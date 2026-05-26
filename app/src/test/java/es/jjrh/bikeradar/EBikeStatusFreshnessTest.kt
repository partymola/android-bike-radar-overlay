// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freshness gate for the eBike live-data UI: snapshot fields stay populated
 * after Flow closes, so [eBikeDataIsFresh] (wall-clock receipt age) is the
 * honest "is data still flowing" signal driving "Receiving" vs "Waiting".
 */
class EBikeStatusFreshnessTest {

    @Test
    fun neverReceivedIsNotFresh() {
        assertFalse(eBikeDataIsFresh(lastUpdatedElapsedMs = 0L, nowMs = 10_000L))
    }

    @Test
    fun recentFrameIsFresh() {
        assertTrue(
            eBikeDataIsFresh(lastUpdatedElapsedMs = 10_000L, nowMs = 12_000L, windowMs = 6_000L),
        )
    }

    @Test
    fun oldFrameIsStale() {
        assertFalse(
            eBikeDataIsFresh(lastUpdatedElapsedMs = 10_000L, nowMs = 20_000L, windowMs = 6_000L),
        )
    }

    @Test
    fun boundaryIsStale() {
        // Exactly windowMs old -> stale (half-open interval).
        assertFalse(
            eBikeDataIsFresh(lastUpdatedElapsedMs = 10_000L, nowMs = 16_000L, windowMs = 6_000L),
        )
    }
}
