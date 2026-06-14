// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideSummaryEdgeTrackerTest {

    // Any snapshot works: the tracker passes it straight through to PostSummary.
    // Injected clocks keep it deterministic without Robolectric.
    private val snap = RideStatsAccumulator(nowMsProvider = { 0L }, monoMsProvider = { 0L }).snapshot()

    private val dwell = 180_000L
    private val longOff = 600_000L // 10 min

    private fun onTick(
        prev: RideSummaryEdgeTracker.State,
        radarOffSinceMs: Long?,
        nowMs: Long,
        evaluatePost: () -> RideStatsSnapshot?,
    ) = RideSummaryEdgeTracker.onTick(prev, radarOffSinceMs, nowMs, longOff, dwell, evaluatePost)

    // ── radar on ─────────────────────────────────────────────────────────────

    @Test fun `radar on with no prior off is a no-op and takes no snapshot`() {
        var calls = 0
        val out = onTick(RideSummaryEdgeTracker.State(), radarOffSinceMs = null, nowMs = 1_000L) {
            calls++
            snap
        }
        assertEquals("no post path on the radar-on branch", 0, calls)
        assertEquals(RideSummaryEdgeTracker.State(lastRadarOffSinceMs = null, rideSummaryPosted = false), out.state)
        assertTrue(out.actions.isEmpty())
    }

    @Test fun `radar returns after a short off continues the ride and clears the posted flag`() {
        val prev = RideSummaryEdgeTracker.State(lastRadarOffSinceMs = 1_000L, rideSummaryPosted = true)
        // off duration 5 s, well under the 10 min new-ride threshold.
        val out = onTick(prev, radarOffSinceMs = null, nowMs = 6_000L) { snap }
        assertTrue("short gap must not start a new ride", out.actions.isEmpty())
        assertEquals(RideSummaryEdgeTracker.State(lastRadarOffSinceMs = null, rideSummaryPosted = false), out.state)
    }

    @Test fun `radar returns after a long off starts a new ride`() {
        val prev = RideSummaryEdgeTracker.State(lastRadarOffSinceMs = 1_000L, rideSummaryPosted = true)
        // off duration == longOff exactly (>= threshold) -> new ride.
        val out = onTick(prev, radarOffSinceMs = null, nowMs = 1_000L + longOff) { snap }
        assertEquals(listOf(RideSummaryEdgeTracker.Action.ResetRideStats), out.actions)
        assertEquals(RideSummaryEdgeTracker.State(lastRadarOffSinceMs = null, rideSummaryPosted = false), out.state)
    }

    // ── radar off ────────────────────────────────────────────────────────────

    @Test fun `radar off within the dwell tracks the off-instant without snapshotting`() {
        var calls = 0
        val out = onTick(RideSummaryEdgeTracker.State(), radarOffSinceMs = 5_000L, nowMs = 5_000L + dwell - 1) {
            calls++
            snap
        }
        assertEquals("must not snapshot inside the dwell (thread-safety gate)", 0, calls)
        assertEquals(RideSummaryEdgeTracker.State(lastRadarOffSinceMs = 5_000L, rideSummaryPosted = false), out.state)
        assertTrue(out.actions.isEmpty())
    }

    @Test fun `radar off past the dwell posts when the snapshot is meaningful`() {
        val out = onTick(RideSummaryEdgeTracker.State(), radarOffSinceMs = 5_000L, nowMs = 5_000L + dwell) { snap }
        assertEquals(RideSummaryEdgeTracker.State(lastRadarOffSinceMs = 5_000L, rideSummaryPosted = true), out.state)
        assertEquals(listOf(RideSummaryEdgeTracker.Action.PostSummary(snap, offInstantMs = 5_000L)), out.actions)
    }

    @Test fun `radar off past the dwell does not post when the snapshot is not meaningful`() {
        val out = onTick(RideSummaryEdgeTracker.State(), radarOffSinceMs = 5_000L, nowMs = 5_000L + dwell) { null }
        assertEquals(RideSummaryEdgeTracker.State(lastRadarOffSinceMs = 5_000L, rideSummaryPosted = false), out.state)
        assertTrue(out.actions.isEmpty())
    }

    @Test fun `an already-posted off-episode does not re-post or re-snapshot`() {
        var calls = 0
        val prev = RideSummaryEdgeTracker.State(lastRadarOffSinceMs = 5_000L, rideSummaryPosted = true)
        val out = onTick(prev, radarOffSinceMs = 5_000L, nowMs = 5_000L + dwell * 2) {
            calls++
            snap
        }
        assertEquals("a posted episode must not snapshot again", 0, calls)
        assertEquals(prev, out.state)
        assertTrue(out.actions.isEmpty())
    }
}
