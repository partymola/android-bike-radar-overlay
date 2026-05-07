// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the reconnect-backoff cap function: returns the steady-state
 * 8 s cap when the radar has been offline for at most the user-configured
 * threshold, the user-configured long-offline cap once that threshold is
 * exceeded. Threshold is exclusive ("strictly greater than"), so the
 * boundary stays on the short cap.
 */
class ReconnectBackoffTest {

    private val short = BikeRadarService.RADAR_RECONNECT_BACKOFF_MAX_MS
    private val threshold = 30L * 60 * 1000   // 30 min, default
    private val longCap = 30L * 1000          // 30 s, default

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
}
