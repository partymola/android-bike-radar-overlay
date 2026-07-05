// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Decides when to fire the radar-drop audible cue. Pure function; the caller
 * (the BikeRadarService walk-away tick) owns [Decision.lastCueMs] and threads
 * it back on the next tick.
 *
 * Why it exists: when the rear-radar BLE link drops mid-ride the overlay
 * freezes on its last frame, so a dead radar looks identical to a clear road.
 * The rider's eyes are on the road, so the warning has to be audible, not a
 * screen glyph.
 *
 * RIDING-CONFIRMED GATE (load-bearing - do not relax without re-reading the
 * scenario matrix below). The cue fires ONLY when [ridingConfirmed] is true.
 * Two independent signals, OR'd, can confirm the rider is mid-ride, so the cue
 * reaches both cohorts:
 *
 *  1. **eBike path** (unchanged): a FRESH eBike snapshot reporting
 *     `system_locked == false` - the Bosch eBike is actively telling us the
 *     rider is on the bike right now.
 *  2. **Radar-activity path** (for the radar-only / no-eBike rider, the
 *     F-Droid audience): the radar was streaming real riding activity - the
 *     rider's own bike speed above walking pace - within a short window BEFORE
 *     the drop. Because the radar stops streaming the moment it drops, this is
 *     sampled and LATCHED at the disconnect instant by the caller (it cannot be
 *     re-read per tick like the live eBike snapshot), then passed in as
 *     [ridingConfirmed]'s `radarActivityFreshAtDrop` argument. See
 *     [activityFreshAtDrop] / [isRidingActivity].
 *
 * The gate exists because of a collision with the walk-away "you left the
 * dashcam on the bike" alarm: both features trigger on "radar went off". The
 * disambiguator is "is the rider still riding or has the ride ended?". The
 * dashcam being alive does NOT answer it (the camera records both mid-ride and
 * just after stopping); a fresh eBike `system_locked == false` does, and so
 * does the rider's own recent speed.
 *
 * SCENARIO MATRIX (radar-activity path; N = the speed-freshness window the
 * caller passes, currently `RADAR_DROP_ACTIVITY_FRESH_MS` = 30 s):
 *  - **Mid-ride drop** (radar dies while the rider is moving): the last
 *    above-walking-pace frame is seconds before the drop, so the latched
 *    freshness is well inside N -> cue fires at the threshold. This is the
 *    whole point of the feature; on the ride-capture corpus, N = 30 s catches
 *    every genuine moving drop.
 *  - **Dismount / deliberate power-off** (rider rolls to a stop, fiddles for
 *    30-60 s, then powers the radar off): the rider's speed has been ~0 since
 *    they stopped, so by the time the radar is switched off the last moving
 *    frame is older than N -> latched freshness is false -> SILENCE. This is
 *    the hard constraint: a cue at every deliberate power-off would train the
 *    rider to ignore it. N = 30 s is the largest window inside that typical
 *    park-then-fiddle spell; on the corpus it false-fires on 1 of 29 genuine
 *    ride-ends, where 45 s hit 2 (including a real 42 s fiddle-then-power-off).
 *    The residual quick-dismount beep is tolerable, because a MISSED cue
 *    leaves the rider blind, which is the worse error. Residual means A FEW
 *    CUES, not an endless loop: the latch can never be un-confirmed once
 *    sampled, so without a bound a quick park repeats the cue at the cadence
 *    until the radar reconnects - which after a park is never. Two guards
 *    bound it: an eBike last-known-locked vetoes the cue outright (see
 *    [ridingConfirmed]), and the latch-only path stops after
 *    [MAX_LATCH_ONLY_CUES] cues per off-episode (see [decide]).
 *  - **Long red light** (rider stopped 1-2 min, radar dies while stopped): if
 *    the radar dies within N of the rider stopping, the cue fires (generous,
 *    per the error-cost asymmetry); if the stop already exceeds N, it is
 *    missed. N trades red-light coverage against dismount silence - the two are
 *    genuinely ambiguous on the rider's own speed alone (both are "stopped,
 *    was moving a while ago"), so no window separates them cleanly. At 30 s,
 *    only stopped spells longer than 2 minutes end up uncovered against the
 *    60 s cue threshold - measured at ~1.3% of riding time on the corpus,
 *    beyond any normal traffic light.
 *
 * WALK-AWAY EXCLUSIVITY (deliberately weakened for the radar-activity path):
 * the eBike path is mutually exclusive with the walk-away alarm by
 * construction (the alarm arms only when NOT fresh-unlocked). The
 * radar-activity path is NOT: a dashcam-owning, no-eBike rider who was moving
 * at the drop gets riding-confirmed here while `WalkAwayArmingGate` still arms,
 * so in principle both can fire in one off-episode. Left as-is on purpose:
 * suppressing arming on the riding-confirmed latch would remove leave-behind
 * protection for a rider who parks within 30 s of stopping (their latch is
 * still fresh at the disconnect), and the co-fire needs a rare double failure -
 * the radar dying while the rider is moving AND the dashcam going stale in the
 * same off-episode.
 *
 * Deliberately NOT part of the activity signal: the mere PRESENCE of tracked
 * vehicles. A vehicle can be tracked while the rider is standing still (queued
 * traffic in the next lane, cross traffic at a light, a car passing a
 * just-dismounted rider), so counting it as "riding" would re-introduce the
 * exact ride-end false-fire the gate exists to prevent. The rider's own speed
 * is the signal that cleanly falls to ~0 at a dismount.
 *
 * BLE disconnect status code (evaluated, deliberately NOT used): the GATT
 * disconnect reason could in principle separate a deliberate power-off
 * (peer-terminated) from a genuine link loss (supervision timeout), which would
 * suppress the dismount false-positive class directly. It is not used because
 * (a) the disconnect arrives via several call sites and only the GATT
 * state-change callback carries a status, so gating on it would couple this
 * safety decision to which path fired; (b) Android GATT status codes are
 * stack- and OEM-dependent; and (c) suppressing on peer-terminate trades a
 * MISSED-cue risk (a genuine drop that happens to report peer-terminate leaves
 * the rider blind) for fewer ride-end beeps, which is the wrong side of the
 * error asymmetry. The speed-freshness window handles the deliberate power-off
 * deterministically instead.
 *
 * Behaviour: once the radar has been continuously down for [thresholdMs]
 * while riding is confirmed, fire, then repeat no more often than
 * [cadenceMs]. The latch resets the moment the radar reconnects (so the next
 * drop fires fresh at the threshold again).
 *
 * RECONNECT CUE (the completing half of the drop design). A drop cue tells the
 * rider the rear channel is dead; after that, silence is ambiguous - it could
 * mean the radar came back (clear road) or that it is still down. So the same
 * reconnect edge that resets the latch also raises [Decision.fireReconnect]
 * IF a drop cue had actually been raised this down-episode (latch non-null).
 * One acknowledgement cue then restores silence's meaning. It never fires on a
 * cold start or a sub-threshold blip, because those never set the latch.
 */
