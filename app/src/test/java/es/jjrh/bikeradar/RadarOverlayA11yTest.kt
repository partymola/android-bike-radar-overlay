// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [buildOverlayA11ySummary] - the spoken overlay summary that the
 * Canvas view itself can't unit-test (its Paparazzi test is excluded from
 * CI). Plain JVM, no Robolectric.
 */
class RadarOverlayA11yTest {

    private fun state(vararg v: Vehicle) = RadarState(vehicles = v.toList())

    @Test fun clearRoadWhenNoVehicles() {
        assertEquals(
            "Bike radar overlay. Road clear.",
            buildOverlayA11ySummary(RadarState(), DashcamStatus.Ok, batteryLow = false),
        )
    }

    @Test fun behindVehiclesAreNotCounted() {
        // A target that has overtaken the rider is excluded from the count.
        val s = state(Vehicle(id = 1, distanceM = 10, speedMs = -5f, isBehind = true))
        assertEquals(
            "Bike radar overlay. Road clear.",
            buildOverlayA11ySummary(s, DashcamStatus.Ok, batteryLow = false),
        )
    }

    @Test fun singleVehicleIsSingularWithDistance() {
        val s = state(Vehicle(id = 1, distanceM = 15, speedMs = -5f))
        assertEquals(
            "Bike radar overlay. 1 vehicle, nearest 15 metres.",
            buildOverlayA11ySummary(s, DashcamStatus.Ok, batteryLow = false),
        )
    }

    @Test fun multipleVehiclesArePluralAndReportNearest() {
        val s = state(
            Vehicle(id = 1, distanceM = 20, speedMs = -5f),
            Vehicle(id = 2, distanceM = 8, speedMs = -6f),
        )
        assertEquals(
            "Bike radar overlay. 2 vehicles, nearest 8 metres.",
            buildOverlayA11ySummary(s, DashcamStatus.Ok, batteryLow = false),
        )
    }

    @Test fun dashcamWarningsAppendPerStatus() {
        assertEquals(
            "Bike radar overlay. Road clear. Dashcam connection lost.",
            buildOverlayA11ySummary(RadarState(), DashcamStatus.Dropped, batteryLow = false),
        )
        assertEquals(
            "Bike radar overlay. Road clear. Dashcam not found.",
            buildOverlayA11ySummary(RadarState(), DashcamStatus.Missing, batteryLow = false),
        )
        assertEquals(
            "Bike radar overlay. Road clear. Searching for dashcam.",
            buildOverlayA11ySummary(RadarState(), DashcamStatus.Searching, batteryLow = false),
        )
    }

    @Test fun lowBatterySuffixAndCombinedWarnings() {
        assertEquals(
            "Bike radar overlay. Road clear. Low battery.",
            buildOverlayA11ySummary(RadarState(), DashcamStatus.Ok, batteryLow = true),
        )
        val s = state(Vehicle(id = 1, distanceM = 15, speedMs = -5f))
        assertEquals(
            "Bike radar overlay. 1 vehicle, nearest 15 metres. Dashcam not found. Low battery.",
            buildOverlayA11ySummary(s, DashcamStatus.Missing, batteryLow = true),
        )
    }
}
