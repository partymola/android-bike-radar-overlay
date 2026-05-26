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

    private val short = BikeRadarService.RADAR_RECONNECT_BACKOFF_MAX_MS
    private val threshold = 30L * 60 * 1000 // 30 min, default
    private val longCap = 30L * 1000 // 30 s, default

    @Test fun nullOffSinceUsesShortCap() {
        assertEquals(
            short,
            BikeRadarService.reconnectBackoffCap(
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
            BikeRadarService.reconnectBackoffCap(
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
            BikeRadarService.reconnectBackoffCap(
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
            BikeRadarService.reconnectBackoffCap(
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
            BikeRadarService.reconnectBackoffCap(
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
            BikeRadarService.reconnectBackoffCap(
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
            BikeRadarService.reconnectBackoffCap(
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
            BikeRadarService.reconnectBackoffCap(
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
}
