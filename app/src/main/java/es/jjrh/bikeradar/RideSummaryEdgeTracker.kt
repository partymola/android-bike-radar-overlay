// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Edge-tracking state machine for the post-ride summary, extracted from
 * `BikeRadarService.maybePostRideSummary` so its off<->on transitions and the
 * dwell/posted gating are unit-testable without the service.
 *
 * The DECISIONS (when a summary is meaningful, when a reconnect starts a new
 * ride) live in [RideSummaryNotificationDecider]; this owns only the state
 * threading and the action sequence around them. It is pure: the caller supplies
 * the radar-off instant, the clock, the thresholds, and a lazy [evaluatePost]
 * that takes the stats snapshot and applies [RideSummaryNotificationDecider.shouldPost]
 * only when invoked. The tracker invokes it solely on the post path (radar off,
 * not yet posted, past the dwell), which preserves the original's "snapshot only
 * once the dwell could pass" thread-safety gate - the accumulator's main-thread
 * writer can still be in its cancellation tail right after a disconnect.
 */
internal object RideSummaryEdgeTracker {

    data class State(
        val lastRadarOffSinceMs: Long? = null,
        val rideSummaryPosted: Boolean = false,
    )

    sealed interface Action {
        /** Radar reconnected after a long off-gap: begin a fresh ride accumulator. */
        object ResetRideStats : Action

        /** Post the summary + append ride history for [snapshot]; [offInstantMs] is
         *  the monotonic ride-end instant (the radar-off moment, not "now"). */
        data class PostSummary(val snapshot: RideStatsSnapshot, val offInstantMs: Long) : Action
    }

    data class Outcome(val state: State, val actions: List<Action>)

    /**
     * Advance the tracker one walk-away tick.
     *
     * @param radarOffSinceMs the monotonic instant the radar went off, or null if
     *   the radar is currently on.
     * @param evaluatePost lazy post check: returns the snapshot to post, or null
     *   to skip. Invoked only on the post path, so it can safely take the stats
     *   snapshot inside.
     */
    fun onTick(
        prev: State,
        radarOffSinceMs: Long?,
        nowMs: Long,
        longOffMs: Long,
        dwellMs: Long,
        evaluatePost: () -> RideStatsSnapshot?,
    ): Outcome {
        if (radarOffSinceMs == null) {
            // Radar is on. If it just came back from an off period, maybe start a
            // new ride and clear the posted flag for the next ride segment.
            val actions = mutableListOf<Action>()
            var posted = prev.rideSummaryPosted
            prev.lastRadarOffSinceMs?.let { wasOffSince ->
                if (RideSummaryNotificationDecider.shouldStartNewRide(nowMs - wasOffSince, longOffMs)) {
                    actions += Action.ResetRideStats
                }
                posted = false
            }
            return Outcome(State(lastRadarOffSinceMs = null, rideSummaryPosted = posted), actions)
        }

        // Radar is off: track the off-instant. Skip the snapshot (and any post)
        // while already posted or still inside the dwell.
        if (prev.rideSummaryPosted || nowMs - radarOffSinceMs < dwellMs) {
            return Outcome(prev.copy(lastRadarOffSinceMs = radarOffSinceMs), emptyList())
        }
        val snapshot = evaluatePost()
        return if (snapshot != null) {
            Outcome(
                State(lastRadarOffSinceMs = radarOffSinceMs, rideSummaryPosted = true),
                listOf(Action.PostSummary(snapshot, radarOffSinceMs)),
            )
        } else {
            Outcome(prev.copy(lastRadarOffSinceMs = radarOffSinceMs), emptyList())
        }
    }
}
