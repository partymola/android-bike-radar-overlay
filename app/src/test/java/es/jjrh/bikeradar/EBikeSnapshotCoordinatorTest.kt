// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [EBikeSnapshotCoordinator] - the orchestration around
 * the (separately-tested) [RideEdgeDetector] / [ClimbDetector] / capture
 * formatter: snapshot caching, the once-per-session odometer baseline, the
 * ride-edge HA publish + clog wiring, and the climb-bit flip. Replaces the
 * inline service logic that had no test seam.
 */
class EBikeSnapshotCoordinatorTest {

    private var now = 1_000L
    private val clogLines = mutableListOf<String>()
    private val rideEdges = mutableListOf<Pair<String, String>>()
    private val iso = "ride-iso-stub" // opaque stand-in; production injects Instant.now().toString()

    private val coord = EBikeSnapshotCoordinator(
        clock = { now },
        clog = { clogLines += it },
        publishRideEdge = { edge, t -> rideEdges += (edge to t) },
        nowIso = { iso },
    )

    private fun feed(snap: LiveDataSnapshot, atMs: Long = now) {
        now = atMs
        coord.onSnapshot(snap)
    }

    private fun clogged(token: String) = clogLines.count { it.contains(token) }

    @Test
    fun cachesSnapshotAndTimestamp() {
        val snap = LiveDataSnapshot(batterySoc = 80)
        feed(snap, atMs = 5_000L)
        assertSame(snap, coord.snapshot())
        assertEquals(5_000L, coord.snapshotAtMs())
    }

    @Test
    fun startsWithNullSnapshot() {
        assertNull(coord.snapshot())
        assertEquals(0L, coord.snapshotAtMs())
        assertFalse(coord.climbing())
        assertFalse(coord.hasEverSeenSnapshot())
    }

    @Test
    fun hasEverSeenSnapshotIsStickyAfterFirstFrame() {
        assertFalse(coord.hasEverSeenSnapshot())
        feed(LiveDataSnapshot(batterySoc = 50))
        assertTrue(coord.hasEverSeenSnapshot())
    }

    @Test
    fun odometerBaselineCapturedOnceAcrossSnapshots() {
        // First snapshot fixes the baseline; the capture line logs the delta
        // from it, so a later snapshot's delta is measured against the FIRST
        // odometer, not refreshed each frame.
        feed(LiveDataSnapshot(odometerM = 1_000L))
        feed(LiveDataSnapshot(odometerM = 1_250L))
        val lastLine = clogLines.last { it.startsWith("ebike") }
        assertTrue("expected delta 250 vs baseline 1000, got: $lastLine", lastLine.contains("odo_delta_m=250"))
    }

    @Test
    fun allNullSnapshotLogsNoCaptureLine() {
        feed(LiveDataSnapshot())
        assertEquals(0, clogLines.count { it.startsWith("ebike") })
    }

    @Test
    fun firstSnapshotDoesNotSynthesizeRideEdge() {
        // The first observation of a locked+stopped bike must not fabricate an
        // "ended" (or "started") edge.
        feed(LiveDataSnapshot(systemLocked = true))
        assertEquals(0, rideEdges.size)
        assertEquals(0, clogged("ride_edge"))
    }

    @Test
    fun rideEdgeStartedPublishesAndLogs() {
        feed(LiveDataSnapshot(systemLocked = true)) // seed: parked, firstSnapshotSeen
        feed(LiveDataSnapshot(systemLocked = false, bikeNotDriving = false)) // unlock + wheel turning
        assertEquals(listOf("started" to iso), rideEdges)
        assertEquals(1, clogged("ride_edge=started"))
    }

    @Test
    fun rideEdgeEndedPublishesAndLogs() {
        feed(LiveDataSnapshot(systemLocked = true))
        feed(LiveDataSnapshot(systemLocked = false, bikeNotDriving = false)) // STARTED
        feed(LiveDataSnapshot(systemLocked = true)) // lock -> ENDED
        assertEquals(listOf("started" to iso, "ended" to iso), rideEdges)
        assertEquals(1, clogged("ride_edge=ended"))
    }

    @Test
    fun midRideTrafficStopDoesNotPublishEdge() {
        feed(LiveDataSnapshot(systemLocked = true))
        feed(LiveDataSnapshot(systemLocked = false, bikeNotDriving = false)) // STARTED
        rideEdges.clear()
        feed(LiveDataSnapshot(systemLocked = false, bikeNotDriving = true)) // wheel at rest, still unlocked
        assertEquals(0, rideEdges.size)
    }

    @Test
    fun cachesTimestampOnEverySnapshot() {
        feed(LiveDataSnapshot(batterySoc = 50), atMs = 5_000L)
        assertEquals(5_000L, coord.snapshotAtMs())
        feed(LiveDataSnapshot(batterySoc = 51), atMs = 9_000L)
        assertEquals(9_000L, coord.snapshotAtMs()) // freshness advances; the radar-drop gate relies on it
    }

    @Test
    fun climbFlipLogsAndSetsFlag() {
        // Rider power above the 250 W threshold, sustained 30 s, flips the climb bit.
        feed(LiveDataSnapshot(riderPower = 300), atMs = 0L) // run starts, not yet sustained
        assertFalse(coord.climbing())
        assertEquals(0, clogged("climbing="))

        feed(LiveDataSnapshot(riderPower = 300), atMs = 30_000L) // 30 s elapsed -> climbing
        assertTrue(coord.climbing())
        assertEquals(1, clogged("climbing=true"))
    }

    @Test
    fun climbStableDoesNotReLog() {
        feed(LiveDataSnapshot(riderPower = 300), atMs = 0L)
        feed(LiveDataSnapshot(riderPower = 300), atMs = 30_000L) // flips true (1 log)
        feed(LiveDataSnapshot(riderPower = 300), atMs = 40_000L) // still climbing, no re-log
        assertTrue(coord.climbing())
        assertEquals(1, clogged("climbing=true"))
    }

    @Test
    fun climbDropClearsFlagAndLogsOnce() {
        feed(LiveDataSnapshot(riderPower = 300), atMs = 0L)
        feed(LiveDataSnapshot(riderPower = 300), atMs = 30_000L) // climbing=true
        feed(LiveDataSnapshot(riderPower = 50), atMs = 31_000L) // below threshold -> reset
        assertFalse(coord.climbing())
        assertEquals(1, clogged("climbing=false"))
    }

    @Test
    fun reClimbAfterDropRequiresFreshDwell() {
        // After a drop, a new high-power run must re-accumulate the full 30 s
        // dwell - it must not re-flip instantly off the previous run's start.
        // Pins that climbState is threaded back after the reset.
        feed(LiveDataSnapshot(riderPower = 300), atMs = 0L)
        feed(LiveDataSnapshot(riderPower = 300), atMs = 30_000L) // climbing=true
        feed(LiveDataSnapshot(riderPower = 50), atMs = 31_000L) // drop -> reset
        feed(LiveDataSnapshot(riderPower = 300), atMs = 31_000L) // new run starts here
        feed(LiveDataSnapshot(riderPower = 300), atMs = 45_000L) // 14 s into new run
        assertFalse("must not re-flip before a fresh 30 s dwell", coord.climbing())
        feed(LiveDataSnapshot(riderPower = 300), atMs = 61_000L) // 30 s into new run
        assertTrue(coord.climbing())
    }
}
