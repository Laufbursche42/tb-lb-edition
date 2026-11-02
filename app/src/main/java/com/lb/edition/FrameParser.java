// tb-lb-edition - an app for Trittbrett e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Decodes incoming BLE notifications (VCU -> phone) for both Trittbrett families. Each notification
 * is treated as one complete frame (the reference implementation, tb-unlock/app.js, never
 * accumulates or splits notifications for this protocol - unlike some sibling forks' fixed-length
 * framing, Trittbrett frames are not packed multiple-per-notification in practice).
 *
 * Thread-safety: onNotify() runs on the GATT callback thread; toJson() may run on the UI thread. All
 * mutable model fields are guarded by this instance's monitor.
 */
final class FrameParser {

    private static final String TAG = "lbble";

    private final SettingsState settings;

    /** "ZYD" or "LEGACY", set by BleManager once the family is known (name, then GATT service). */
    volatile String family = "ZYD";

    // Phone-side RSSI (set by BleManager); not part of the VCU frames.
    volatile int rssi = 0;

    // Bluetooth advertised name of the connected scooter (set by BleManager); not part of the VCU
    // frames, shown as-is (Trittbrett names are unpredictable, e.g. "ePFHilde", "Hilde 135..").
    volatile String btName = "";

    // ── ZYD live model (monitor Frame A) ──
    private double speed, volt, current, power;
    private int battPct, escTemp, motTemp;
    private double trip, total;

    // ── ZYD live model (monitor Frame B) ──
    private int battTemp;
    private int faultWord;
    private int capRemaining, capTotal;
    private String displayVer = "";

    // ── ESC info (head 0x01 cmd 0x07): five 16-byte ASCII strings streamed in 8-byte chunks ──
    private final byte[] escInfoBuf = new byte[80];
    private String fwModel = "", fwHardware = "", fwBoot = "", fwFirmware = "", fwUniquecode = "";

    // ── Battery info (head 0x01 cmd 0x08): one 16-byte ASCII serial, streamed in 8-byte chunks ──
    private final byte[] batInfoBuf = new byte[16];
    private String batterySN = "";

    // Frame B charge cycle count (buf[16..17], u16BE). Scale UNCONFIRMED pending a live capture.
    private int batteryCycle = -1;

    // ── Legacy live model (FF 55 raw path; each value is its own streamed opcode frame) ──
    private double legacySpeed, legacyVolt, legacyTrip = -1, legacyTotal = -1, legacyWheel = -1;
    private int legacyBatt = -1, legacyTemp = Integer.MIN_VALUE, legacyDriveTime = -1;
    private int legacyGear = -1, legacyLock = -1, legacyUnit = -1, legacyStart = -1;
    private int legacyNominalV = -1, legacyCruise = -1, legacySelfCheck = -1;

    FrameParser(SettingsState settings) {
        this.settings = settings;
    }

    /**
     * Treat the whole notification as one frame (no cross-notification reassembly - see class doc)
     * and decode it per the active family.
     */
    void onNotify(byte[] v) {
        if (v == null || v.length < 2) return;
        try {
            if ("LEGACY".equals(family)) dispatchLegacy(v); else dispatchZyd(v);
        } catch (Throwable ignored) {
            // never let a malformed frame break the pipeline
        }
    }

    // ── ZYD: head 0xAB monitor A/B, head 0x01 info/ack, CRC-16/MODBUS over all but the last 2 bytes ──

    private void dispatchZyd(byte[] v) {
        int n = v.length;
        if (n < 4) return;
        int computed = CommandBuilder.crc16Modbus(v, n - 2);
        int got = (v[n - 2] & 0xFF) | ((v[n - 1] & 0xFF) << 8);
        if (computed != got) return;   // drop silently, exactly like the reference implementation
        int head = v[0] & 0xFF;
        if (head == 0xAB) {
            decodeMonitor(v);
        } else if (head == 0x01) {
            int cmd = v[1] & 0xFF;
            if (cmd == 0x07 && n >= 6) decodeEscInfo(v);
            else if (cmd == 0x08 && n >= 6) decodeBatInfo(v);
            // any other head-0x01 command is a plain ack for a register write - nothing to decode
        }
    }

    private synchronized void decodeMonitor(byte[] b) {
        int sub = b[1] & 0xFF;
        if (sub == 0x00 && b.length >= 23) {
            battPct = u8(b, 5);
            double s1 = u16(b, 6), s2 = u16(b, 8);
            speed = Math.max(s1, s2) / 1000.0 * (settings.thousandUnitsEnable ? 100.0 : 1.0);
            volt = u16(b, 10) / 10.0;
            current = s16(b, 12) / 64.0;
            escTemp = s8(b, 14);
            motTemp = s8(b, 15);
            trip = u16(b, 16) / 10.0;
            total = (((b[18] & 0xFF) << 16) | ((b[19] & 0xFF) << 8) | (b[20] & 0xFF)) / 10.0;
            power = volt * current;
            settings.updateFromMonitorA(b);
        } else if (sub == 0x01 && b.length >= 16) {
            battTemp = s8(b, 7);
            faultWord = u16(b, 8);
            capRemaining = u16(b, 14);
            capTotal = u16(b, 12);
            if (b.length >= 18) batteryCycle = u16(b, 16);   // buf[16..17]; scale unconfirmed
            if (b.length >= 23) displayVer = "V" + u8(b, 20) + "." + u8(b, 21) + "." + u8(b, 22);
            settings.updateFromMonitorB(b);
        }
    }

