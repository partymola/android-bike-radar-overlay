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
 *
 * Threading: instances are not thread-safe; serialise calls (the radar
 * stream is naturally single-producer).
 */
class AlertDecider(
    private val sustainFrames: Int = 2,
    private val minBeepGapMs: Long = 700,
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

    fun decide(vehicles: List<Vehicle>, alertMaxM: Int, nowMs: Long): Event {
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
                lastBeepAtMs = nowMs
                beepPending = false
                Event.Beep(closestUrgency)
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
    }

    private fun urgencyFor(distM: Int, alertMaxM: Int): Int {
        val third = alertMaxM / 3f
        return when {
            distM <= third       -> 3
            distM <= 2f * third  -> 2
            else                 -> 1
        }
    }
}
