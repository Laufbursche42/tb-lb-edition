// tb-lb-edition - an app for Trittbrett e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.util.Log;

/**
 * Builds every outgoing frame (phone -> VCU) for the two Trittbrett wire protocols. Trittbrett
 * picks one of two independent families by advertised BLE name (see BleManager): ZYD (binary
 * frames, CRC-16/MODBUS) and Legacy (FF 55 frames, additive checksum). Frame shapes are documented
 * per builder below; the authoritative source is the static analysis in tb-unlock/app.js
 * (com.planm.trittbrett 2.1.0), not this app's own protocol - nothing here is guessed.
 */
final class CommandBuilder {

    private static final String TAG = "lbcmd";

    private CommandBuilder() {}

    // ── CRC-16/MODBUS (ZYD family): poly 0x8005 reflected (0xA001 in the loop), init 0xFFFF, ──
    // ── refin/refout true, xorout 0. Appended LOW byte first, then HIGH byte. ──

    static int crc16Modbus(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int n = 0; n < 8; n++) {
                crc = ((crc & 1) != 0) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1);
            }
        }
        return crc & 0xFFFF;
    }

    /** Append the CRC-16/MODBUS of {@code head} (low byte first, then high byte). */
    private static byte[] zydAppendCrc(int[] head) {
        byte[] h = new byte[head.length];
        for (int i = 0; i < head.length; i++) h[i] = (byte) (head[i] & 0xFF);
        int crc = crc16Modbus(h, h.length);
        byte[] out = new byte[head.length + 2];
        System.arraycopy(h, 0, out, 0, h.length);
        out[head.length] = (byte) (crc & 0xFF);
        out[head.length + 1] = (byte) ((crc >>> 8) & 0xFF);
        return out;
    }

    // ── ZYD read/request (8 bytes): 01 cmd addrHi addrLo cntHi cntLo crcLo crcHi ──

    static byte[] zydReadFrame(int cmd, int addr, int cnt) {
        return zydAppendCrc(new int[]{
                0x01, cmd & 0xFF,
                (addr >>> 8) & 0xFF, addr & 0xFF,
                (cnt >>> 8) & 0xFF, cnt & 0xFF,
        });
    }

    // ── ZYD monitor / base-params (10 bytes): AB 00 0A valueByte limitCruise m1 m2 m3 crcLo crcHi ──
    // valueByte packs gear/lights/cruise/boot/unit/lock (see zydStatusByte); limitCruise is the
    // cruise speed cap; m1/m2/m3 are the three gear speed limits (km/h). This is the ONE frame that
    // carries all of those fields, so a single-field change must resend the others unchanged - the
    // caller is responsible for tracking the current values (SettingsState) and passing them all in.

    static byte[] zydMonitorFrame(int valueByte, int limitCruise, int m1, int m2, int m3) {
        return zydAppendCrc(new int[]{
                0xAB, 0x00, 0x0A,
                valueByte & 0xFF, limitCruise & 0xFF, m1 & 0xFF, m2 & 0xFF, m3 & 0xFF,
        });
    }

    /**
     * Pack the monitor-frame value byte: bit0-1 gear, bit2 headlight, bit3 ambient, bit4 cruise,
     * bit5 boot(kickstart), bit6 imperial(unit), bit7 lock. This is the WRITE packing; the inbound
     * monitor status word (FrameParser) uses different bit positions - the two must never be
     * confused.
     */
    static int zydStatusByte(int gear, boolean headlight, boolean ambient, boolean cruise,
                              boolean boot, boolean imperial, boolean lock) {
        int v = gear & 0x03;
        if (headlight) v |= 1 << 2;
        if (ambient) v |= 1 << 3;
        if (cruise) v |= 1 << 4;
        if (boot) v |= 1 << 5;
        if (imperial) v |= 1 << 6;
        if (lock) v |= 1 << 7;
        return v & 0xFF;
    }

    // ── ZYD register write (CMD_RW_PARAMETER 0x17): ──
    // 01 17 addrHi addrLo wcntHi wcntLo addrHi addrLo wcntHi wcntLo byteCount value.. crcLo crcHi
    // wcnt = floor(valueBytes.length/2) (16-bit word count); the address+wordcount pair is repeated
    // twice; the value is big-endian at the register address.

    static byte[] zydRwParamFrame(int addr, byte[] valueBytes) {
        int words = valueBytes.length / 2;
        int n = valueBytes.length;
        int[] head = new int[11 + n];
        head[0] = 0x01;
        head[1] = 0x17;
        head[2] = (addr >>> 8) & 0xFF;
        head[3] = addr & 0xFF;
        head[4] = (words >>> 8) & 0xFF;
        head[5] = words & 0xFF;
        head[6] = (addr >>> 8) & 0xFF;
        head[7] = addr & 0xFF;
        head[8] = (words >>> 8) & 0xFF;
        head[9] = words & 0xFF;
        head[10] = n & 0xFF;
        for (int i = 0; i < n; i++) head[11 + i] = valueBytes[i] & 0xFF;
        return zydAppendCrc(head);
    }

    /** Global speed-limit register 0x20, value = round(kmh*10) as uint16 BE. App ceiling 60 km/h. */
    static byte[] zydSpeedFrame(double kmh) {
        int v = ((int) Math.round(kmh * 10.0)) & 0xFFFF;
        return zydRwParamFrame(0x20, new byte[]{(byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)});
    }

    // ── ZYD control frame "sendTran" (no CRC): A5 cmd ~cmd 00 00 00 00 5A ──
    // cmd 0x00 enters UF mode (register/config access, display bypassed; "Keep UF Mode").
    // cmd 0xFF leaves UF mode again ("Stop UF Mode"). UF mode must never be the resting state of a
    // connection - see BleManager's handshake and register-write sequence.

    static byte[] zydTranFrame(int cmd) {
        return new byte[]{(byte) 0xA5, (byte) (cmd & 0xFF), (byte) (~cmd & 0xFF), 0, 0, 0, 0, (byte) 0x5A};
    }

    // ── ZYD idle heartbeat "sendKeep" (no CRC): A5 02 FD 5A ("Keep Monitor Mode") ──
    // This is the frame that keeps the display in charge of the throttle - the idle heartbeat is
    // ALWAYS this frame, never zydTranFrame; looping zydTranFrame(0x00) latches UF mode and bypasses
    // the display (the historical tb-unlock v14 bug - see BleManager).

    static byte[] zydKeepFrame() {
        return new byte[]{(byte) 0xA5, 0x02, (byte) 0xFD, (byte) 0x5A};
    }

    // ── AT channel (ZYD only, ASCII on the f2f1 write characteristic) ──

    private static byte[] ascii(String s) {
        try {
            return (s == null ? "" : s).getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            return new byte[0];
        }
    }

    /** AT+PWD[<pin>] - PIN auth on the AT channel. Must be sent and awaited before any other write. */
    static byte[] atPassword(String pin) {
        return ascii("AT+PWD[" + (pin == null ? "" : pin) + "]");
    }

    /** AT+NAME[<=16 chars] - set the BLE advertised name. */
    static byte[] atName(String name) {
        return ascii("AT+NAME[" + (name == null ? "" : name) + "]");
    }

    /** AT+MP3/MP31/MP32/MP34[NN] - sound select. code is "MP3"(start)/"MP31"(shutdown)/"MP32"(horn)/
     *  "MP34"(alarm); type is a 2-digit sound number, zero-padded. */
    static byte[] atSound(String code, int type) {
        String nn = (type < 10 ? "0" : "") + type;
        return ascii("AT+" + code + "[" + nn + "]");
    }

    // ── Legacy family: FF 55 opcode length payload.. checksum ──
    // checksum = additive sum of ALL header+payload bytes (including the FF 55 lead-in and the
    // length byte) & 0xFF, appended as the last byte.

    static byte[] ff55Frame(int opcode, byte[] payload) {
        byte[] p = payload == null ? new byte[0] : payload;
        byte[] head = new byte[4 + p.length];
        head[0] = (byte) 0xFF;
        head[1] = 0x55;
        head[2] = (byte) (opcode & 0xFF);
        head[3] = (byte) (p.length & 0xFF);
        System.arraycopy(p, 0, head, 4, p.length);
        int sum = 0;
        for (byte b : head) sum += (b & 0xFF);
        byte[] out = new byte[head.length + 1];
        System.arraycopy(head, 0, out, 0, head.length);
        out[head.length] = (byte) (sum & 0xFF);
        return out;
    }

    // ── Self-test against the belegt vectors, run once at class load ──

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static boolean eq(byte[] a, String expectedHex) {
        return hex(a).equals(expectedHex);
    }

    static boolean selfTest() {
        boolean ok = true;
        ok &= crc16Modbus("123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 9) == 0x4B37;
        ok &= eq(ff55Frame(0x1F, new byte[]{0x02}), "FF 55 1F 01 02 76");
        ok &= eq(ff55Frame(0x1F, new byte[]{0x03}), "FF 55 1F 01 03 77");
        ok &= eq(ff55Frame(0x17, new byte[]{0x01}), "FF 55 17 01 01 6D");
        ok &= eq(ff55Frame(0x01, new byte[0]), "FF 55 01 00 55");
        ok &= eq(zydTranFrame(0x00), "A5 00 FF 00 00 00 00 5A");
        ok &= eq(zydTranFrame(0xFF), "A5 FF 00 00 00 00 00 5A");
        ok &= eq(zydKeepFrame(), "A5 02 FD 5A");
        byte[] speed20 = zydSpeedFrame(20);
        ok &= eq(speed20, "01 17 00 20 00 01 00 20 00 01 02 00 C8 53 32");
        return ok;
    }

    static {
        boolean ok = selfTest();
        Log.i(TAG, "protocol self-test " + (ok ? "PASSED" : "FAILED"));
    }
}
