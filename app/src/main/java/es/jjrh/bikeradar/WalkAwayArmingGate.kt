// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure-function gate consulted by [BikeRadarService.markRadarDisconnected]
 * before transitioning to ARMED. The walk-away decider itself stays
 * stateless and unchanged; this gate is the caller-owned arming bit's
 * single point of LDI-aware decision.
 *
 * Semantics:
 *
 *   - LDI reports `system_locked = false` → rider is on the bike (the
 *     bike's anti-theft lock is off whenever the rider is actively
 *     riding). A radar BLE blip mid-ride must NOT arm the walk-away
 *     alarm. Suppress.
 *   - LDI reports `system_locked = true` → bike is locked, rider may
 *     be dismounting. Use existing arm-on-disconnect path (downstream
 *     [WalkAwayDecider] timers handle the dismount-window dwell).
 *   - LDI absent (null snapshot or null `systemLocked`) → no LDI
 *     signal available (no Bosch eBike, experimental flag off, or
 *     pre-bond). Fall back to the existing always-arm-on-disconnect
 *     behaviour. Graceful degradation for radar-only / non-LDI riders.
 *
 * The "arm immediately on lock" variant is not used: riders commonly
 * lock the bike before removing the front camera and pannier and the
 * dwell IS the dismount window for that sequence. Locked-state is a
 * weak intent signal, not a "rider has left" signal.
 */
object WalkAwayArmingGate {

    /**
     * Returns true when [BikeRadarService.markRadarDisconnected] should
     * proceed with the existing IDLE → ARMED transition; false to
     * suppress arming for this disconnect.
     */
    fun shouldArm(ldiSnapshot: LiveDataSnapshot?): Boolean = ldiSnapshot?.systemLocked != false
}
