// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Owns the eBike live-data snapshot and everything derived from it. The
 * read-only [EBikeStatusReader] (lifecycle kept by [BikeRadarService]) pushes
 * each fresh snapshot here via [onSnapshot]; this class caches it (for the
 * AlertDecider stationary override, the radar-drop freshness gate and
 * [WalkAwayArmingGate]), logs a delta-only capture line, and threads the two
 * pure detectors - [RideEdgeDetector] (ride STARTED/ENDED -> HA) and
 * [ClimbDetector] (sustained rider power -> the climbing bit the AlertDecider
 * consults).
 *
 * Extracted off the service so the orchestration around those detectors is
 * unit-testable without Android: the clock, capture-log writer, ride-edge
 * publisher and ISO-timestamp source are injected. No bonded eBike / flag off /
 * permission missing -> [onSnapshot] is simply never called and [snapshot]
 * stays null, the graceful-degradation path radar-only riders take.
 */
internal class EBikeSnapshotCoordinator(
    private val clock: () -> Long,
    private val clog: (String) -> Unit,
    private val publishRideEdge: (edge: String, isoTime: String) -> Unit,
    private val nowIso: () -> String,
) {
    @Volatile private var lastSnapshot: LiveDataSnapshot? = null

    @Volatile private var lastSnapshotMs: Long = 0L

    // Absolute odometer at the first snapshot of this session; the capture log
    // writes `odo_delta_m = current - baseline` rather than the absolute
    // (privacy hardening, see EBikeCaptureFormatter).
    @Volatile private var sessionStartOdometerM: Long? = null

    // Ride-edge + climb detector state, mutated only on the BLE callback thread
    // inside onSnapshot. See RideEdgeDetector / ClimbDetector.
    @Volatile private var rideEdgeState: RideEdgeDetector.State = RideEdgeDetector.State()

    @Volatile private var climbState: ClimbDetector.State = ClimbDetector.State()

    @Volatile private var climbingFlag: Boolean = false

    // Sticky: true once any snapshot has arrived this session, i.e. the rider
    // has a Bosch eBike streaming. Stays true through a Flow dropout so a
    // momentarily-null snapshot doesn't reclassify an eBike rider as radar-only.
    @Volatile private var everSeen: Boolean = false

    /** Last-known snapshot, or null until the first frame (no eBike / flag off /
     *  permission missing). Consumed by [WalkAwayArmingGate] and the AlertDecider
     *  stationary override. */
    fun snapshot(): LiveDataSnapshot? = lastSnapshot

    /** True once any eBike snapshot has arrived this session (sticky) - i.e. this
     *  is an eBike rider, not a radar-only one. Used to pick the dead-radar
     *  banner's cohort behaviour even if Flow has momentarily dropped. */
    fun hasEverSeenSnapshot(): Boolean = everSeen

    /** Wall-clock of the last snapshot. The radar-drop cue trusts
     *  `system_locked == false` only while this is fresh: a stale snapshot means
     *  the eBike link itself dropped (rider walked away), so "unlocked" can no
     *  longer be believed. */
    fun snapshotAtMs(): Long = lastSnapshotMs

    /** True while sustained rider power has the climb bit set (keeps alerts
     *  firing on a slow climb the stationary-suppress gate would otherwise mute). */
    fun climbing(): Boolean = climbingFlag

    /**
     * Handle a fresh live-data snapshot: cache it for the AlertDecider
     * stationary override and the walk-away arming gate, append a delta-only
     * line to the capture log, and drive ride-edge + climb detection.
     */
    fun onSnapshot(snap: LiveDataSnapshot) {
        lastSnapshot = snap
        lastSnapshotMs = clock()
        everSeen = true
        // Capture odometer baseline on first sighting, then log the snapshot
        // delta-only. format() returns null when every field is still
        // unobserved so we skip logging empty stubs.
        if (sessionStartOdometerM == null) {
            sessionStartOdometerM = snap.odometerM
        }
        EBikeCaptureFormatter.format(snap, sessionStartOdometerM)?.let(clog)
        // Feed the edge detector; on STARTED / ENDED publish to HA so
        // dashboards and automations have bike-truth ride boundaries
        // (independent of GPS drift on the office side).
        val (nextState, edge) = RideEdgeDetector.next(rideEdgeState, snap)
        rideEdgeState = nextState
        if (edge != RideEdgeDetector.Edge.NONE) {
            val edgeName = if (edge == RideEdgeDetector.Edge.STARTED) "started" else "ended"
            val nowIsoTime = nowIso()
            clog("# ebike ride_edge=$edgeName t=$nowIsoTime")
            publishRideEdge(edgeName, nowIsoTime)
        }
        // Thread the climb state. Sustained high rider_power (default >= 250 W
        // for >= 30 s) flips the climbing bit, which the AlertDecider
        // stationary override consults to keep alerts firing on a slow climb.
        val (nextClimb, isClimbing) = ClimbDetector.classify(
            prev = climbState,
            nowMs = clock(),
            riderPowerW = snap.riderPower,
        )
        climbState = nextClimb
        if (isClimbing != climbingFlag) {
            climbingFlag = isClimbing
            clog("# ebike climbing=$isClimbing rider_power=${snap.riderPower}")
        }
    }
}
