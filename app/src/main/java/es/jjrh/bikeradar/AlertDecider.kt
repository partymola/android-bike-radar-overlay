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
 * Triggers:
 *  - **Per-track rising edge.** A previously-unseen track id entering the
 *    close zone fires a beep at the current closest-car urgency, even if
 *    other tracks were already close.
 *  - **Escalation.** When the closest car crosses into a higher urgency
 *    bucket (further -> closer), re-beep at the new level.
 *  - **Overtake re-acknowledgement.** When a track that was close
 *    transitions to `isBehind` (the radar's post-detection state), and
 *    other tracks are still close, re-beep at the new closest urgency so
 *    the rider knows what's still tailing after a pass.
 *  - **Sustain debounce.** A track must be present in close for
 *    `sustainFrames` consecutive frames before it counts. Single-frame
 *    radar blips never fire.
 *  - **Beep cooldown.** No two beeps within `minBeepGapMs`. Triggers in the
 *    cooldown window collapse into a single beep at the closest urgency
 *    *at the moment the cooldown expires* — so a junction with three cars
 *    arriving in two seconds produces one clean beep, not a cacophony.
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
        val riderStationary =
            (nowMs - lastNotStationaryAtMs) >= stationaryDwellMs

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
        val closestUrgency = stableClose
            .minOfOrNull { it.distanceM }
            ?.let { urgencyFor(it, alertMaxM) }
            ?: 0

        val newEntries = stableTids - prevStableClose
        val overtakes  = prevStableClose intersect behindTids

        val triggered = newEntries.isNotEmpty()
            || (overtakes.isNotEmpty() && stableTids.isNotEmpty())
            || (closestUrgency > prevClosestUrgency)
        if (triggered) beepPending = true

        val cooldownDone = nowMs - lastBeepAtMs >= minBeepGapMs

        val event: Event = when {
            stableTids.isEmpty() && prevStableClose.isNotEmpty() -> {
                lastBeepAtMs = nowMs
                beepPending = false
                Event.Clear
            }
            beepPending && cooldownDone && stableTids.isNotEmpty() -> {
                val anyImminentImpact = stableClose.any { v ->
                    v.speedMs <= SAFETY_OVERRIDE_CLOSING_MS &&
                        v.distanceM <= alertMaxM / 3
                }
                when {
                    riderStationary && !anyImminentImpact -> {
                        // Inaudible - don't consume the audio-spacing
                        // cooldown or clear beepPending. When the rider
                        // rolls off, the next decide() call can fire
                        // same-frame.
                        Event.None
                    }
                    riderStationary && anyImminentImpact -> {
                        // Override the suppress: the rider is stopped AND
                        // a vehicle is closing fast at near-third
                        // proximity. UrgentApproach (distinct audio) fires
                        // regardless of the suppress gate.
                        lastBeepAtMs = nowMs
                        beepPending = false
                        Event.UrgentApproach
                    }
                    else -> {
                        lastBeepAtMs = nowMs
                        beepPending = false
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
    }
}