    /** off = u16BE(b,2); copy b[5..] into the 80-byte accumulator at offset off. Complete at off+8>=64. */
    private synchronized void decodeEscInfo(byte[] b) {
        int off = u16(b, 2);
        for (int i = 0; i < 8 && (5 + i) < b.length && (off + i) < escInfoBuf.length; i++) {
            escInfoBuf[off + i] = b[5 + i];
        }
        if (off + 8 >= 80) {
            fwModel = asciiClean(escInfoBuf, 0, 16);
            fwHardware = asciiClean(escInfoBuf, 16, 32);
            fwBoot = asciiClean(escInfoBuf, 32, 48);
            fwFirmware = asciiClean(escInfoBuf, 48, 64);
            fwUniquecode = asciiClean(escInfoBuf, 64, 80);
        }
    }

    /** off = u16BE(b,2); copy b[5..] into the 16-byte accumulator at offset off. Complete at off+8>=16. */
    private synchronized void decodeBatInfo(byte[] b) {
        int off = u16(b, 2);
        for (int i = 0; i < 8 && (5 + i) < b.length && (off + i) < batInfoBuf.length; i++) {
            batInfoBuf[off + i] = b[5 + i];
        }
        if (off + 8 >= 16) batterySN = asciiClean(batInfoBuf, 0, 16);
    }

    // ── Legacy: head 0xFF, op = t[2], additive checksum over all but the last byte. Each telemetry
    //    value arrives as its own FF 55 opcode frame; payload = bytes 4..n-2, big-endian unsigned. ──

    private static final int[] NOMINAL_V = {0, 24, 36, 48, 60};   // raw 0x1B payload index -> volts

    private synchronized void dispatchLegacy(byte[] v) {
        int n = v.length;
        if (n < 5 || (v[0] & 0xFF) != 0xFF) return;
        int sum = 0;
        for (int i = 0; i < n - 1; i++) sum += (v[i] & 0xFF);
        if ((sum & 0xFF) != (v[n - 1] & 0xFF)) return;
        int op = v[2] & 0xFF;
        int len = v[3] & 0xFF;
        long pv = beValue(v, 4, n - 1);             // numeric payload (speed/dist/temp/time/wheel)
        int pb = (n >= 6) ? (v[4] & 0xFF) : -1;     // first payload byte (enum state frames)
        switch (op) {
            case 0x0A: legacySpeed = pv * 0.001; break;                 // km/h
            case 0x0B: legacyTrip = pv * 0.001; break;                  // trip km
            case 0x0C: legacyTotal = pv * 0.001; break;                 // total km
            case 0x0D: legacyBatt = (int) pv; break;                    // %
            case 0x0E: legacyVolt = pv * 0.001; break;                  // V
            case 0x11: legacyTemp = (int) pv; break;                    // controller temp degC
            case 0x22: legacyDriveTime = (int) pv; break;               // s
            case 0x1F: if (pb == 0x02) legacyGear = 0; else if (pb == 0x03) legacyGear = 1; break;   // D1/D2
            case 0x17: if (len == 1) { if (pb == 0x01) legacyLock = 0; else if (pb == 0x02) legacyLock = 1; } break;  // unlock/lock (len 5 = code-lock, skip)
            case 0x18: if (pb == 0x01) legacyUnit = 0; else if (pb == 0x02) legacyUnit = 1; break;   // metric/mile
            case 0x1A: if (pb == 0x01) legacyStart = 1; else if (pb == 0x02) legacyStart = 0; break; // zero-start / foot-kick
            case 0x1B: if (pb >= 1 && pb <= 4) legacyNominalV = NOMINAL_V[pb]; break;
            case 0x1C: if (len == 2) legacyWheel = pv / 10.0; break;    // 50->5.0" 65->6.5" 85->8.5"
            case 0x1D: if (pb == 0x01) legacyCruise = 1; else if (pb == 0x02) legacyCruise = 0; break;
            case 0x1E: if (len == 2) legacySelfCheck = (int) pv; break; // u16 error bitfield
            default: break;   // 0x01 confirm/keep-alive ack and anything else: nothing to decode
        }
    }

    private static final int[] SC_BITS = {0x0001, 0x0002, 0x0004, 0x0008, 0x0010, 0x0020};
    private static final String[] SC_NAMES = {"throttle", "brake", "hall", "hardware", "battery", "lowBattery"};

    private JSONArray legacyFaultList() {
        JSONArray a = new JSONArray();
        if (legacySelfCheck > 0) {
            for (int i = 0; i < SC_BITS.length; i++) {
                if ((legacySelfCheck & SC_BITS[i]) != 0) a.put(SC_NAMES[i]);
            }
        }
        return a;
    }

