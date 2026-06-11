// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideSummaryNotificationDeciderTest {

    private fun snap(
        exposureSeconds: Long = 0L,
        distanceKm: Float = 0f,
        closePasses: Int = 0,
    ) = RideStatsSnapshot(
        overtakesTotal = 10,
        closePassCount = closePasses,
        grazingCount = 0,
        hgvClosePassCount = 0,
        peakClosingKmh = null,
        closingSpeedP90Kmh = null,
        minLateralClearanceM = null,
        distanceRiddenKm = distanceKm,
        exposureSeconds = exposureSeconds,
        closePassConversionRatePct = 0f,
        tightestPass = null,
        rideStartedAtMs = 0L,
        alertsPerKm = null,
        alertsPerHourOfRide = null,
    )

    private val meaningful = snap(exposureSeconds = 600L)

    @Test fun `posts after the off dwell on a meaningful ride`() {
        assertTrue(
            RideSummaryNotificationDecider.shouldPost(
                radarOffSinceMs = 0L,
                nowMs = RideSummaryNotificationDecider.POST_DWELL_MS,
                alreadyPosted = false,
                snap = meaningful,
            ),
        )
    }

    @Test fun `stays silent inside the off dwell`() {
        // A mid-ride BLE drop reconnects well inside the dwell; the
        // summary must not fire for it.
        assertFalse(
            RideSummaryNotificationDecider.shouldPost(
                radarOffSinceMs = 0L,
                nowMs = RideSummaryNotificationDecider.POST_DWELL_MS - 1,
                alreadyPosted = false,
                snap = meaningful,
            ),
        )
    }

    @Test fun `stays silent while the radar is connected`() {
        assertFalse(
            RideSummaryNotificationDecider.shouldPost(
                radarOffSinceMs = null,
                nowMs = 1_000_000L,
                alreadyPosted = false,
                snap = meaningful,
            ),
        )
    }

    @Test fun `posts once per off episode`() {
        assertFalse(
            RideSummaryNotificationDecider.shouldPost(
                radarOffSinceMs = 0L,
                nowMs = RideSummaryNotificationDecider.POST_DWELL_MS * 2,
                alreadyPosted = true,
                snap = meaningful,
            ),
        )
    }

    @Test fun `bench connect with no ride content stays silent`() {
        assertFalse(
            RideSummaryNotificationDecider.shouldPost(
                radarOffSinceMs = 0L,
                nowMs = RideSummaryNotificationDecider.POST_DWELL_MS,
                alreadyPosted = false,
                snap = snap(exposureSeconds = 30L, distanceKm = 0.1f),
            ),
        )
    }

    @Test fun `each meaningful-ride signal qualifies on its own`() {
        assertTrue(RideSummaryNotificationDecider.isMeaningful(snap(exposureSeconds = 300L)))
        assertTrue(RideSummaryNotificationDecider.isMeaningful(snap(distanceKm = 1.0f)))
        assertTrue(RideSummaryNotificationDecider.isMeaningful(snap(closePasses = 1)))
        assertFalse(RideSummaryNotificationDecider.isMeaningful(snap(exposureSeconds = 299L, distanceKm = 0.9f)))
    }

    @Test fun `new ride starts only past the long-off threshold`() {
        val threshold = 30L * 60_000L
        assertFalse(RideSummaryNotificationDecider.shouldStartNewRide(threshold - 1, threshold))
        assertTrue(RideSummaryNotificationDecider.shouldStartNewRide(threshold, threshold))
    }
}
