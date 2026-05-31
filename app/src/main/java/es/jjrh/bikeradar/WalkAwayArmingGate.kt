// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure-function gate consulted by [BikeRadarService.markRadarDisconnected]
 * before transitioning to ARMED. The walk-away decider itself stays
 * stateless and unchanged; this gate is the caller-owned arming bit's
 * single point of eBike-aware decision.
 *
 * Semantics: arming is suppressed in exactly ONE case - a FRESH eBike
 * snapshot reporting `system_locked = false` (rider is actively on the
 * bike, so a mid-ride radar BLE blip must not arm). Every other case
 * arms (fail toward protecting the bike):
 *
 *   - FRESH `system_locked = false` → rider on the bike. Suppress.
 *   - `system_locked = true` (locked or asleep) → rider may be
 *     dismounting. Arm; downstream [WalkAwayDecider] timers handle the
 *     dismount-window dwell.
 *   - eBike absent (null snapshot or null `systemLocked`) → no eBike
 *     signal (no Bosch eBike, experimental flag off, or pre-bond). Arm.
 *     Graceful degradation for radar-only / non-eBike riders.
 *   - STALE snapshot ([snapshotAgeMs] ≥ [freshMs]) → the eBike link
 *     dropped; a frozen `false` from before the rider parked must NOT
 *     suppress arming. Arm. This mirrors [RadarDropDecider.ridingConfirmed],
 *     whose KDoc spells out the same fail-closed-on-stale contract; without
 *     it a stale "unlocked" silently suppresses the walk-away
 *     (dashcam-left-on-bike) reminder when the rider actually has parked
 *     and left. (This is the phone-side dashcam reminder, NOT the bike's
 *     own anti-theft alarm, which is a Bosch function independent of this app.)
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
     * suppress arming for this disconnect. Suppress only when the eBike
     * snapshot is FRESH ([snapshotAgeMs] < [freshMs]) and reports
     * `system_locked = false`.
     */
    fun shouldArm(eBikeSnapshot: LiveDataSnapshot?, snapshotAgeMs: Long, freshMs: Long): Boolean {
        val freshlyUnlocked = eBikeSnapshot?.systemLocked == false && snapshotAgeMs < freshMs
        return !freshlyUnlocked
    }
}
