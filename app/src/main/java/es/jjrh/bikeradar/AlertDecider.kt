// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure-JVM alert decision engine. Fed one frame at a time, returns either a
 * `Beep(urgency)` to acknowledge a new threat or a closer-distance escalation,
 * a `Clear` chime when the road empties, or `None`.
 *
 * Beep count maps to the proximity of the **closest** car in the close zone:
 *   - urgency 1  : far third of the alert window (d > 2/3 alertMaxM)
 *   - urgency 2  : middle third
 *   - urgency 3  : near third (d <= 1/3 alertMaxM)
 *
 * Triggers (closest-only audio model: a beep tells the rider about
 * the *closest* threat only; piling on a beep for a track that
 * doesn't change "the closest is at tier N" information is the
 * cacophony pattern):
 *  - **Closest-tier rising edge.** A new track entering the close
 *    set is silent unless its arrival raises the *closest-urgency
 *    tier* above the highest tier we have audibly fired for on the
 *    new closest track.
 *  - **Escalation with per-track tier latch.** When the closest car
 *    crosses into a higher urgency bucket (further -> closer),
 *    re-beep at the new level — but only if we haven't already
 *    audibly fired for *that tid at that tier*. Intra-tier distance
 *    jitter (e.g. 11→9→11 m flapping the near-third boundary) does
 *    NOT re-fire.
 *  - **Filtered overtake re-acknowledgement.** When a track that
 *    was close transitions to `isBehind` and others remain close,
 *    re-beep only if the remaining closest-urgency is *strictly
 *    greater* than the peak urgency the just-overtaking track ever
 *    reached. Same-or-lower tier re-statement is silent — the rider
 *    was already alerted at that tier by the now-overtaking track.
 *  - **Sustain debounce.** A track must be present in close for
 *    `sustainFrames` consecutive frames before it counts. Single-
 *    frame radar blips never fire.
 *  - **Beep cooldown.** No two beeps within `minBeepGapMs`. Triggers
 *    in the cooldown window collapse into a single beep at the
 *    closest urgency *at the moment the cooldown expires*.
 *  - **Clear chime.** Plays once when the close zone goes from non-empty
 *    to empty, bypassing the cooldown (different timbre, never
 *    overlaps a beep on the speaker).
 *  - **Stationary suppress.** Once the rider has been at or below
 *    `stationaryMsThreshold` for at least `stationaryDwellMs` of
 *    wall-clock time, Beep events are mapped to None. Clear still
 *    fires. Lets the rider sit at a traffic light without beep/clear
 *    loops from the queue of stopped cars behind them.
 *  - **Stationary safety override.** While stationary-suppressed, an
 *    [Event.UrgentApproach] (distinct audio) fires anyway when any
 *    close vehicle is at near-third proximity AND closing faster than
 *    [SAFETY_OVERRIDE_CLOSING_MS]. Catches a vehicle that isn't
 *    braking for the queue ahead - the only case where alerting a
 *    stopped rider is still useful (rider has a chance to dismount or
 *    move out of the line of impact).
 *
 * Threading: instances are not thread-safe; serialise calls (the radar
 * stream is naturally single-producer).
 */
