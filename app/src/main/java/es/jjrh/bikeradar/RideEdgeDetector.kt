// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Detect ride-start and ride-end edges from the Bosch LDI snapshot
 * stream. Pure-function; the caller owns the state and feeds in one
 * snapshot per NOTIFY merge.
 *
 * Why LDI edges instead of GPS-derived ones: GPS-edge ride boundaries
 * drift by hundreds of metres on the office side of a commute (radio
 * shadow indoors), so the ride often appears to "end" 5 km later. LDI
 * gives bike-truth: the rider unlocked the bike means the ride started,
 * locking it means the ride ended. Independent of GPS quality.
 *
 * State machine (rider's POV):
 *
 *   PARKED  --(unlock + wheel turning)-->  RIDING
 *   RIDING  --(lock)-->                    PARKED
 *
 *   PARKED stays through wheel-turning while still locked (the rider
 *   walking the bike, manual move). Only the unlock + wheel-turning
 *   combination starts a ride.
 *
 *   RIDING stays through traffic-light stops (wheel at rest, still
 *   unlocked) until the rider deliberately locks the bike. Mid-ride
 *   stops don't trip an end event.
 *
 * Edges only fire on observed transitions. The first snapshot of the
 * session is captured silently into [State.firstSnapshotSeen] so the
 * very first observation of a locked+stopped bike doesn't synthesise a
 * spurious "started" or "ended".
 */
object RideEdgeDetector {

    enum class Edge { NONE, STARTED, ENDED }

    /**
     * @param isRiding True between observed STARTED and ENDED edges.
     * @param firstSnapshotSeen True once any snapshot with at least one
     *   of `systemLocked` or `bikeNotDriving` set has been observed.
     *   Edges only fire after this flips true.
     */
    data class State(
        val isRiding: Boolean = false,
        val firstSnapshotSeen: Boolean = false,
    )

    /**
     * Apply one snapshot. Returns the next [State] and the [Edge]
     * observed in this transition.
     */
    fun next(prev: State, snapshot: LiveDataSnapshot): Pair<State, Edge> {
        val locked = snapshot.systemLocked
        val notDriving = snapshot.bikeNotDriving

        // Without either signal, we cannot decide anything; carry state.
        if (locked == null && notDriving == null) return prev to Edge.NONE

        // currentIsRiding semantics:
        //   - Locked ALWAYS means not riding (rider parked the bike).
        //   - Unlocked + wheel turning means riding.
        //   - Unlocked + wheel at rest while previously riding stays
        //     "riding" (traffic-light stop).
        //   - Unlocked + wheel at rest while previously parked stays
        //     "parked" (rider unlocked the bike but hasn't started
        //     pedalling yet).
        val currentIsRiding = when {
            locked == true -> false
            notDriving == false -> true
            notDriving == true && prev.isRiding -> true
            notDriving == true && !prev.isRiding -> false
            // notDriving == null but unlocked: trust the prior state.
            else -> prev.isRiding
        }

        // Silently absorb the very first snapshot so we don't fabricate
        // an edge from "no prior state" to whatever was observed.
        if (!prev.firstSnapshotSeen) {
            return State(isRiding = currentIsRiding, firstSnapshotSeen = true) to Edge.NONE
        }

        val edge = when {
            !prev.isRiding && currentIsRiding -> Edge.STARTED
            prev.isRiding && !currentIsRiding -> Edge.ENDED
            else -> Edge.NONE
        }
        return State(isRiding = currentIsRiding, firstSnapshotSeen = true) to edge
    }
}
