// tb-lb-edition - an app for Trittbrett e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Native BLE layer for Trittbrett e-scooters. Two independent wire families exist, chosen by the
 * advertised BLE name and confirmed by which GATT service is actually present (BleManager.md /
 * tb-unlock/app.js is the source of truth for all of this):
 *
 * ZYD family (FRITZ, PAUL, SULTAN, HILDE, KALLE, EMMA when advertised as "zyd.."/"hw_.."): data
 * service f1f0 (write f1f1, notify f1f2), optional AT/PIN service f2f0 (write f2f1, notify f2f2).
 * Binary frames, CRC-16/MODBUS. Has a BLE speed command (register 0x20).
 *
 * Legacy family (older KALLE v1 / EMMA v1, advertised as exactly "Scooter"): custom service 7777
 * (write 8877, notify 8888). FF 55 frames, additive checksum. No BLE speed command, gear switch only.
 *
 * Units advertise unpredictable, changing names (e.g. "ePFHilde", "Hilde 135.."), so scanning
 * accepts every device; the name classifies it when it can, the GATT service found on connect
 * decides for certain (and overrides a wrong name-based guess).
 */
@SuppressLint("MissingPermission")
final class BleManager {

    private static final String TAG = "lbble";

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static UUID uuid16(String hex4) {
        return UUID.fromString("0000" + hex4 + "-0000-1000-8000-00805f9b34fb");
    }

    private static final UUID ZYD_SERVICE = uuid16("f1f0");
    private static final UUID ZYD_WRITE = uuid16("f1f1");
    private static final UUID ZYD_NOTIFY = uuid16("f1f2");
    private static final UUID ZYD_AT_SERVICE = uuid16("f2f0");
    private static final UUID ZYD_AT_WRITE = uuid16("f2f1");
    private static final UUID ZYD_AT_NOTIFY = uuid16("f2f2");
    private static final UUID LEGACY_SERVICE = uuid16("7777");
    private static final UUID LEGACY_WRITE = uuid16("8877");
    private static final UUID LEGACY_NOTIFY = uuid16("8888");

    private static final long DISCOVER_DELAY_MS = 1500;
    private static final long WRITE_GAP_MS = 200;
    private static final long RECONNECT_BASE_MS = 3000;
    private static final long RECONNECT_MAX_MS = 30000;
    private static final long PUSH_INTERVAL_MS = 500;
    private static final long IDLE_KEEP_INTERVAL_MS = 500;

    interface Listener {
        void onScanResults(String jsonArray);
        void onState(String json);
        void onLiveData(String json);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final SettingsState settings = new SettingsState();
    private final FrameParser parser = new FrameParser(settings);

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private volatile boolean scanning = false;
    private final Map<String, ScanEntry> found = new LinkedHashMap<>();

    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic notifyChar;
    private volatile BluetoothGattCharacteristic writeChar;
    private volatile BluetoothGattCharacteristic atWriteChar;
    private volatile boolean notifyReady = false;
    private volatile boolean connected = false;
    private volatile boolean charsSetupDone = false;

    /** "ZYD" or "LEGACY". Decided from the advertised name, confirmed/overridden by the GATT
     *  service actually found on connect. */
    private volatile String family = "ZYD";
    private volatile String pin = "";

    private String desiredAddress;
    private String deviceName = "";

    private volatile long reconnectDelay = RECONNECT_BASE_MS;

    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writing = false;

    BleManager(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
        try {
            BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) adapter = bm.getAdapter();
        } catch (Throwable t) {
            Log.e(TAG, "adapter init failed", t);
        }
    }

    private static final class ScanEntry {
        String name;
        String address;
        int rssi;
    }

    // ── Scan: accept every device, the name/service classify it, not a scan filter ──

