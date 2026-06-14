// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rear-radar V2 data-flow watchdog staleness decision: the
 * strictly-greater teardown boundary, the "no frame yet" guard, and - the
 * safety point - that once the monotonic age exceeds the stall it stays stale
 * as time advances, so a silently-dead radar is always torn down. Plain JVM:
 * the decider takes nowMs as an argument, so there is no clock to mock (the
 * production caller supplies SystemClock.elapsedRealtime()).
 */
class V2WatchdogDeciderTest {

    private val stall = 5_000L

    @Test fun noFrameYetIsNeverStale() {
        assertFalse(V2WatchdogDecider.isStale(nowMs = 1_000_000L, lastFrameMs = 0L, stallMs = stall))
    }

    @Test fun underThresholdIsFresh() {
        assertFalse(V2WatchdogDecider.isStale(nowMs = 104_999L, lastFrameMs = 100_000L, stallMs = stall))
    }

    @Test fun exactlyAtThresholdIsStillFresh() {
        // age == stall is NOT stale (strictly-greater), matching the prior inline `ageMs > STALL`.
        assertFalse(V2WatchdogDecider.isStale(nowMs = 105_000L, lastFrameMs = 100_000L, stallMs = stall))
    }

    @Test fun pastThresholdIsStale() {
        assertTrue(V2WatchdogDecider.isStale(nowMs = 105_001L, lastFrameMs = 100_000L, stallMs = stall))
    }

    @Test fun staysStaleAsMonotonicTimeAdvances() {
        // elapsedRealtime never goes backward, so once the age exceeds the stall
        // every later (>=) reading is still stale - the radar stays torn down.
        val lastFrame = 100_000L
        assertTrue(V2WatchdogDecider.isStale(nowMs = 106_000L, lastFrameMs = lastFrame, stallMs = stall))
        assertTrue(V2WatchdogDecider.isStale(nowMs = 200_000L, lastFrameMs = lastFrame, stallMs = stall))
    }
}
