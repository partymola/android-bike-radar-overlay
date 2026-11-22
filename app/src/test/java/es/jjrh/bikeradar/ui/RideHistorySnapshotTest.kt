// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.RideHistoryRecord
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Ride history screen: the empty state and a
 * populated list in each locale (two rides with a passed count, and an older
 * ride with none and no traffic or tightest figure), the Spanish one also at a
 * large font on a narrow phone.
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
        captureRoboImage {
            RideHistoryContent(records = populatedRecords(), onBack = {})
        }
    }

    /** The same rows in Spanish, whose line is the longer of the two. */
    @Test
    @Config(qualifiers = "es-w448dp-h997dp-xxhdpi")
    fun populatedSpanish() {
        captureRoboImage {
            RideHistoryContent(records = populatedRecords(), onBack = {})
        }
    }

    /** The Spanish rows on a narrow phone at a large font, where a line breaks
     *  between segments, never inside one that fits on a line of its own. */
    @Test
    @Config(qualifiers = "es-w360dp-h800dp-xxhdpi")
    fun populatedSpanishLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        captureRoboImage {
            RideHistoryContent(records = populatedRecords(), onBack = {})
        }
    }

    /** A ride with the passed count and two close passes, one with the count and
     *  one close pass, and an older ride with neither. */
    private fun populatedRecords(): List<RideHistoryRecord> {
        // Fixed epoch timestamps (an arbitrary weekday morning; the test
        // JVM runs in UTC) so the rendered dates are stable across runs.
        val morning = 1_780_733_520_000L
        return listOf(
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
                vehiclesPassed = 23,
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
                vehiclesPassed = 15,
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
    }
}
