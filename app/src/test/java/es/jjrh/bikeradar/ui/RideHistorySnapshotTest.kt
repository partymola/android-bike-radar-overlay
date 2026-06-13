// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.RideHistoryRecord
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Ride history screen: the empty state and a
 * populated list (full ride, no-traffic ride, no-tightest ride - the
 * three row shapes the formatter produces).
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class RideHistorySnapshotTest {

    @Test
    fun empty() {
        captureRoboImage {
            RideHistoryContent(records = emptyList(), onBack = {})
        }
    }

    @Test
    fun populated() {
        // Fixed epoch timestamps (an arbitrary weekday morning; the test
        // JVM runs in UTC) so the rendered dates are stable across runs.
        val morning = 1_780_733_520_000L
        val records = listOf(
            RideHistoryRecord(
                startedAtMs = morning + 4 * 86_400_000L,
                endedAtMs = morning + 4 * 86_400_000L + 42 * 60_000L,
                overtakes = 48,
                closePasses = 2,
                grazingPasses = 1,
                hgvClosePasses = 1,
                peakClosingKmh = 52,
                closingSpeedP90Kmh = 38,
                minLateralClearanceM = 0.8f,
                distanceKm = 12.4f,
                exposureSeconds = 950L,
                alertsPerKm = 1.4f,
                tightestPassClearanceM = 0.8f,
                tightestPassClosingKmh = 32,
            ),
            RideHistoryRecord(
                startedAtMs = morning + 86_400_000L,
                endedAtMs = morning + 86_400_000L + 38 * 60_000L,
                overtakes = 31,
                closePasses = 1,
                grazingPasses = 0,
                hgvClosePasses = 0,
                peakClosingKmh = 44,
                closingSpeedP90Kmh = 30,
                minLateralClearanceM = 0.9f,
                distanceKm = 11.9f,
                exposureSeconds = 820L,
                alertsPerKm = 0.9f,
                tightestPassClearanceM = 0.9f,
                tightestPassClosingKmh = 28,
            ),
            RideHistoryRecord(
                startedAtMs = morning,
                endedAtMs = morning + 35 * 60_000L,
                overtakes = 0,
                closePasses = 0,
                grazingPasses = 0,
                hgvClosePasses = 0,
                peakClosingKmh = null,
                closingSpeedP90Kmh = null,
                minLateralClearanceM = null,
                distanceKm = 10.8f,
                exposureSeconds = 0L,
                alertsPerKm = null,
                tightestPassClearanceM = null,
                tightestPassClosingKmh = null,
            ),
        )
        captureRoboImage {
            RideHistoryContent(records = records, onBack = {})
        }
    }
}