object RadarDropDecider {

    /** Cue count carried across ticks so the latch-only path can be capped;
     *  reset (with the [lastCueMs] latch) when the radar comes back up. */
    data class Decision(
        val fire: Boolean,
        val lastCueMs: Long?,
        val cueCount: Int = 0,
        val fireReconnect: Boolean = false,
    )

    /** Repeat cap when riding is confirmed ONLY by the latched radar-activity
     *  signal (no live eBike confirmation). The latch is sampled once at the
     *  drop and can never be un-confirmed, so a quick park - radar powered off
     *  within the freshness window of the last moving frame - would otherwise
     *  repeat the cue at [decide]'s cadence FOREVER (observed in the field: a
     *  3-beep cue every 3 minutes for the rest of the evening). Three cues
     *  (threshold + two cadences, ~7 minutes) still cover a genuine mid-ride
     *  drop the rider missed once; a live eBike "unlocked" keeps repeating
     *  uncapped because it genuinely re-confirms riding every tick. */
    const val MAX_LATCH_ONLY_CUES = 3

    fun decide(
        radarEverLive: Boolean,
        radarDownForMs: Long?,
        ridingConfirmed: Boolean,
        nowMs: Long,
        thresholdMs: Long,
        cadenceMs: Long,
        lastCueMs: Long?,
        latchOnlyConfirmation: Boolean = false,
        cueCount: Int = 0,
    ): Decision {
        val eligible = radarEverLive &&
            radarDownForMs != null &&
            radarDownForMs >= thresholdMs &&
            ridingConfirmed &&
            !(latchOnlyConfirmation && cueCount >= MAX_LATCH_ONLY_CUES)
        if (!eligible) {
            // Reset the latch only when the radar is back up, so the next
            // drop fires promptly at the threshold. While still down but not
            // yet eligible (under threshold, or riding momentarily
            // unconfirmed), preserve the latch so a brief un-confirm doesn't
            // replay the cue.
            //
            // The reconnect edge rides on this same reset: back up
            // (radarDownForMs == null) AND a drop cue was raised this episode
            // (lastCueMs != null) fires the one-shot reconnect cue. A still-down
            // tick (latch preserved) or a never-cued reconnect (cold start /
            // blip, latch null) stays silent.
            val backUp = radarDownForMs == null
            return Decision(
                fire = false,
                lastCueMs = if (backUp) null else lastCueMs,
                cueCount = if (backUp) 0 else cueCount,
                fireReconnect = backUp && lastCueMs != null,
            )
        }
        val due = lastCueMs == null || nowMs - lastCueMs >= cadenceMs
        return if (due) {
            Decision(fire = true, lastCueMs = nowMs, cueCount = cueCount + 1)
        } else {
            Decision(fire = false, lastCueMs = lastCueMs, cueCount = cueCount)
        }
    }

