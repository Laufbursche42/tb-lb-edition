// tb-lb-edition - an app for Trittbrett e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

/**
 * The maintained "base params" (bp) of a ZYD-family Trittbrett scooter: the small set of fields
 * that all live together in the one 10-byte monitor frame (CommandBuilder.zydMonitorFrame). Because
 * that frame carries gear/lights/cruise/boot/unit/lock plus the cruise cap plus the three gear
 * limits all at once, changing ONE field must resend the others unchanged - this class is that
 * memory, filled from the incoming 0xAB monitor frames (A and B) and read back when building the
 * next outgoing monitor frame. Register writes (speed, throttle curves, ...) and AT commands are
 * one-shot and need no cache; only base params do.
 *
 * Legacy-family scooters (advertised name "Scooter") do not use this class at all - they have no
 * base-params concept, only a bare gear switch and lock/unlock (see CommandBuilder.ff55Frame).
 */
final class SettingsState {

    // Write-side fields (see CommandBuilder.zydStatusByte for the bit packing on the wire).
    volatile int gear = 0;              // 0 = D, 1 = T
    volatile boolean headlight = false;
    volatile boolean ambient = false;
    volatile boolean cruise = false;    // cruise-control enable bit; the app only ever writes it off
    volatile boolean boot = false;      // kickstart / zero-start
    volatile boolean imperial = false;  // false = km/h, true = mph
    volatile boolean lock = false;

    // Read-mostly fields from monitor Frame B; m1/m2/m3 have no known per-gear meaning on any current
    // Trittbrett model (GearType has only two values app-wide) but must be resent unchanged.
    volatile int limitCruise = 3;
    volatile int m1 = 6;
    volatile int m2 = 10;
    volatile int m3 = 20;

    volatile boolean receivedMonitor = false;   // true once the first 0xAB frame arrived

    /** Build the next outgoing monitor frame from the currently maintained fields. */
    synchronized byte[] monitorFrame() {
        int v = CommandBuilder.zydStatusByte(gear, headlight, ambient, cruise, boot, imperial, lock);
        return CommandBuilder.zydMonitorFrame(v, limitCruise, m1, m2, m3);
    }

    /**
     * Update from monitor Frame A (0xAB, sub 0x00). {@code t} is the raw notification bytes.
     * Inbound status-word bit positions (u16BE at t[21]) differ from the outgoing zydStatusByte
     * packing - see CommandBuilder.zydStatusByte's note. Never confuse the two.
     */
    synchronized void updateFromMonitorA(byte[] t) {
        gear = t[4] & 0x03;
        int status = ((t[21] & 0xFF) << 8) | (t[22] & 0xFF);
        headlight = ((status >> 2) & 1) != 0;
        boot = ((status >> 5) & 1) != 0;
        imperial = ((status >> 6) & 1) != 0;
        cruise = ((status >> 9) & 1) != 0;
        lock = ((status >> 11) & 1) != 0;
        ambient = ((status >> 15) & 1) != 0;
        receivedMonitor = true;
    }

    /** Update from monitor Frame B (0xAB, sub 0x01): the cruise cap plus the three gear limits. */
    synchronized void updateFromMonitorB(byte[] t) {
        limitCruise = t[3] & 0xFF;
        m1 = t[4] & 0xFF;
        m2 = t[5] & 0xFF;
        m3 = t[6] & 0xFF;
    }
}
