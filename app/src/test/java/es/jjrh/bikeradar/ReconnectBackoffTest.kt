// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the reconnect-backoff cap function: 8 s normally, 60 s once
 * the radar has been offline past 30 min. The threshold is exclusive
 * ("strictly greater than"), so the boundary stays on the short cap.
 */
class ReconnectBackoffTest {

    private val short = BikeRadarService.RADAR_RECONNECT_BACKOFF_MAX_MS
    private val long = BikeRadarService.RADAR_RECONNECT_BACKOFF_LONG_OFFLINE_MAX_MS
    private val threshold = BikeRadarService.RADAR_LONG_OFFLINE_THRESHOLD_MS

    @Test fun nullOffSinceUsesShortCap() {
        assertEquals(short, BikeRadarService.reconnectBackoffCap(now = 1_000_000L, offSinceMs = null))
    }

    @Test fun zeroElapsedUsesShortCap() {
        assertEquals(short, BikeRadarService.reconnectBackoffCap(now = 5_000L, offSinceMs = 5_000L))
    }

    @Test fun justUnderThresholdUsesShortCap() {
        val off = 0L
        assertEquals(short, BikeRadarService.reconnectBackoffCap(now = threshold - 1, offSinceMs = off))
    }

    @Test fun atThresholdStillUsesShortCap() {
        val off = 0L
        assertEquals(short, BikeRadarService.reconnectBackoffCap(now = threshold, offSinceMs = off))
    }

    @Test fun pastThresholdUsesLongCap() {
        val off = 0L
        assertEquals(long, BikeRadarService.reconnectBackoffCap(now = threshold + 1, offSinceMs = off))
    }

    @Test fun overnightOfflineUsesLongCap() {
        val off = 0L
        val twentyFourHoursMs = 24L * 60 * 60 * 1000
        assertEquals(long, BikeRadarService.reconnectBackoffCap(now = twentyFourHoursMs, offSinceMs = off))
    }
}
