// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the spoken overlay summary backing the Canvas view's a11y node:
 * the pure [buildOverlayA11yModel] state logic resolved through
 * [overlayA11yDescription]. Runs under Robolectric so the externalised copy
 * + the "N vehicles" quantity string resolve from the real resources; the
 * assertions pin the exact spoken English. The view's rendering is covered
 * separately by RadarOverlayViewTest.
 */
@RunWith(RobolectricTestRunner::class)
class RadarOverlayA11yTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun state(vararg v: Vehicle) = RadarState(vehicles = v.toList())

    private fun summary(
        state: RadarState,
        dashcamStatus: DashcamStatus,
        batteryLow: Boolean,
    ) = ctx.overlayA11yDescription(buildOverlayA11yModel(state, dashcamStatus, batteryLow))

    @Test fun clearRoadWhenNoVehicles() {
        assertEquals(
            "Bike radar overlay. Road clear.",
            summary(RadarState(), DashcamStatus.Ok, batteryLow = false),
        )
    }

    @Test fun behindVehiclesAreNotCounted() {
        // A target that has overtaken the rider is excluded from the count.
        val s = state(Vehicle(id = 1, distanceM = 10, speedMs = -5f, isBehind = true))
        assertEquals(
            "Bike radar overlay. Road clear.",
            summary(s, DashcamStatus.Ok, batteryLow = false),
        )
    }

    @Test fun singleVehicleIsSingularWithDistance() {
        val s = state(Vehicle(id = 1, distanceM = 15, speedMs = -5f))
        assertEquals(
            "Bike radar overlay. 1 vehicle, nearest 15 metres.",
            summary(s, DashcamStatus.Ok, batteryLow = false),
        )
    }

    @Test fun multipleVehiclesArePluralAndReportNearest() {
        val s = state(
            Vehicle(id = 1, distanceM = 20, speedMs = -5f),
            Vehicle(id = 2, distanceM = 8, speedMs = -6f),
        )
        assertEquals(
            "Bike radar overlay. 2 vehicles, nearest 8 metres.",
            summary(s, DashcamStatus.Ok, batteryLow = false),
        )
    }

    @Test fun dashcamWarningsAppendPerStatus() {
        assertEquals(
            "Bike radar overlay. Road clear. Dashcam connection lost.",
            summary(RadarState(), DashcamStatus.Dropped, batteryLow = false),
        )
        assertEquals(
            "Bike radar overlay. Road clear. Dashcam not found.",
            summary(RadarState(), DashcamStatus.Missing, batteryLow = false),
        )
        assertEquals(
            "Bike radar overlay. Road clear. Searching for dashcam.",
            summary(RadarState(), DashcamStatus.Searching, batteryLow = false),
        )
    }

    @Test fun lowBatterySuffixAndCombinedWarnings() {
        assertEquals(
            "Bike radar overlay. Road clear. Low battery.",
            summary(RadarState(), DashcamStatus.Ok, batteryLow = true),
        )
        val s = state(Vehicle(id = 1, distanceM = 15, speedMs = -5f))
        assertEquals(
            "Bike radar overlay. 1 vehicle, nearest 15 metres. Dashcam not found. Low battery.",
            summary(s, DashcamStatus.Missing, batteryLow = true),
        )
    }
}
