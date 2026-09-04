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
 *  1. **eBike path**: a FRESH eBike snapshot reporting `system_locked == false`
 *     AND the bike's own speed showing a recent sustained ride (see
 *     [RidingSpeedGate]). Unlock alone only means the bike is AWAKE - it goes
 *     unlocked the instant it is switched on, in a garage or a bike store, with
 *     the radar still in a pannier - so speed is what actually says the rider is
 *     on the bike. The rider also handles a powered-on bike well before setting
 *     off (wheeling it out of a rack, fitting kit), which is why the speed gate
 *     needs a sustained spell rather than any movement at all.
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
 *    park-then-fiddle spell; on the corpus it false-fires on 4 of 76 genuine
 *    ride-ends, where 45 s hits 11 (including a real 42 s
 *    fiddle-then-power-off).
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
 *    60 s cue threshold - measured at ~1.2% of riding time on the corpus,
 *    beyond any normal traffic light. The two capture splits this window does
 *    not catch are both of exactly that shape: the rider had been stationary
 *    for 158 s and 119 s at the drop, with a peak of 0.0 and 2.0 m/s over the
 *    preceding 90 s. Every split where the rider was actually moving is
 *    caught. Read a "mid-ride drop" count against that: the corpus holds 6
 *    splits, 4 of them with a moving rider.
 *
 * WALK-AWAY EXCLUSIVITY (deliberately weakened for the radar-activity and
 * track-presence paths): the eBike path is mutually exclusive with the
 * walk-away alarm by construction (the alarm arms only when NOT
 * fresh-unlocked). The other two are NOT: a dashcam-owning, no-eBike rider
 * gets riding-confirmed here while `WalkAwayArmingGate` still arms, so both can
 * fire in one off-episode. Left as-is on purpose: suppressing arming on the
 * latch would remove leave-behind protection for a rider who parks within 30 s
 * of stopping, since their latch is still fresh at the disconnect.
 *
 * Do not read the co-fire as rare. It needs the dashcam to stay FRESH, which is
 * the ordinary state at a ride end before the camera is switched off - a stale
 * dashcam is what PREVENTS the alarm (`WalkAwayDecider` returns NONE, then
 * AUTO_DISMISS, on `nowMs - dashcamLastAdvertMs > dashcamFreshMs`). The
 * track-presence path widens it further: "moving at the drop" becomes "traffic
 * seen within 30 s of the drop", so a range-only rider with a dashcam who parks
 * kerbside on a busy road can take both. That is the case the Experimental
 * toggle exists to switch off.
 *
 * Deliberately NOT part of the activity signal on a radar that reports rider
 * speed: the mere PRESENCE of tracked vehicles. A vehicle can be tracked while
 * the rider is standing still (queued traffic in the next lane, cross traffic
 * at a light, a car passing a just-dismounted rider), so counting it as
 * "riding" would re-introduce the exact ride-end false-fire the gate exists to
 * prevent. The rider's own speed is the signal that cleanly falls to ~0 at a
 * dismount.
 *
 * TRACK-PRESENCE FALLBACK (the one exception - see [isTrackActivity]). A
 * range-only radar has no device-status frame, so `bikeSpeedMs` is null for the
 * entire ride and NEITHER confirmation path above can ever open for a rider
 * with no eBike. For them the cue is not conservative, it is unreachable. Track
 * presence is the only riding signal that stream still carries, so it stands in
 * - but ONLY where the source cannot report rider speed AND no eBike has been
 * seen this session AND the rider has left the toggle on. A radar that reports
 * speed keeps the measured speed gate untouched.
 *
 * What that substitution costs, measured on the ride-capture corpus (195
 * captures, 82 rides, 76 genuine ride-ends) by replaying track presence in
 * place of rider speed at the same instants: at a 30 s window it opens on 6 of
 * 76 ride-ends where the speed gate opens on 4, catches the same 4 moving
 * capture splits, and is closed for 39% of riding time because the road behind
 * is empty. So this is a cue that reaches part of a ride rather than one that
 * never fires, and it is NOT as good as the speed gate.
 *
 * Two limits on that measurement, both of which make it weaker than it looks.
 * The corpus is one rider's commute, so coverage tracks traffic density along
 * the route and the ride-end figure tracks how busy that rider's parking spot
 * is; a rider who parks kerbside on a main road will see more false fires. And
 * the corpus is V2 captures throughout - track presence was replayed from
 * their target frames - so the substitute has never been measured on the
 * stream it actually applies to, and no V1 hardware exists here to measure it
 * on. The per-episode cap ([MAX_LATCH_ONLY_CUES]) bounds the cost at three
 * cues, and the toggle is the way out. [RadarDropDeciderTest] pins the gating;
 * do not widen it to a source that has rider speed.
 *
 * Closure rate was evaluated as a way to sharpen it and does not work. It is
 * `vehicle speed - rider speed`, so a stopped rider should see traffic close
 * faster; measured over 3,401 moving and 108 stopped windows the distributions
 * overlap throughout (a threshold keeping 83.5% of moving windows keeps 50% of
 * stopped ones), because a stopped rider is usually stopped in traffic that is
 * stopped too. Do not re-derive it.
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
     *  - **eBike path**: a FRESH snapshot reporting `system_locked == false`
     *    AND [eBikeRidingFresh] - the bike's own speed says it has recently been
     *    RIDDEN (see [RidingSpeedGate]). The speed term is load-bearing: an eBike
     *    reports itself unlocked the moment it is switched on, so on the lock bit
     *    alone a rider powering up in a garage with the radar still in a pannier
     *    was indistinguishable from a rider mid-ride whose radar had just died -
     *    and the cue fired indoors, on cadence, on every power-on. Every way of
     *    having no live "unlocked" signal FAILS CLOSED:
     *     - [systemLocked] null (no eBike field) -> false,
     *     - caller passes null systemLocked for a null snapshot (no eBike) -> false,
     *     - `system_locked == true` (locked / dismounting) -> false,
     *     - [snapshotAgeMs] >= [freshMs] (eBike link dropped, e.g. rider left) -> false,
     *     - bike awake but not recently ridden (garage, rack, bike store) -> false.
     *  - **Radar-activity path**: [radarActivityFreshAtDrop], the caller's
     *    boolean latched at the disconnect instant from [activityFreshAtDrop],
     *    OR - on a stream with no rider speed - from
     *    [trackActivityFreshAtDrop]. Defaults to false so the eBike-only call
     *    sites and their existing tests are unaffected.
     *
     * PARKED VETO (overrides BOTH paths), from either of two sources that mean
     * the same thing. A last-known `system_locked == true` is the bike saying
     * the rider parked; [riderEndedRide] is the rider saying it on the main
     * screen, which is the only way a rider without an eBike can. Both must
     * veto HERE and not only at the banner: the cue's repeat cap is reset for a
     * parked ride, so a veto that reaches the reset without reaching this
     * predicate uncaps the cue instead of silencing it, and it fires every
     * tick. `endRideSuppressesTheDropCue` pins that.
     *
     * So no drop cue even when the radar-activity latch is true. Without the
     * veto, a rider who locks up within the latch's freshness window of still
     * moving keeps "riding confirmed" for the whole off-episode and the cue
     * repeats against a parked, locked bike (observed in the field).
     *
     * The lock source is sticky regardless of snapshot freshness,
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
        eBikeRidingFresh: Boolean,
        radarActivityFreshAtDrop: Boolean = false,
        riderEndedRide: Boolean = false,
    ): Boolean {
        if (riderEndedRide || systemLocked == true) return false
        val eBikePath = systemLocked == false && snapshotAgeMs < freshMs && eBikeRidingFresh
        return eBikePath || radarActivityFreshAtDrop
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
     * Whether a single decoded frame counts as riding activity for the
     * TRACK-PRESENCE FALLBACK: a real radar reported a vehicle, on a stream
     * that cannot report the rider's own speed. See the class KDoc for what
     * this substitution costs and why it is confined to that stream.
     *
     * Asked of the SOURCE's capability rather than of a null speed value, so a
     * V2 radar that has not yet sent its first device-status frame cannot fall
     * down this path.
     *
     * [DataSource.NONE] is excluded as depth rather than as the live guard:
     * it is the bus's cleared/default state, which always carries an empty
     * vehicle list, so `vehiclesPresent` already rejects every state that can
     * hold it today. It earns its line against a source that does not exist
     * yet - a legacy-capture replay would publish [DataSource.V1] WITH
     * vehicles, and would then stamp a latch a later live drop reads. Do not
     * read this as demo protection: both scripted paths publish
     * [DataSource.V2] today, and [RadarState.scenarioTimeMs] is the field that
     * actually means "scripted, not a live link".
     */
    fun isTrackActivity(source: DataSource, vehiclesPresent: Boolean): Boolean = vehiclesPresent && source != DataSource.NONE && !source.hasRiderSpeed

    /**
     * The track-presence fallback's answer at the drop, latched by the caller
     * exactly like [activityFreshAtDrop] and for the same reason: the radar
     * stops streaming the instant it drops.
     *
     * [hasEBikeSignal] fails closed and withdraws the fallback from anyone
     * whose eBike already answers the riding question - that cohort keeps the
     * eBike path and its locked veto, which this proxy has no equivalent of.
     * It is sticky for the session, so an eBike whose Flow link dies mid-ride
     * withholds the fallback for the rest of that ride and leaves the rider
     * with neither path. Known and deliberate pending evidence that the cohort
     * (an eBike AND a range-only radar) exists at all; do not read the sentence
     * above as covering it.
     *
     * The rider's Experimental toggle is deliberately NOT a parameter here.
     * The cue applies it per tick in `RadarLinkCoordinator.evaluateRadarDrop`,
     * so switching it off silences the rest of an off-episode rather than only
     * the next one, and the ride wakelock does not apply it at all because it
     * protects the walk-away and ride-summary timers too. A parameter would
     * have to be passed one way by one caller and the other way by the other,
     * which is a policy the callers own rather than one this function can hold.
     */
    fun trackActivityFreshAtDrop(
        dropInstantMs: Long,
        lastTrackMs: Long?,
        windowMs: Long,
        hasEBikeSignal: Boolean,
    ): Boolean = !hasEBikeSignal &&
        activityFreshAtDrop(dropInstantMs, lastTrackMs, windowMs)

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
