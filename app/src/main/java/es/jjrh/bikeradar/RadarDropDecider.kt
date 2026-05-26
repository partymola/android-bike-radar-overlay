// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Decides when to fire the radar-drop audible cue. Pure function; the caller
 * (the BikeRadarService walk-away tick) owns [Decision.lastCueMs] and threads
 * it back on the next tick. Mirrors [CriticalBatteryDecider].
 *
 * Why it exists: when the rear-radar BLE link drops mid-ride the overlay
 * freezes on its last frame, so a dead radar looks identical to a clear road.
 * The rider's eyes are on the road, so the warning has to be audible, not a
 * screen glyph.
 *
 * EBIKE-DATA-ONLY GATE (load-bearing - do not relax without re-reading the collision
 * rationale below). The cue fires ONLY when [ridingConfirmed] is true, which
 * the caller
 * sets from a FRESH eBike snapshot reporting `system_locked == false` (the
 * Bosch eBike is actively telling us the rider is on the bike right now).
 *
 * The reason is a collision with the walk-away "you left the dashcam on the
 * bike" alarm: both features trigger on "radar went off". The disambiguator
 * is "is the rider still riding or has the ride ended?", and the dashcam
 * being alive does NOT answer it (the camera records both mid-ride and just
 * after stopping). Only eBike `system_locked` (or GPS motion, declined for v1)
 * does. Gating on a fresh `system_locked == false` makes this cue mutually
 * exclusive with the walk-away alarm (which arms only when NOT unlocked) and
 * means it can never false-fire at ride-end: a dismount either locks the bike
 * (system_locked -> true) or drops the eBike link (snapshot goes stale), and
 * either closes the gate. Without eBike there is no cue.
 *
 * Behaviour: once the radar has been continuously down for [thresholdMs]
 * while riding is confirmed, fire, then repeat no more often than
 * [cadenceMs]. The latch resets the moment the radar reconnects (so the next
 * drop fires fresh at the threshold again).
 */
object RadarDropDecider {

    data class Decision(val fire: Boolean, val lastCueMs: Long?)

    fun decide(
        radarEverLive: Boolean,
        radarDownForMs: Long?,
        ridingConfirmed: Boolean,
        nowMs: Long,
        thresholdMs: Long,
        cadenceMs: Long,
        lastCueMs: Long?,
    ): Decision {
        val eligible = radarEverLive &&
            radarDownForMs != null &&
            radarDownForMs >= thresholdMs &&
            ridingConfirmed
        if (!eligible) {
            // Reset the latch only when the radar is back up, so the next
            // drop fires promptly at the threshold. While still down but not
            // yet eligible (under threshold, or riding momentarily
            // unconfirmed), preserve the latch so a brief un-confirm doesn't
            // replay the cue.
            return Decision(fire = false, lastCueMs = if (radarDownForMs == null) null else lastCueMs)
        }
        val due = lastCueMs == null || nowMs - lastCueMs >= cadenceMs
        return if (due) {
            Decision(fire = true, lastCueMs = nowMs)
        } else {
            Decision(fire = false, lastCueMs = lastCueMs)
        }
    }

    /**
     * The eBike-data gate the cue depends on: confirm the rider is actively on the
     * bike RIGHT NOW. True only for a FRESH snapshot reporting
     * `system_locked == false`. This is what keeps the cue mutually exclusive
     * with the walk-away alarm and prevents a ride-end false fire, so every
     * way of having no live "unlocked" signal must FAIL CLOSED:
     *  - [systemLocked] null (no eBike field) -> false,
     *  - caller passes null systemLocked for a null snapshot (no eBike) -> false,
     *  - `system_locked == true` (locked / dismounting) -> false,
     *  - [snapshotAgeMs] >= [freshMs] (eBike link dropped, e.g. rider left) -> false.
     * Extracted as a pure function (like `WalkAwayArmingGate.shouldArm`) so this
     * safety gate is unit-tested rather than buried inline in the service.
     */
    fun ridingConfirmed(systemLocked: Boolean?, snapshotAgeMs: Long, freshMs: Long): Boolean = systemLocked == false && snapshotAgeMs < freshMs
}