class AlertDecider(
    private val sustainFrames: Int = 2,
    /** Minimum wall-clock milliseconds between two audible beeps.
     *  The closest-only trigger rule already filters multi-track
     *  noise; this cooldown is for back-to-back triggers on the
     *  closest track itself (e.g. tier raise immediately after a
     *  new-entry fire). */
    private val minBeepGapMs: Long = 700,
    /** Rider's bike speed (m/s) at or below this counts as "stationary".
     *  0.5 m/s catches raw bytes 0..2 inclusive (0, 0.25, 0.5 m/s),
     *  matching the prior 2 km/h gate exactly. */
    private val stationaryMsThreshold: Float = 0.5f,
    /** Wall-clock milliseconds the rider's bike speed must stay at or
     *  below [stationaryMsThreshold] continuously before Beep events
     *  get mapped to None. Long enough to skip rolling stops mid-turn,
     *  short enough to kick in at a normal traffic-light stop. */
    private val stationaryDwellMs: Long = 2_000L,
) {

    sealed class Event {
        data class Beep(val count: Int) : Event()
        object Clear : Event()
        /** Stationary-suppress override: rider is stopped AND a close
         *  vehicle is closing faster than [SAFETY_OVERRIDE_CLOSING_MS]
         *  at near-third proximity. Audible regardless of the suppress
         *  gate; the audio is intentionally distinct from a normal Beep
         *  so the rider knows this is the impact-warning case. */
        object UrgentApproach : Event()
        object None : Event()
    }

    private val consecutiveClose = HashMap<Int, Int>()
    private var prevStableClose: Set<Int> = emptySet()
    private var prevClosestUrgency: Int = 0
    private var lastBeepAtMs: Long = Long.MIN_VALUE / 2  // guarantees first beep fires
    private var beepPending: Boolean = false

    /**
     * Per-track tier latch — tid -> highest urgency tier we have
     * *audibly* fired for during this approach episode.
     *
     * Used by both the new-entry gate and the escalation gate to
     * suppress same-tier re-fires. Once we've played a Beep(N)
     * attributable to tid T, subsequent frames where T is still the
     * closest at tier N (or lower) are silent. A re-fire on T
     * requires either:
     *   - a true tier raise (N -> N+1) for that tid;
     *   - the close set fully empties (Clear), which clears this
     *     map; or
     *   - the tid de-escalates a full tier away from N then comes
     *     back to N (rearm event), via [peakUrgencyPerTid] rearm
     *     logic.
     *
     * Cleared on Clear (when the close set transitions from non-
     * empty to empty).
     */
    private val firedTierPerTid = HashMap<Int, Int>()

    /**
     * Per-track peak-urgency tracker — tid -> highest urgency tier
     * observed for that tid since it entered the close set.
     *
     * Two roles:
     *   1. Drives the filtered-overtake-reack gate: when a tid flips
     *      isBehind, the remaining closest's urgency must be strictly
     *      greater than `peakUrgencyPerTid[overtakenTid]` to fire.
     *   2. Drives the rearm leg of per-track hysteresis: if a tid
     *      drops a full tier below its `firedTierPerTid` entry, the
     *      latch is cleared so a subsequent re-escalation can fire
     *      again.
     *
     * Cleared on Clear, same as [firedTierPerTid].
     */
    private val peakUrgencyPerTid = HashMap<Int, Int>()

    /** Wall-clock ms of the most recent `decide()` call in which the rider
     *  was NOT at or below [stationaryMsThreshold]. Compared against
     *  `nowMs` each call to decide whether the stationary dwell has been
     *  satisfied. [NOT_INITIALIZED] until the first call of this session. */
    private var lastNotStationaryAtMs: Long = NOT_INITIALIZED

    fun decide(
        vehicles: List<Vehicle>,
        alertMaxM: Int,
        nowMs: Long,
        bikeSpeedMs: Float? = null,
    ): Event {
        // Rider-stationary gate. Track when the rider was last observed NOT
        // stationary; once that was more than stationaryDwellMs ago, Beep
        // events get mapped to None (Clear still fires). On the very first
        // call we initialise lastNotStationaryAtMs to nowMs so the dwell is
        // measured from now, not from 1970.
        val isBelowThreshold =
            bikeSpeedMs != null && bikeSpeedMs <= stationaryMsThreshold
        if (lastNotStationaryAtMs == NOT_INITIALIZED || !isBelowThreshold) {
            lastNotStationaryAtMs = nowMs
        }
        val timeBelowStationaryMs = nowMs - lastNotStationaryAtMs
        val riderStationary = timeBelowStationaryMs >= stationaryDwellMs
        // Short mini-dwell for the imminent-impact override (below).
        // The 2 s `stationaryDwellMs` exists to skip rolling stops
        // mid-turn for the ordinary-Beep suppress path; the override
        // is much narrower (closing ≥ 6 m/s AND distance ≤ alertMaxM/3)
        // and doesn't need the same protection. 500 ms is long enough
        // to absorb single-frame radar speed noise and 200-400 ms
        // mid-turn speed dips, short enough that a rider decelerating
        // into a junction with a closing vehicle gets the urgent tone
        // well within the time-to-collision window.
        val riderBelowStationaryForUrgent =
            timeBelowStationaryMs >= URGENT_OVERRIDE_DWELL_MS

        // Skip alongside-stationary tracks (parked car / queued traffic next
        // to a crawling rider). The decoder gates these on rider speed +
        // dwell time + zero closing speed, so they are by construction
        // not threats - beeping for them would be the audio equivalent of
        // the chevron-overlap problem the visual dock was added to fix.
        val close = vehicles.filter {
            !it.isBehind && !it.isAlongsideStationary && it.distanceM in 0..alertMaxM
        }
        val behindTids = vehicles.filter { it.isBehind }.mapTo(HashSet()) { it.id }
        val currentCloseTids = close.mapTo(HashSet()) { it.id }

        // Update consecutive-frame counters. A tid drops back to zero the
        // moment it leaves the close set, so flicker can't accumulate.
        val updated = HashMap<Int, Int>(currentCloseTids.size)
        for (tid in currentCloseTids) {
            updated[tid] = (consecutiveClose[tid] ?: 0) + 1
        }
        consecutiveClose.clear()
        consecutiveClose.putAll(updated)

        val stableClose = close.filter { (consecutiveClose[it.id] ?: 0) >= sustainFrames }
        val stableTids = stableClose.mapTo(HashSet()) { it.id }
        val closestVehicle = stableClose.minByOrNull { it.distanceM }
        val closestUrgency = closestVehicle
            ?.let { urgencyFor(it.distanceM, alertMaxM) }
            ?: 0

        // Update peak urgency per tid for every stable-close track. Used by
        // both the D2b filtered overtake re-ack gate and the per-track
        // hysteresis re-arm path. Must be updated BEFORE the trigger gate
        // since D2b reads `peakUrgencyPerTid[overtakenTid]`.
        for (v in stableClose) {
            val u = urgencyFor(v.distanceM, alertMaxM)
            val prevPeak = peakUrgencyPerTid[v.id] ?: 0
            if (u > prevPeak) peakUrgencyPerTid[v.id] = u
        }

        val newEntries = stableTids - prevStableClose
        val overtakes  = prevStableClose intersect behindTids

        // Trigger gate. Audio describes the closest threat only;
        // additional tracks at the same or lower tier are silent.
        //
        //   Closest-tier rising edge: a new track entering the close
        //     set fires only if its arrival raises the closest-urgency
        //     tier above what we have already audibly fired for on
        //     the current closest tid.
        //
        //   Filtered overtake re-ack: when a track flips isBehind and
        //     others remain close, fire only if the remaining
        //     closest-urgency is strictly greater than the peak
        //     urgency the just-overtaking track ever reached.
        //
        //   Per-track tier latch: once we have audibly fired at
        //     urgency N for tid T, no re-fire for the same tid at the
        //     same tier. Re-fire requires a true tier raise N->N+1, a
        //     Clear (which resets all latches), or a full-tier
        //     de-escalation followed by re-escalation (handled via
        //     the peakUrgencyPerTid rearm path).
        val newEntryRaisesTier =
            newEntries.isNotEmpty() && closestUrgency > prevClosestUrgency
        val overtakeToHigher = if (overtakes.isNotEmpty() && stableTids.isNotEmpty()) {
            val peakOvertaken = overtakes.maxOf { peakUrgencyPerTid[it] ?: 0 }
            closestUrgency > peakOvertaken
        } else {
            false
        }
        // Escalation only counts if it's a true tier raise on the closest
        // tid that we haven't already fired for at that tier.
        val escalation = closestVehicle != null &&
            closestUrgency > prevClosestUrgency &&
            closestUrgency > (firedTierPerTid[closestVehicle.id] ?: 0)
        // Stationary-impact safety override. While the rider is at or
        // below the stationary speed threshold and ANY vehicle in the
        // close set is at near-third proximity closing fast, fire
        // UrgentApproach every cooldown.
        //
        // Bypasses the stationary-suppress dwell. The dwell exists to
        // skip rolling stops mid-turn; it is a 2 s timer used as a
        // proxy for "rider has committed to a stop". When an imminent
        // threat is present the dwell is the wrong gate: TTC is sub-
        // 2 s, and waiting it out leaves the urgent tone silent
        // during the entire reaction window (a rider decelerating
        // into a junction with a closing vehicle is not "rolling stop
        // mid-turn" — they ARE the case the override exists for).
        //
        // No per-tid latch. The imminent-impact gate is intentionally
        // tight (closing ≥ 6 m/s AND distance ≤ alertMaxM/3 AND rider
        // at or below stationary speed). Industry standards (TCAS,
        // automotive FCW, IEC 60601-1-8 medical, NFPA 72 smoke T3,
        // ISO 7731 industrial) all repeat-while-held for imminent-
        // danger cues. DO NOT add a per-tid latch.
        val anyImminentImpact = riderBelowStationaryForUrgent && stableClose.any { v ->
            v.speedMs <= SAFETY_OVERRIDE_CLOSING_MS &&
                v.distanceM <= alertMaxM / 3
        }
        val triggered = newEntryRaisesTier || overtakeToHigher || escalation || anyImminentImpact
        if (triggered) beepPending = true

        val cooldownDone = nowMs - lastBeepAtMs >= minBeepGapMs

        val event: Event = when {
            stableTids.isEmpty() && prevStableClose.isNotEmpty() -> {
                lastBeepAtMs = nowMs
                beepPending = false
                firedTierPerTid.clear()
                peakUrgencyPerTid.clear()
                Event.Clear
            }
            beepPending && cooldownDone && stableTids.isNotEmpty() -> {
                when {
                    anyImminentImpact -> {
                        // Held imminent threat: fire urgent every cooldown
                        // until threat clears.
                        lastBeepAtMs = nowMs
                        beepPending = false
                        Event.UrgentApproach
                    }
                    riderStationary -> {
                        // Stationary, no imminent threat — suppress
                        // ordinary beeps. Don't consume cooldown or
                        // beepPending: when the rider rolls off, the
                        // next decide() call can fire same-frame.
                        Event.None
                    }
                    else -> {
                        lastBeepAtMs = nowMs
                        beepPending = false
                        if (closestVehicle != null) {
                            firedTierPerTid[closestVehicle.id] = closestUrgency
                        }
                        Event.Beep(closestUrgency)
                    }
                }
            }
            else -> Event.None
        }

        prevStableClose = stableTids
        prevClosestUrgency = if (stableTids.isEmpty()) 0 else closestUrgency
        return event
    }

    fun reset() {
        consecutiveClose.clear()
        firedTierPerTid.clear()
        peakUrgencyPerTid.clear()
        prevStableClose = emptySet()
        prevClosestUrgency = 0
        lastBeepAtMs = Long.MIN_VALUE / 2
        beepPending = false
        lastNotStationaryAtMs = NOT_INITIALIZED
    }

    private fun urgencyFor(distM: Int, alertMaxM: Int): Int {
        val third = alertMaxM / 3f
        return when {
            distM <= third       -> 3
            distM <= 2f * third  -> 2
            else                 -> 1
        }
    }

    companion object {
        /** Sentinel for [lastNotStationaryAtMs] meaning "no `decide()`
         *  call has yet been processed this session". The first call
         *  replaces it with `nowMs` so dwell starts counting from there.
         *  Cannot reuse the `Long.MIN_VALUE / 2` idiom that [lastBeepAtMs]
         *  uses: `nowMs - Long.MIN_VALUE / 2` overflows positive on the
         *  first call and would satisfy `>= stationaryDwellMs`
         *  immediately, silencing the first beep. */
        private const val NOT_INITIALIZED: Long = Long.MIN_VALUE

        /** Closing speed (m/s, signed; negative = approaching) at or
         *  below which a stationary rider's suppress gate is overridden,
         *  when paired with near-third proximity.
         *
         *  Decoded `speedMs` is integer m/s (RadarV2Decoder rounds the
         *  raw 0.5 m/s LSBs to Int), so legal closing values are
         *  -7, -6, -5, ... A naive choice of -5 lands the threshold
         *  on the radar's quantisation noise: a target whose real
         *  closing speed sits near 5 m/s would oscillate between
         *  rounded -5 (fires) and rounded -4 (does not), causing the
         *  override to flap frame-to-frame. -6 is one quantum stricter
         *  and corresponds to a real closing speed of >=5.5 m/s
         *  (~20 km/h), which matches "vehicle still going at urban
         *  cruising speed without braking for the queue" better than
         *  -5 (~18 km/h) does. */
        const val SAFETY_OVERRIDE_CLOSING_MS = -6

        /** Mini-dwell for the imminent-impact override path. Much
         *  shorter than [stationaryDwellMs] (which exists to skip
         *  rolling stops mid-turn for the ordinary-Beep suppress
         *  path). 500 ms is long enough to absorb single-frame
         *  radar bike-speed noise and 200-400 ms mid-turn speed
         *  dips, short enough that a rider decelerating into a
         *  junction with a closing vehicle gets the urgent tone
         *  well within the time-to-collision window for the gate
         *  (closing ≥ 6 m/s AND distance ≤ alertMaxM/3). */
        const val URGENT_OVERRIDE_DWELL_MS = 500L
    }
}
