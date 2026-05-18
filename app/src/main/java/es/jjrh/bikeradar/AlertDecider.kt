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
 *    close vehicle satisfies either:
 *      a) **proximity gate** - at near-third proximity (`distanceM <=
 *         alertMaxM/3`) AND closing faster than
 *         [SAFETY_OVERRIDE_CLOSING_MS] (radar quantum-strict);
 *      b) **TTC gate** - TTC = `distanceM / closing` <= [TTC_GATE_SECONDS]
 *         AND closing >= [TTC_GATE_CLOSING_FLOOR_MS] AND `distanceM <=
 *         alertMaxM`. Strictly extends the proximity gate's coverage at
 *         the same closing-speed bar: at 6 m/s closing, TTC <= 2 s maps
 *         to distance <= 12 m, while the proximity gate caught only the
 *         distance <= alertMaxM/3 = 6 m subset. Earlier warning on the
 *         same threats; closing-floor filters slow-queue traffic.
 *    Catches a vehicle that isn't braking for the queue ahead - the
 *    only case where alerting a stopped rider is still useful (rider
 *    has a chance to dismount or move out of the line of impact).
 *    The closing-speed floor on the TTC gate filters slow-queue
 *    traffic merging into a stopped rider, where the driver is
 *    clearly tracking and braking.
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
        /** Closest stable target's `lateralPos` is carried on each
         *  Beep so audio consumers can pan to the threat's side
         *  (experimental flag). `0f` when no directional information
         *  is available; consumers treat that as centred. */
        data class Beep(val count: Int, val lateralPos: Float = 0f) : Event()
        object Clear : Event()
        /** Stationary-suppress override: rider is stopped AND a close
         *  vehicle satisfies either the proximity gate (near-third
         *  distance + closing past [SAFETY_OVERRIDE_CLOSING_MS]) or
         *  the TTC gate (TTC <= [TTC_GATE_SECONDS] + closing >=
         *  [TTC_GATE_CLOSING_FLOOR_MS]). Audible regardless of the
         *  suppress dwell; the audio is intentionally distinct from a
         *  normal Beep so the rider knows this is the impact-warning
         *  case. See the class KDoc for the full gate semantics.
         *  `lateralPos` is the triggering vehicle's lateral position
         *  for directional audio (experimental flag); `0f` when not
         *  available. */
        data class UrgentApproach(val lateralPos: Float = 0f) : Event()
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
        // close set looks imminent, fire UrgentApproach every cooldown.
        //
        // Two disjunct gates (a vehicle satisfying either fires):
        //   1. Proximity gate (radar-quantum strict): near-third
        //      distance AND closing faster than SAFETY_OVERRIDE_CLOSING_MS.
        //   2. TTC gate: time-to-collision below TTC_GATE_SECONDS,
        //      with a closing-speed floor (TTC_GATE_CLOSING_FLOOR_MS)
        //      that filters slow-queue traffic merging into the rider,
        //      and a distance ceiling at alertMaxM so we never reach
        //      out beyond what the alert envelope is configured for.
        //      Strictly extends the proximity gate's coverage at the
        //      same closing-speed bar: at 6 m/s closing, TTC <= 2 s
        //      maps to distance <= 12 m, while the proximity gate
        //      caught only the distance <= alertMaxM/3 = 6 m subset.
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
        // No per-tid latch. Industry standards (TCAS, automotive FCW,
        // IEC 60601-1-8 medical, NFPA 72 smoke T3, ISO 7731 industrial)
        // all repeat-while-held for imminent-danger cues. DO NOT add a
        // per-tid latch.
        // `stableClose` preserves the upstream order from
        // `RadarV2Decoder.snapshot()`, which sorts by `distanceM`
        // ascending. So `firstOrNull` here returns the CLOSEST
        // imminent-impact threat - the right one to pan the urgent
        // cue toward.
        val imminentImpactTrigger = if (!riderBelowStationaryForUrgent) null else stableClose.firstOrNull { v ->
            val byProximity = v.speedMs <= SAFETY_OVERRIDE_CLOSING_MS &&
                v.distanceM <= alertMaxM / 3
            // Closing speed in m/s, positive = approaching.
            val closingMs = -v.speedMs
            val byTtc = closingMs >= TTC_GATE_CLOSING_FLOOR_MS &&
                v.distanceM in 0..alertMaxM &&
                v.distanceM.toFloat() / closingMs <= TTC_GATE_SECONDS
            byProximity || byTtc
        }
        val anyImminentImpact = imminentImpactTrigger != null
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
                        // until threat clears. Carry the triggering
                        // vehicle's lateralPos so audio consumers can pan
                        // to the threat's side when the experimental
                        // directional-audio flag is on.
                        lastBeepAtMs = nowMs
                        beepPending = false
                        Event.UrgentApproach(
                            lateralPos = imminentImpactTrigger?.lateralPos ?: 0f,
                        )
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
                        // closestVehicle's lateralPos feeds directional
                        // audio when the experimental flag is on; defaults
                        // to 0f when no closest is tracked (defensive -
                        // beepPending shouldn't normally reach here in
                        // that state).
                        val v = closestVehicle
                        if (v != null) {
                            firedTierPerTid[v.id] = closestUrgency
                            Event.Beep(count = closestUrgency, lateralPos = v.lateralPos)
                        } else {
                            Event.Beep(count = closestUrgency, lateralPos = 0f)
                        }
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
         *  Decoded `speedMs` is Float at the radar's native 0.5 m/s
         *  quantum, so legal closing values are ..., -6.5, -6.0, -5.5,
         *  -5.0, ... A naive choice of -5f sits on the quantisation
         *  step: a target whose real closing speed sits near 5 m/s
         *  would flap across the threshold from frame to frame as the
         *  raw byte oscillates between -10 and -11. -6f is one quantum
         *  stricter and corresponds to a real closing speed of
         *  >= 6.0 m/s (~22 km/h), which matches "vehicle still going
         *  at urban cruising speed without braking for the queue"
         *  better than -5f (~18 km/h) does. */
        const val SAFETY_OVERRIDE_CLOSING_MS = -6f

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

        /** Time-to-collision threshold (seconds) for the TTC disjunct
         *  of the stationary-impact safety override. Below the 2.8 s
         *  lower bound of automotive forward-collision-warning systems
         *  (NHTSA Burgett & Carter, Mercedes Pre-Safe, Volvo RCW use
         *  2.8-4 s) - they assume a driver in a vehicle with AEB. A
         *  stopped cyclist's reaction options are narrower (dismount,
         *  step aside, brace), and a wider TTC window for normal-
         *  closing-speed traffic merging into the rider's queue
         *  position quickly degenerates into beep noise. 2 s buys
         *  enough warning to react when paired with the 6 m/s closing
         *  floor below: at the boundary, a 12 m / 6 m/s approach
         *  still gives the same warning the proximity gate would
         *  give at 6 m / 6 m/s = 1 s, but earlier in the encounter. */
        const val TTC_GATE_SECONDS = 2.0f

        /** Minimum closing speed (m/s, positive = approaching) for the
         *  TTC disjunct to engage. Mirrors [SAFETY_OVERRIDE_CLOSING_MS]
         *  on the proximity disjunct so both gates of the stationary
         *  override share the same quantum-strict closing bound - the
         *  -5/-6 quantum boundary on the proximity gate exists to
         *  avoid radar-noise flap, and the same reasoning applies
         *  here. Anything below 6 m/s catches too much queueing
         *  traffic merging into a stopped rider, where the driver is
         *  clearly tracking and braking. */
        const val TTC_GATE_CLOSING_FLOOR_MS = 6f
    }
}
