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
 *    `stationaryKmhThreshold` for at least `stationaryDwellMs` of
 *    wall-clock time, Beep events are mapped to None. Clear still
 *    fires. Lets the rider sit at a traffic light without beep/clear
 *    loops from the queue of stopped cars behind them.
 *
 * Threading: instances are not thread-safe; serialise calls (the radar
 * stream is naturally single-producer).
 */
class AlertDecider(
    private val sustainFrames: Int = 2,
    private val minBeepGapMs: Long = 700,
    /** Rider's bike speed (km/h) at or below this counts as "stationary".
     *  Set to 2 to stay clear of the device-status byte's stationary
     *  floor (raw 2 decodes to ~2 km/h, the radar's own doppler noise
     *  floor above true zero). */
    private val stationaryKmhThreshold: Int = 2,
    /** Wall-clock milliseconds the rider's bike speed must stay at or
     *  below [stationaryKmhThreshold] continuously before Beep events
     *  get mapped to None. Long enough to skip rolling stops mid-turn,
     *  short enough to kick in at a normal traffic-light stop. */
    private val stationaryDwellMs: Long = 2_000L,
) {

    sealed class Event {
        data class Beep(val count: Int) : Event()
        object Clear : Event()
        object None : Event()
    }

    private val consecutiveClose = HashMap<Int, Int>()
    private var prevStableClose: Set<Int> = emptySet()
    private var prevClosestUrgency: Int = 0
    private var lastBeepAtMs: Long = Long.MIN_VALUE / 2  // guarantees first beep fires
    private var beepPending: Boolean = false
    /** Wall-clock ms of the most recent `decide()` call in which the rider
     *  was NOT at or below [stationaryKmhThreshold]. Compared against
     *  `nowMs` each call to decide whether the stationary dwell has been
     *  satisfied. [NOT_INITIALIZED] until the first call of this session. */
    private var lastNotStationaryAtMs: Long = NOT_INITIALIZED

    fun decide(
        vehicles: List<Vehicle>,
        alertMaxM: Int,
        nowMs: Long,
        bikeSpeedKmh: Int? = null,
    ): Event {
        // Rider-stationary gate. Track when the rider was last observed NOT
        // stationary; once that was more than stationaryDwellMs ago, Beep
        // events get mapped to None (Clear still fires). On the very first
        // call we initialise lastNotStationaryAtMs to nowMs so the dwell is
        // measured from now, not from 1970.
        val isBelowThreshold =
            bikeSpeedKmh != null && bikeSpeedKmh <= stationaryKmhThreshold
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
                if (riderStationary) {
                    // Inaudible - don't consume the audio-spacing cooldown
                    // or clear beepPending. When the rider rolls off, the
                    // next decide() call can fire same-frame.
                    Event.None
                } else {
                    lastBeepAtMs = nowMs
                    beepPending = false
                    Event.Beep(closestUrgency)
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
    }
}