    void scan() {
        try {
            if (adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "scan: adapter unavailable/disabled");
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            synchronized (found) { found.clear(); }
            if (scanning) return;
            ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, s, scanCallback);
            scanning = true;
            Log.i(TAG, "scan started");
        } catch (Throwable t) {
            Log.e(TAG, "scan failed", t);
        }
    }

    void stopScan() {
        try {
            if (scanner != null && scanning) scanner.stopScan(scanCallback);
        } catch (Throwable t) {
            Log.e(TAG, "stopScan failed", t);
        } finally {
            scanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScan(result);
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            if (results != null) for (ScanResult r : results) handleScan(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed code=" + errorCode);
            scanning = false;
        }
    };

    private void handleScan(ScanResult result) {
        try {
            if (result == null || result.getDevice() == null) return;
            String addr = result.getDevice().getAddress();
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null || name.isEmpty()) {
                try { name = result.getDevice().getName(); } catch (Throwable ignored) {}
            }
            // No name filter: Trittbrett units advertise unpredictable, changing names. The user
            // picks their scooter from the full list; classification happens on connect.
            ScanEntry e = new ScanEntry();
            e.name = (name == null) ? "" : name;
            e.address = addr;
            e.rssi = result.getRssi();
            boolean changed;
            synchronized (found) {
                ScanEntry prev = found.get(addr);
                changed = prev == null;
                found.put(addr, e);
            }
            if (changed) { Log.i(TAG, "found: " + e.name + " [" + addr + "] rssi=" + e.rssi); pushScanResults(); }
        } catch (Throwable t) {
            Log.e(TAG, "handleScan failed", t);
        }
    }

    private void pushScanResults() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (found) {
                for (ScanEntry e : found.values()) {
                    JSONObject o = new JSONObject();
                    o.put("name", e.name);
                    o.put("address", e.address);
                    o.put("rssi", e.rssi);
                    arr.put(o);
                }
            }
            if (listener != null) listener.onScanResults(arr.toString());
        } catch (Throwable t) {
            Log.e(TAG, "pushScanResults failed", t);
        }
    }

    /** Advertised-name classifier, 1:1 with the app's ScanFragment (case-insensitive). Returns
     *  "ZYD", "LEGACY" or null (unknown - the GATT service found on connect decides). */
    private static String classifyByName(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(java.util.Locale.US);
        if (n.equals("scooter")) return "LEGACY";
        if (n.startsWith("zyd") || n.startsWith("hw_")) return "ZYD";
        return null;
    }

    /** Set a PIN for the ZYD AT+PWD auth channel (empty = no PIN sent). */
    void setPin(String pinValue) { this.pin = pinValue == null ? "" : pinValue.trim(); }

    // ── Connect / disconnect ──

    void connect(String address, String name) {
        if (name != null && !name.trim().isEmpty()) {
            deviceName = name.trim();
            String f = classifyByName(deviceName);
            if (f != null) family = f;
        }
        connect(address);
    }

    void connect(String address) {
        try {
            if (address == null || address.trim().isEmpty() || adapter == null) return;
            desiredAddress = address.trim();
            stopScan();
            synchronized (found) {
                ScanEntry e = found.get(desiredAddress);
                if (e != null && e.name != null) deviceName = e.name;
            }
            closeGatt();
            BluetoothDevice dev = adapter.getRemoteDevice(desiredAddress);
            if (deviceName == null || deviceName.isEmpty()) {
                try { String n = dev.getName(); if (n != null) deviceName = n; } catch (Throwable ignored) {}
            }
            String f = classifyByName(deviceName);
            if (f != null) family = f;
            parser.btName = deviceName == null ? "" : deviceName;
            Log.i(TAG, "connect() -> " + desiredAddress + " name=" + deviceName + " family(guess)=" + family);
            pushState("connecting");
            gatt = dev.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
        }
    }

    void disconnect() {
        desiredAddress = null;
        stopIdleKeep();
        stopPush();
        try {
            if (gatt != null) gatt.disconnect();
        } catch (Throwable t) {
            Log.e(TAG, "disconnect failed", t);
        }
        closeGatt();
        connected = false;
        notifyReady = false;
        pushState("disconnected");
    }

    String lastDeviceJson() {
        try {
            SharedPreferences sp = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE);
            String addr = sp.getString("last_device_addr", "");
            if (addr == null || addr.isEmpty()) return "";
            String name = sp.getString("last_device_name", "");
            JSONObject o = new JSONObject();
            o.put("address", addr);
            o.put("name", name == null ? "" : name);
            return o.toString();
        } catch (Throwable t) {
            Log.e(TAG, "lastDeviceJson failed", t);
            return "";
        }
    }

    void connectLast() {
        try {
            if (connected || desiredAddress != null) return;
            SharedPreferences sp = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE);
            String addr = sp.getString("last_device_addr", "");
            String name = sp.getString("last_device_name", "");
            if (addr != null && !addr.isEmpty()) {
                Log.i(TAG, "connectLast() -> " + addr);
                connect(addr, name);
            }
        } catch (Throwable t) {
            Log.e(TAG, "connectLast failed", t);
        }
    }

    private void closeGatt() {
        try {
            if (gatt != null) gatt.close();
        } catch (Throwable ignored) {
        } finally {
            gatt = null;
            notifyChar = null;
            writeChar = null;
            atWriteChar = null;
            notifyReady = false;
            charsSetupDone = false;
            synchronized (writeQueue) { writeQueue.clear(); writing = false; }
        }
    }

    // ── GATT callback ──

    private long frameCount = 0;
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected");
                pushState("discovering");
                // ZYD monitor-A frames are up to 25 bytes (23 payload + 2 CRC), over the default
                // ATT MTU's 20 usable bytes - request a bigger MTU so a frame is never truncated.
                try { if (g != null) g.requestMtu(64); } catch (Throwable ignored) {}
                main.postDelayed(() -> {
                    try { if (gatt != null) gatt.discoverServices(); } catch (Throwable ignored) {}
                }, DISCOVER_DELAY_MS);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected status=" + status);
                connected = false;
                notifyReady = false;
                stopIdleKeep();
                stopPush();
                closeGatt();
                pushState("disconnected");
                if (desiredAddress != null) {
                    long delay = reconnectDelay;
                    Log.i(TAG, "scheduling reconnect in " + delay + " ms (backoff)");
                    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
                    main.postDelayed(() -> {
                        if (desiredAddress != null) connect(desiredAddress);
                    }, delay);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            try {
                Log.i(TAG, "onServicesDiscovered status=" + status + " count=" + (g == null ? 0 : g.getServices().size()));
                if (g != null && g.getDevice() != null) {
                    try { String n = g.getDevice().getName(); if (n != null && !n.isEmpty()) {
                        deviceName = n; parser.btName = n;
                        String f = classifyByName(n); if (f != null) family = f;
                    } } catch (Throwable ignored) {}
                }
                setupCharacteristics(g);
            } catch (Throwable t) {
                Log.e(TAG, "onServicesDiscovered failed", t);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "CCCD write status=" + status);
            notifyReady = true;
            connected = true;
            reconnectDelay = RECONNECT_BASE_MS;
            try {
                SharedPreferences.Editor ed = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE).edit()
                        .putString("last_device_addr", desiredAddress);
                if (deviceName != null && !deviceName.isEmpty()) ed.putString("last_device_name", deviceName);
                ed.apply();
            } catch (Throwable ignored) {}
            pushState("connected");
            startPush();
            runConnectHandshake();
            drainWriteQueue();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            // Only the data-channel queue (writeChar) is serialised this way; AT-channel writes
            // (atWriteChar) go straight to the GATT layer and must not touch this flag/queue, or a
            // completing AT write can race a still-in-flight data-channel write.
            if (c != writeChar) return;
            synchronized (writeQueue) { writing = false; }
            main.postDelayed(BleManager.this::drainWriteQueue, WRITE_GAP_MS);
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.i(TAG, "MTU changed to " + mtu + " status=" + status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            try {
                byte[] v = c.getValue();
                if (v != null) {
                    parser.onNotify(v);
                    if (frameCount++ % 50 == 0) Log.i(TAG, "rx frames=" + frameCount + " last=" + v.length + "b");
                }
            } catch (Throwable t) {
                Log.e(TAG, "onCharacteristicChanged failed", t);
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            parser.rssi = rssi;
        }
    };

    /** Fixed, known service/characteristic UUIDs per family - no heuristic property scan needed
     *  (unlike the base app, Trittbrett's UUIDs are all exactly known). The GATT service actually
     *  present overrides a wrong or missing name-based family guess. */
    private void setupCharacteristics(BluetoothGatt g) {
        if (g == null) return;
        synchronized (this) {
            if (charsSetupDone) return;
            charsSetupDone = true;
        }
        boolean hasZyd = g.getService(ZYD_SERVICE) != null;
        boolean hasLegacy = g.getService(LEGACY_SERVICE) != null;
        if (hasZyd) family = "ZYD";
        else if (hasLegacy) family = "LEGACY";
        else {
            Log.w(TAG, "neither F1F0 nor 7777 present - not a Trittbrett scooter");
            pushState("no-service");
            return;
        }
        parser.family = family;

        if ("ZYD".equals(family)) {
            BluetoothGattService svc = g.getService(ZYD_SERVICE);
            notifyChar = svc.getCharacteristic(ZYD_NOTIFY);
            writeChar = svc.getCharacteristic(ZYD_WRITE);
            BluetoothGattService at = g.getService(ZYD_AT_SERVICE);
            atWriteChar = at != null ? at.getCharacteristic(ZYD_AT_WRITE) : null;
            BluetoothGattCharacteristic atNotify = at != null ? at.getCharacteristic(ZYD_AT_NOTIFY) : null;
            if (atNotify != null) enableNotifications(g, atNotify);
        } else {
            BluetoothGattService svc = g.getService(LEGACY_SERVICE);
            notifyChar = svc.getCharacteristic(LEGACY_NOTIFY);
            writeChar = svc.getCharacteristic(LEGACY_WRITE);
        }

        if (notifyChar == null || writeChar == null) {
            Log.w(TAG, "notify/write characteristic missing for family=" + family);
            pushState("no-char");
            return;
        }
        Log.i(TAG, "family=" + family + " notify=" + notifyChar.getUuid() + " write=" + writeChar.getUuid());
        enableNotifications(g, notifyChar);
    }

    private void enableNotifications(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        try {
            g.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean ok = g.writeDescriptor(cccd);
                Log.i(TAG, "writeDescriptor(CCCD) " + ch.getUuid() + " initiated=" + ok);
                if (!ok && ch == notifyChar) main.post(this::forceReady);
            } else if (ch == notifyChar) {
                Log.w(TAG, "CCCD descriptor missing; proceeding");
                main.post(this::forceReady);
            }
        } catch (Throwable t) {
            Log.e(TAG, "enableNotifications failed", t);
            if (ch == notifyChar) main.post(this::forceReady);
        }
    }

    private void forceReady() {
        notifyReady = true;
        connected = true;
        reconnectDelay = RECONNECT_BASE_MS;
        pushState("connected");
        startPush();
        runConnectHandshake();
        drainWriteQueue();
    }

    // ── Post-connect handshake (belegt against BleCore.listener.onConnectStatusChanged) ──

    private void runConnectHandshake() {
        if ("LEGACY".equals(family)) {
            enqueueWrite(CommandBuilder.ff55Frame(0x01, new byte[0]));   // confirm
            startLegacyKeep();
            return;
        }
        // ZYD: 5x sendStopTran (force OUT of any leftover UF mode) + 3x sendKeep (enter monitor
        // mode), THEN the PIN, THEN the idle heartbeat, THEN one ESC-info request. Order is
        // load-bearing - see CommandBuilder.zydTranFrame's doc and the tb-unlock v14/v15 history:
        // a build that skipped step 1 and looped sendTran as its idle heartbeat left the scooter
        // latched in UF mode for the whole connection (display bypassed, throttle unresponsive).
        for (int i = 0; i < 5; i++) {
            final int delay = i * 50;
            main.postDelayed(() -> writeDirect(CommandBuilder.zydTranFrame(0xFF)), delay);
        }
        for (int i = 0; i < 3; i++) {
            final int delay = 250 + i * 50;
            main.postDelayed(() -> writeDirect(CommandBuilder.zydKeepFrame()), delay);
        }
        main.postDelayed(() -> {
            if (!pin.isEmpty() && atWriteChar != null) {
                try {
                    atWriteChar.setValue(CommandBuilder.atPassword(pin));
                    if (gatt != null) gatt.writeCharacteristic(atWriteChar);
                } catch (Throwable t) { Log.e(TAG, "AT+PWD failed", t); }
            }
        }, 420);
        main.postDelayed(() -> {
            startZydIdleKeep();
            enqueueWrite(CommandBuilder.zydReadFrame(0x07, 0, 4));   // ESC-info request
        }, 620);
    }

    /** Write straight to the characteristic, bypassing the serialised queue - only for the fixed
     *  handshake burst, which is itself already time-spaced and must not wait behind other writes. */
    private void writeDirect(byte[] frame) {
        try {
            BluetoothGatt g = gatt;
            BluetoothGattCharacteristic wc = writeChar;
            if (g == null || wc == null || frame == null) return;
            wc.setValue(frame);
            g.writeCharacteristic(wc);
        } catch (Throwable t) {
            Log.e(TAG, "writeDirect failed", t);
        }
    }

    // ── Idle heartbeat: ZYD sendKeep ("Keep Monitor Mode"), Legacy FF5501, never sendTran ──

    private final Runnable zydIdleKeep = new Runnable() {
        @Override public void run() {
            if (!notifyReady) return;
            enqueueWrite(CommandBuilder.zydKeepFrame());
            main.postDelayed(this, IDLE_KEEP_INTERVAL_MS);
        }
    };

    private final Runnable legacyKeep = new Runnable() {
        @Override public void run() {
            if (!notifyReady) return;
            enqueueWrite(CommandBuilder.ff55Frame(0x01, new byte[0]));
            main.postDelayed(this, IDLE_KEEP_INTERVAL_MS);
        }
    };

    private void startZydIdleKeep() { main.removeCallbacks(zydIdleKeep); main.post(zydIdleKeep); }
    private void startLegacyKeep() { main.removeCallbacks(legacyKeep); main.post(legacyKeep); }
    private void stopIdleKeep() { main.removeCallbacks(zydIdleKeep); main.removeCallbacks(legacyKeep); }

    // ── Live-data push (~2x/s) ──

    private final Runnable pushTask = new Runnable() {
        @Override
        public void run() {
            if (!connected) return;
            try {
                if (listener != null) listener.onLiveData(parser.toJson());
            } catch (Throwable t) {
                Log.e(TAG, "push failed", t);
            }
            try { if (gatt != null) gatt.readRemoteRssi(); } catch (Throwable ignored) {}
            main.postDelayed(this, PUSH_INTERVAL_MS);
        }
    };

    private void startPush() {
        main.removeCallbacks(pushTask);
        main.postDelayed(pushTask, PUSH_INTERVAL_MS);
    }

    private void stopPush() {
        main.removeCallbacks(pushTask);
    }

    // ── Write queue (serialised GATT writes) ──

    private void enqueueWrite(byte[] frame) {
        if (frame == null) return;
        synchronized (writeQueue) { writeQueue.add(frame); }
        drainWriteQueue();
    }

    private void drainWriteQueue() {
        if (!notifyReady) return;
        byte[] frame;
        synchronized (writeQueue) {
            if (writing) return;
            frame = writeQueue.poll();
            if (frame == null) return;
            writing = true;
        }
        boolean started = doWrite(frame);
        if (!started) {
            synchronized (writeQueue) { writing = false; }
            main.postDelayed(this::drainWriteQueue, WRITE_GAP_MS);
        }
    }

    private boolean doWrite(byte[] frame) {
        try {
            BluetoothGatt g = gatt;
            BluetoothGattCharacteristic wc = writeChar;
            if (g == null || wc == null) return false;
            int props = wc.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            wc.setValue(frame);
            return g.writeCharacteristic(wc);
        } catch (Throwable t) {
            Log.e(TAG, "doWrite failed", t);
            return false;
        }
    }

    private final Handler seq = new Handler(Looper.getMainLooper());

    /**
     * ZYD register-write sequence (CMD_RW_PARAMETER 0x17), exact order or the controller ignores
     * it: pause the idle heartbeat, wait 150 ms, one sendTran nudge, wait 30 ms, the write itself,
     * wait 30 ms, sendStopTran to release UF mode again, then resume the idle heartbeat. A single
     * write must never leave the scooter latched in UF mode.
     */
    private void sendZydParam(byte[] frame) {
        if (!connected || writeChar == null) return;
        stopIdleKeep();
        seq.postDelayed(() -> {
            writeDirect(CommandBuilder.zydTranFrame(0x00));
            seq.postDelayed(() -> {
                writeDirect(frame);
                seq.postDelayed(() -> {
                    writeDirect(CommandBuilder.zydTranFrame(0xFF));
                    if (connected) startZydIdleKeep();
                }, 30);
            }, 30);
        }, 150);
    }

    // ── Base-param write (ZYD monitor frame): change one field, resend the rest unchanged ──

    private void writeMonitor() {
        enqueueWrite(settings.monitorFrame());
    }

    void setLock(boolean unlocked) {
        try {
            if ("LEGACY".equals(family)) {
                enqueueWrite(CommandBuilder.ff55Frame(0x17, new byte[]{(byte) (unlocked ? 0x01 : 0x02)}));
            } else {
                settings.lock = !unlocked;
                writeMonitor();
            }
        } catch (Throwable t) {
            Log.e(TAG, "setLock failed", t);
        }
    }

    // ── Register encoding (opv / int / realmax / index), all 16-bit BE at the register address ──

    private static byte[] enc16(double value, String kind, double factor) {
        int n;
        if ("realmax".equals(kind)) n = ((int) Math.round(value)) * (int) factor;
        else if ("opv".equals(kind)) n = (int) Math.round(value * factor);
        else n = (int) Math.round(value);   // "int" / "index"
        n &= 0xFFFF;
        return new byte[]{(byte) ((n >>> 8) & 0xFF), (byte) (n & 0xFF)};
    }

    /**
     * Per-key settings dispatcher, mirroring the tb-unlock/app.js SETTINGS table: one frame per
     * key, no full-state merge (unlike the base app's single Teverun 0x18 frame). ZYD-only keys are
     * ignored on a Legacy scooter (no register/AT/most base-param path exists there).
     */
    void sendSetting(String json) {
        try {
            JSONObject o = (json == null || json.trim().isEmpty()) ? null : new JSONObject(json);
            if (o == null) return;

            if ("LEGACY".equals(family)) {
                if (o.has("gear")) {
                    int g = o.optInt("gear", 0);
                    enqueueWrite(CommandBuilder.ff55Frame(0x1F, new byte[]{(byte) (g == 0 ? 0x02 : 0x03)}));
                }
                return;
            }

            // Base params (ZYD monitor frame) - change the field, resend the whole frame.
            boolean touchedBase = false;
            if (o.has("headlight")) { settings.headlight = o.optBoolean("headlight"); touchedBase = true; }
            if (o.has("ambient")) { settings.ambient = o.optBoolean("ambient"); touchedBase = true; }
            if (o.has("gear")) { settings.gear = o.optInt("gear", settings.gear) & 1; touchedBase = true; }
            if (o.has("zeroStart")) { settings.boot = !o.optBoolean("zeroStart"); touchedBase = true; }   // inverted
            if (o.has("cruiseOff")) { settings.cruise = false; touchedBase = true; }
            if (o.has("unit")) { settings.imperial = o.optBoolean("unit"); touchedBase = true; }
            if (o.has("limitCruise")) { settings.limitCruise = o.optInt("limitCruise", settings.limitCruise); touchedBase = true; }
            if (touchedBase) writeMonitor();

            // AT channel (ASCII, needs the f2f1 characteristic).
            if (o.has("name") && atWriteChar != null) {
                writeAt(CommandBuilder.atName(o.optString("name", "")));
            }
            if (o.has("startSound")) writeAt(CommandBuilder.atSound("MP3", o.optInt("startSound", 1)));
            if (o.has("shutdownSound")) writeAt(CommandBuilder.atSound("MP31", o.optInt("shutdownSound", 1)));
            if (o.has("hornSound")) writeAt(CommandBuilder.atSound("MP32", o.optInt("hornSound", 1)));
            if (o.has("alarmSound")) writeAt(CommandBuilder.atSound("MP34", o.optInt("alarmSound", 1)));

            // Speed (register 0x20) has its own dedicated method (see setSpeed) but is also reachable
            // generically here so the settings sheet can treat it like any other register.
            if (o.has("speed")) sendZydParam(CommandBuilder.zydSpeedFrame(o.optDouble("speed", 20)));

            // Registers (CMD_RW_PARAMETER 0x17), all 16-bit BE.
            regIfPresent(o, "throttleAccel", 0x09, "realmax", 3000);
            regIfPresent(o, "throttleBrake", 0x0a, "realmax", 3000);
            regIfPresent(o, "cruiseTime", 0x33, "int", 1);
            regIfPresent(o, "shutdownTime", 0x34, "int", 1);
            regIfPresent(o, "wheel", 0x17, "opv", 25.4);
            regIfPresent(o, "carrier", 0x21, "index", 1);
            regIfPresent(o, "serviceKm", 0x4a, "int", 1);
            regIfPresent(o, "modDepth", 0x02, "realmax", 436);
            regIfPresent(o, "polePairs", 0x04, "int", 1);
            regIfPresent(o, "dischargeCur", 0x0b, "opv", 64);
            regIfPresent(o, "brakeCur", 0x0c, "opv", 64);
            regIfPresent(o, "voltProt", 0x13, "opv", 10);
        } catch (Throwable t) {
            Log.e(TAG, "sendSetting failed", t);
        }
    }

    private void regIfPresent(JSONObject o, String key, int addr, String kind, double factor) {
        if (!o.has(key)) return;
        double v = o.optDouble(key, 0);
        sendZydParam(CommandBuilder.zydRwParamFrame(addr, enc16(v, kind, factor)));
    }

    /** Direct speed set (register 0x20), used by the Speed card's unlock/lock buttons. */
    void setSpeed(double kmh) {
        if (!"ZYD".equals(family)) return;
        sendZydParam(CommandBuilder.zydSpeedFrame(kmh));
    }

    private void writeAt(byte[] cmd) {
        try {
            if (atWriteChar == null || gatt == null) return;
            atWriteChar.setValue(cmd);
            gatt.writeCharacteristic(atWriteChar);
        } catch (Throwable t) {
            Log.e(TAG, "AT write failed", t);
        }
    }

    // ── State reporting ──

    private void pushState(String status) {
        try {
            JSONObject o = new JSONObject();
            o.put("connected", connected);
            o.put("name", deviceName == null ? "" : deviceName);
            o.put("address", desiredAddress == null ? "" : desiredAddress);
            o.put("family", family);
            o.put("status", status == null ? "" : status);
            if (listener != null) listener.onState(o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "pushState failed", t);
        }
    }

    void shutdown() {
        stopScan();
        disconnect();
    }
}
