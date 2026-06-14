// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Verifies the reconnect-backoff cap function: returns the steady-state
 * 8 s cap when the radar has been offline for at most the user-configured
 * threshold, the user-configured long-offline cap once that threshold is
 * exceeded. Threshold is exclusive ("strictly greater than"), so the
 * boundary stays on the short cap.
 */
class ReconnectBackoffTest {

    private val short = RADAR_RECONNECT_BACKOFF_MAX_MS
    private val threshold = 30L * 60 * 1000 // 30 min, default
    private val longCap = 30L * 1000 // 30 s, default

    @Test fun nullOffSinceUsesShortCap() {
        assertEquals(
            short,
            reconnectBackoffCap(
                now = 1_000_000L,
                offSinceMs = null,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun zeroElapsedUsesShortCap() {
        assertEquals(
            short,
            reconnectBackoffCap(
                now = 5_000L,
                offSinceMs = 5_000L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun justUnderThresholdUsesShortCap() {
        assertEquals(
            short,
            reconnectBackoffCap(
                now = threshold - 1,
                offSinceMs = 0L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun atThresholdStillUsesShortCap() {
        assertEquals(
            short,
            reconnectBackoffCap(
                now = threshold,
                offSinceMs = 0L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun pastThresholdUsesLongCap() {
        assertEquals(
            longCap,
            reconnectBackoffCap(
                now = threshold + 1,
                offSinceMs = 0L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun overnightOfflineUsesLongCap() {
        val twentyFourHoursMs = 24L * 60 * 60 * 1000
        assertEquals(
            longCap,
            reconnectBackoffCap(
                now = twentyFourHoursMs,
                offSinceMs = 0L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun userTunedShorterThresholdHonoured() {
        // 5-min threshold (lower bound of Settings range): elapsed of 6 min
        // should already use the long cap.
        val tightThreshold = 5L * 60 * 1000
        assertEquals(
            longCap,
            reconnectBackoffCap(
                now = 6L * 60 * 1000,
                offSinceMs = 0L,
                longOfflineThresholdMs = tightThreshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun userTunedLongerCapHonoured() {
        val customCap = 90L * 1000
        assertEquals(
            customCap,
            reconnectBackoffCap(
                now = threshold + 1,
                offSinceMs = 0L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = customCap,
            ),
        )
    }

    @Test fun jitteredStaysWithinTwentyPercentBand() {
        // Sampling: every draw must land in [base*0.8, base*1.2] inclusive.
        val base = 8_000L
        val low = (base * 0.8).toLong()
        val high = (base * 1.2).toLong()
        val rng = Random(42L)
        repeat(10_000) {
            val v = jittered(base, rng)
            assertTrue("$v not in [$low, $high]", v in low..high)
        }
    }

    @Test fun jitteredActuallySpreads() {
        // Sanity: 1000 draws on the same base must produce at least two
        // distinct values (otherwise jitter is silently a no-op).
        val rng = Random(7L)
        val draws = (1..1000).map { jittered(8_000L, rng) }.toSet()
        assertTrue("only ${draws.size} distinct values from 1000 draws", draws.size > 1)
    }

    @Test fun jitteredIsDeterministicWithSeededRandom() {
        // Replaying the same seed yields the same sequence; required for the
        // bounds test above and for any future regression run.
        val seq1 = (1..10).map { jittered(8_000L, Random(123L)) }
        val seq2 = (1..10).map { jittered(8_000L, Random(123L)) }
        assertEquals(seq1, seq2)
        // Sanity: a different seed gives a different sequence.
        val seq3 = (1..10).map { jittered(8_000L, Random(124L)) }
        assertNotEquals(seq1, seq3)
    }

    @Test fun jitteredZeroOrNegativePassesThrough() {
        // Jittering into the past would be meaningless; the helper must be
        // a no-op for non-positive inputs.
        assertEquals(0L, jittered(0L))
        assertEquals(-5L, jittered(-5L))
    }

    @Test fun jitteredSmallBasePassesThroughWhenRangeRoundsToZero() {
        // A 4 ms base produces a 0 ms jitter range (4 * 0.2 = 0.8 -> 0L);
        // returning the base unchanged is safer than calling
        // Random.nextLong(0, 1) and biasing toward zero.
        assertEquals(4L, jittered(4L, Random(0L)))
    }

    // ReconnectLoopPlanner: the stateless backoff steps shared by the rear-radar,
    // front-camera, and eBike-status reconnect loops. Each loop keeps its own
    // reset trigger; these pin only the delay-selection and grow arithmetic they
    // all lean on.

    @Test fun nextDelayQuickUsesFixedShortWaitRegardlessOfBackoff() {
        // A quick (post-ABORT) reconnect ignores the accumulated backoff.
        assertEquals(
            RADAR_QUICK_RECONNECT_MS,
            ReconnectLoopPlanner.nextDelayMs(backoffMs = 8_000L, quickReconnect = true, random = Random(0L)),
        )
    }

    @Test fun nextDelayNonQuickJittersTheBackoff() {
        // Non-quick delay is the current backoff with +/-20% jitter applied.
        val base = 4_000L
        val low = (base * 0.8).toLong()
        val high = (base * 1.2).toLong()
        val v = ReconnectLoopPlanner.nextDelayMs(backoffMs = base, quickReconnect = false, random = Random(99L))
        assertTrue("$v not in [$low, $high]", v in low..high)
    }

    @Test fun nextDelayNonQuickDelegatesToJitteredExactly() {
        // Same seed through the planner and the raw helper must agree, proving
        // the planner adds no hidden transform.
        assertEquals(
            jittered(4_000L, Random(55L)),
            ReconnectLoopPlanner.nextDelayMs(backoffMs = 4_000L, quickReconnect = false, random = Random(55L)),
        )
    }

    @Test fun growDoublesBelowCeiling() {
        // 1 s -> 2 s, well under the 8 s steady-state ceiling.
        assertEquals(
            2_000L,
            ReconnectLoopPlanner.grow(
                backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS,
                nowMs = 5_000L,
                offSinceMs = 5_000L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun growClampsToSteadyStateCeiling() {
        // 8 s doubled is 16 s but the short-offline ceiling holds it at 8 s.
        assertEquals(
            RADAR_RECONNECT_BACKOFF_MAX_MS,
            ReconnectLoopPlanner.grow(
                backoffMs = RADAR_RECONNECT_BACKOFF_MAX_MS,
                nowMs = 5_000L,
                offSinceMs = 5_000L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun growMidValueClampsToCeiling() {
        // 5 s doubled is 10 s, clamped down to the 8 s ceiling (not left at 10).
        assertEquals(
            RADAR_RECONNECT_BACKOFF_MAX_MS,
            ReconnectLoopPlanner.grow(
                backoffMs = 5_000L,
                nowMs = 5_000L,
                offSinceMs = 5_000L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun growRelaxesCeilingOnceLongOffline() {
        // Past the long-offline threshold the ceiling lifts to longCap (30 s),
        // so 8 s can double past the steady-state 8 s cap toward the relaxed one.
        assertEquals(
            16_000L,
            ReconnectLoopPlanner.grow(
                backoffMs = RADAR_RECONNECT_BACKOFF_MAX_MS,
                nowMs = threshold + 1,
                offSinceMs = 0L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun growClampsToRelaxedCeilingWhenLongOffline() {
        // Above the relaxed cap, doubling is held at longCap (20 s -> 30 s, not 40).
        assertEquals(
            longCap,
            ReconnectLoopPlanner.grow(
                backoffMs = 20_000L,
                nowMs = threshold + 1,
                offSinceMs = 0L,
                longOfflineThresholdMs = threshold,
                longOfflineCapMs = longCap,
            ),
        )
    }

    @Test fun growWithNullOffSinceHoldsShortCapNeverRelaxes() {
        // The eBike loop tracks no off-instant and passes offSinceMs = null, so
        // grow must clamp to the steady-state 8 s ceiling and never relax,
        // regardless of the (inert) now / threshold / long-cap args. 6 s doubled
        // is 12 s, held at 8 s.
        assertEquals(
            RADAR_RECONNECT_BACKOFF_MAX_MS,
            ReconnectLoopPlanner.grow(
                backoffMs = 6_000L,
                nowMs = 0L,
                offSinceMs = null,
                longOfflineThresholdMs = 0L,
                longOfflineCapMs = 0L,
            ),
        )
    }
}
