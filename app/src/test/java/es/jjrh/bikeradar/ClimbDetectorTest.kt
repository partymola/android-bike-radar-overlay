// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimbDetectorTest {

    @Test fun `null rider power keeps not-climbing and resets state`() {
        // No-eBike rider, or rider_power not yet observed. Graceful
        // degradation: the detector returns not-climbing, doesn't
        // accumulate any dwell, and resets any prior state.
        val prev = ClimbDetector.State(highPowerStartedMs = 1000L)
        val (next, climbing) = ClimbDetector.classify(prev, nowMs = 5000L, riderPowerW = null)
        assertFalse(climbing)
        assertNull(next.highPowerStartedMs)
    }

    @Test fun `power below threshold resets state and stays not-climbing`() {
        // Easy cruise: 100 W on the flat doesn't accumulate climb dwell.
        val (next, climbing) = ClimbDetector.classify(
            prev = ClimbDetector.State(highPowerStartedMs = 1000L),
            nowMs = 5000L,
            riderPowerW = 100,
        )
        assertFalse(climbing)
        assertNull(next.highPowerStartedMs)
    }

    @Test fun `first high-power frame starts the dwell clock but does not yet count as climbing`() {
        // First frame above threshold: record start, don't yet declare
        // climb. Sustained dwell hasn't elapsed.
        val (next, climbing) = ClimbDetector.classify(
            prev = ClimbDetector.State(),
            nowMs = 10_000L,
            riderPowerW = 280,
        )
        assertFalse(climbing)
        assertEquals(10_000L, next.highPowerStartedMs)
    }

    @Test fun `sustained high power for the dwell period flips climbing true`() {
        // 30 s of >=250 W is the canonical climbing signature. The
        // detector says climbing=true exactly at the dwell boundary.
        var state = ClimbDetector.State()
        // t=0: first high frame.
        state = ClimbDetector.classify(state, nowMs = 0L, riderPowerW = 300).first
        // t=29s: still under dwell, not climbing yet.
        val (atDwellMinus, beforeDwell) = ClimbDetector.classify(state, nowMs = 29_000L, riderPowerW = 300)
        assertFalse(beforeDwell)
        state = atDwellMinus
        // t=30s: dwell satisfied, climbing.
        val (atDwell, climbing) = ClimbDetector.classify(state, nowMs = 30_000L, riderPowerW = 300)
        assertTrue(climbing)
        assertEquals(0L, atDwell.highPowerStartedMs)
    }

    @Test fun `power dipping below threshold mid-run resets the dwell`() {
        // Rider grinding up the hill, takes a breather (100 W < 250 W
        // for one frame), then resumes hard. The dip resets the dwell -
        // not climbing until the new run accumulates another 30 s.
        var state = ClimbDetector.State()
        state = ClimbDetector.classify(state, nowMs = 0L, riderPowerW = 300).first
        // 25 s of sustained effort.
        state = ClimbDetector.classify(state, nowMs = 25_000L, riderPowerW = 300).first
        assertNotNull(state.highPowerStartedMs)
        // Breather at 26 s: state resets.
        val (afterDip, climbingDuringDip) = ClimbDetector.classify(state, nowMs = 26_000L, riderPowerW = 80)
        assertFalse(climbingDuringDip)
        assertNull(afterDip.highPowerStartedMs)
        // Resume at 27 s: new dwell clock starts.
        val (afterResume, climbingAtResume) = ClimbDetector.classify(afterDip, nowMs = 27_000L, riderPowerW = 320)
        assertFalse(climbingAtResume)
        assertEquals(27_000L, afterResume.highPowerStartedMs)
    }

    @Test fun `flag-off graceful degradation - all-null inputs stay not-climbing forever`() {
        // When the eBike feature is off, no snapshot is ever produced
        // and the detector is never called. As a defensive contract,
        // explicit null inputs across many calls must never accumulate
        // false climbing.
        var state = ClimbDetector.State()
        for (t in 0L..120_000L step 1_000L) {
            val (next, climbing) = ClimbDetector.classify(state, nowMs = t, riderPowerW = null)
            assertFalse("must never climb on null", climbing)
            assertNull(next.highPowerStartedMs)
            state = next
        }
    }

    @Test fun `exactly threshold power is treated as climbing dwell-eligible`() {
        // Boundary: 250 W exactly is at the threshold. The detector
        // uses >= so the rider doing exactly the threshold effort still
        // starts the dwell clock.
        val (next, _) = ClimbDetector.classify(
            prev = ClimbDetector.State(),
            nowMs = 0L,
            riderPowerW = ClimbDetector.DEFAULT_THRESHOLD_W,
        )
        assertEquals(0L, next.highPowerStartedMs)
    }
}