    /**
     * The riding-confirmed gate the cue depends on: is the rider actively on the
     * bike RIGHT NOW? True when EITHER path confirms it (see the class KDoc):
     *  - **eBike path**: a FRESH snapshot reporting `system_locked == false`.
     *    Every way of having no live "unlocked" signal FAILS CLOSED:
     *     - [systemLocked] null (no eBike field) -> false,
     *     - caller passes null systemLocked for a null snapshot (no eBike) -> false,
     *     - `system_locked == true` (locked / dismounting) -> false,
     *     - [snapshotAgeMs] >= [freshMs] (eBike link dropped, e.g. rider left) -> false.
     *  - **Radar-activity path**: [radarActivityFreshAtDrop], the caller's
     *    boolean latched at the disconnect instant from [activityFreshAtDrop].
     *    Defaults to false so the eBike-only call sites and their existing tests
     *    are unaffected.
     *
     * LOCKED VETO (overrides BOTH paths): a last-known `system_locked == true`
     * means the rider explicitly parked, so no drop cue - even when the
     * radar-activity latch is true. Without it, a rider who locks up within
     * the latch's freshness window of still moving keeps "riding confirmed"
     * for the whole off-episode and the cue repeats against a parked, locked
     * bike (observed in the field). Sticky regardless of snapshot freshness,
     * for the same reason the reconnect banner treats locked as sticky:
     * locking is what makes the bike sleep and drop the eBike link, so the
     * lock reading inevitably ages out - and a riding rider is never
     * last-known-locked (the bike doesn't sleep while moving), so the veto
     * cannot silence a genuine mid-ride drop.
     *
     * Extracted as a pure function (like `WalkAwayArmingGate.shouldArm`) so this
     * safety gate is unit-tested rather than buried inline in the service.
     */
    fun ridingConfirmed(
        systemLocked: Boolean?,
        snapshotAgeMs: Long,
        freshMs: Long,
        radarActivityFreshAtDrop: Boolean = false,
    ): Boolean {
        if (systemLocked == true) return false
        return (systemLocked == false && snapshotAgeMs < freshMs) || radarActivityFreshAtDrop
    }

    /**
     * Whether a single decoded radar frame counts as "riding activity" for the
     * radar-activity confirmation path: the rider's own bike speed is above
     * walking pace. Null speed (no device-status frame yet, or a radar firmware
     * that never reports speed) is NOT riding - the path then simply never
     * confirms and the rider falls back to the eBike gate (or no cue), the same
     * graceful degradation as having no eBike. Vehicle presence is deliberately
     * excluded (see the class KDoc).
     */
    fun isRidingActivity(bikeSpeedMs: Float?, walkingPaceMs: Float): Boolean = bikeSpeedMs != null && bikeSpeedMs > walkingPaceMs

    /**
     * Whether the last riding-activity instant is fresh enough at the drop to
     * confirm a live-ride off-episode. Sampled once, at the disconnect instant,
     * because the radar stops streaming after the drop (unlike the live eBike
     * snapshot, this signal cannot be re-read per tick). Fails closed when there
     * was no riding activity at all this session ([lastActivityMs] null).
     * Boundary: age == [windowMs] is NOT fresh (strict `<`), matching the eBike
     * freshness convention.
     */
    fun activityFreshAtDrop(dropInstantMs: Long, lastActivityMs: Long?, windowMs: Long): Boolean = lastActivityMs != null && dropInstantMs - lastActivityMs < windowMs
}