    private static long beValue(byte[] b, int from, int toExclusive) {
        long v = 0;
        for (int i = from; i < toExclusive; i++) v = (v << 8) | (b[i] & 0xFF);
        return v;
    }

    // ── helpers ──

    private static int u8(byte[] b, int i) { return b[i] & 0xFF; }

    private static int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    private static int s16(byte[] b, int i) {
        int v = u16(b, i);
        return (v & 0x8000) != 0 ? v - 0x10000 : v;
    }

    private static int s8(byte[] b, int i) {
        int v = b[i] & 0xFF;
        return (v & 0x80) != 0 ? v - 0x100 : v;
    }

    private static String asciiClean(byte[] buf, int from, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < toExclusive && i < buf.length; i++) {
            int c = buf[i] & 0xFF;
            if (c >= 0x20 && c <= 0x7E) sb.append((char) c);
        }
        return sb.toString().trim();
    }

    private static final int[] FAULT_BITS = {1, 2, 3, 4, 7, 9, 10, 11};
    private static final String[] FAULT_NAMES = {"E1", "E2", "E3", "E4", "E7", "E9", "F1", "F2"};

    private JSONArray faultList() {
        JSONArray a = new JSONArray();
        for (int i = 0; i < FAULT_BITS.length; i++) {
            if (((faultWord >> FAULT_BITS[i]) & 1) != 0) a.put(FAULT_NAMES[i]);
        }
        return a;
    }

    // ── JSON serialisation for the dashboard (window.__onBleData / lb_live_data) ──

    synchronized String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("family", family);
            o.put("rssi", rssi);
            o.put("btName", btName);
            o.put("ts", System.currentTimeMillis());

            if ("LEGACY".equals(family)) {
                o.put("speed", round1(legacySpeed));
                if (legacyVolt > 0) o.put("volt", round1(legacyVolt));
                if (legacyTrip >= 0) o.put("trip", round1(legacyTrip));
                if (legacyTotal >= 0) o.put("total", round1(legacyTotal));
                if (legacyBatt >= 0) o.put("batt", legacyBatt);
                if (legacyTemp != Integer.MIN_VALUE) o.put("escTemp", legacyTemp);
                if (legacyDriveTime >= 0) o.put("driveTime", legacyDriveTime);
                if (legacyGear >= 0) o.put("gear", legacyGear);
                if (legacyLock >= 0) o.put("lock", legacyLock == 1);
                if (legacyUnit >= 0) o.put("imperial", legacyUnit == 1);
                if (legacyStart >= 0) o.put("boot", legacyStart == 1);
                if (legacyCruise >= 0) o.put("cruise", legacyCruise == 1);
                if (legacyNominalV > 0) o.put("nominalVoltage", legacyNominalV);
                if (legacyWheel > 0) o.put("wheelDiameter", round1(legacyWheel));
                if (legacySelfCheck >= 0) o.put("faults", legacyFaultList());
            } else {
                o.put("speed", round1(speed));
                o.put("batt", battPct);
                o.put("volt", round1(volt));
                o.put("current", round1(current));
                o.put("power", round2(power));
                o.put("escTemp", escTemp);
                o.put("motTemp", motTemp);
                o.put("trip", round1(trip));
                o.put("total", round1(total));
                o.put("battTemp", battTemp);
                o.put("capRemaining", capRemaining);
                o.put("capTotal", capTotal);
                o.put("faults", faultList());
                if (!displayVer.isEmpty()) o.put("displayVer", displayVer);
                if (!fwFirmware.isEmpty()) o.put("fwFirmware", fwFirmware);
                if (!fwModel.isEmpty()) o.put("fwModel", fwModel);
                if (!fwHardware.isEmpty()) o.put("fwHardware", fwHardware);
                if (!fwBoot.isEmpty()) o.put("fwBoot", fwBoot);
                if (!fwUniquecode.isEmpty()) o.put("fwUniquecode", fwUniquecode);
                if (!batterySN.isEmpty()) o.put("batterySN", batterySN);
                if (batteryCycle >= 0) o.put("batteryCycle", batteryCycle);

                // Maintained base params (SettingsState.bp), so the settings page can prefill without
                // ever writing a stale value back - mirrors settingsReady()/received71 on the base app.
                o.put("settingsReady", settings.receivedMonitor);
                if (settings.receivedMonitor) {
                    o.put("gear", settings.gear);
                    o.put("headlight", settings.headlight);
                    o.put("ambient", settings.ambient);
                    o.put("cruise", settings.cruise);
                    o.put("boot", settings.boot);
                    o.put("imperial", settings.imperial);
                    o.put("lock", settings.lock);
                    o.put("blinkLeft", settings.blinkLeft);
                    o.put("blinkRight", settings.blinkRight);
                    o.put("limitCruise", settings.limitCruise);
                    o.put("m1", settings.m1);
                    o.put("m2", settings.m2);
                    o.put("m3", settings.m3);
                }
            }
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
