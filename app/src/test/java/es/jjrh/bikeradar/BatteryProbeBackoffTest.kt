// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the dashcam-probe backoff schedule: base interval at zero failures,
 * doubling per consecutive failure, clamped to the cap, with no overflow for any
 * failure count. Also the `shouldAttempt` gate's null/elapsed boundaries.
 */
class BatteryProbeBackoffTest {

    private val base = 20_000L
    private val cap = 60_000L

    @Test fun zeroFailuresIsBase() {
        assertEquals(base, BatteryProbeBackoff.minIntervalMs(0, base, cap))
    }

    @Test fun negativeFailuresIsBase() {
        assertEquals(base, BatteryProbeBackoff.minIntervalMs(-5, base, cap))
    }

    @Test fun firstFailureDoublesBase() {
        assertEquals(40_000L, BatteryProbeBackoff.minIntervalMs(1, base, cap))
    }

    @Test fun doublingClampsAtCap() {
        // 20s, 40s, 80s→cap, then stays at cap.
        assertEquals(60_000L, BatteryProbeBackoff.minIntervalMs(2, base, cap))
        assertEquals(60_000L, BatteryProbeBackoff.minIntervalMs(3, base, cap))
        assertEquals(60_000L, BatteryProbeBackoff.minIntervalMs(10, base, cap))
    }

    @Test fun largeFailureCountDoesNotOverflowAndStaysAtCap() {
        // A naive `base shl failures` would wrap (Kotlin masks the shift count to
        // 6 bits) and collapse the interval back to base, resurrecting the storm.
        // The doubling-with-early-stop schedule must never wrap.
        assertEquals(cap, BatteryProbeBackoff.minIntervalMs(64, base, cap))
        assertEquals(cap, BatteryProbeBackoff.minIntervalMs(1000, base, cap))
        assertEquals(cap, BatteryProbeBackoff.minIntervalMs(Int.MAX_VALUE, base, cap))
    }

    @Test fun capBelowBaseReturnsCap() {
        assertEquals(10_000L, BatteryProbeBackoff.minIntervalMs(0, 20_000L, 10_000L))
        assertEquals(10_000L, BatteryProbeBackoff.minIntervalMs(5, 20_000L, 10_000L))
    }

    @Test fun shouldAttemptWhenNeverProbed() {
        assertTrue(BatteryProbeBackoff.shouldAttempt(1_000L, null, 99, base, cap))
    }

    @Test fun shouldAttemptBlocksWithinInterval() {
        // 1 failure → 40s interval; 39s elapsed is too soon.
        assertFalse(BatteryProbeBackoff.shouldAttempt(39_000L, 0L, 1, base, cap))
    }

    @Test fun shouldAttemptAtExactInterval() {
        // Boundary is inclusive (>=): exactly 40s elapsed permits the attempt.
        assertTrue(BatteryProbeBackoff.shouldAttempt(40_000L, 0L, 1, base, cap))
    }

    @Test fun shouldAttemptAfterInterval() {
        assertTrue(BatteryProbeBackoff.shouldAttempt(100_000L, 0L, 2, base, cap))
    }

    @Test fun zeroFailuresGatesAtBase() {
        assertFalse(BatteryProbeBackoff.shouldAttempt(19_999L, 0L, 0, base, cap))
        assertTrue(BatteryProbeBackoff.shouldAttempt(20_000L, 0L, 0, base, cap))
    }

    @Test fun disconnectedRadarAlwaysProbes() {
        // Safety branch: while the radar is disconnected the walk-away alarm
        // needs the probe at full cadence, so backoff must NOT apply - even with
        // many failures and a just-now attempt, shouldProbe stays true.
        assertTrue(BatteryProbeBackoff.shouldProbe(false, 1_000L, 1_000L, 50, base, cap))
        assertTrue(BatteryProbeBackoff.shouldProbe(false, 0L, null, 0, base, cap))
    }

    @Test fun connectedRadarAppliesBackoff() {
        // 1 failure -> 40s interval: 39s is too soon, 40s permits.
        assertFalse(BatteryProbeBackoff.shouldProbe(true, 39_000L, 0L, 1, base, cap))
        assertTrue(BatteryProbeBackoff.shouldProbe(true, 40_000L, 0L, 1, base, cap))
    }

    @Test fun connectedRadarProbesWhenNeverAttempted() {
        assertTrue(BatteryProbeBackoff.shouldProbe(true, 1_000L, null, 5, base, cap))
    }
}
