// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.view.ViewGroup
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for [RadarOverlayView]. Each test injects a specific
 * [RadarState] and takes a Paparazzi snapshot via layoutlib — no device
 * or emulator required.
 *
 * First run: `./gradlew recordPaparazziDebug` to record golden images.
 * Subsequent runs: `./gradlew verifyPaparazziDebug` compares against goldens
 * and fails on pixel-level regressions.
 *
 * The view is rendered at its production width (130 dp) and full device
 * height so layout and drawing proportions match the real overlay.
 * Device: PIXEL_9_PRO_XL (1344×2992, xxhdpi) — pixel-identical to Pixel 10 Pro XL.
 */
class RadarOverlayViewTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    private fun overlay(): RadarOverlayView {
        val density = paparazzi.context.resources.displayMetrics.density
        val widthPx = (130 * density).toInt()
        return RadarOverlayView(paparazzi.context).apply {
            layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    @Test
    fun empty() {
        paparazzi.snapshot(overlay())
    }

    @Test
    fun singleVehicleApproaching() {
        paparazzi.snapshot(overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = 12)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                )
            )
        })
    }

    @Test
    fun closeApproach() {
        // Vehicle at 5 m triggers the danger-border highlight.
        paparazzi.snapshot(overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 5, speedMs = 14, lateralPos = 0.1f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                )
            )
            setAlertMaxM(10)
        })
    }

    @Test
    fun multipleVehicles() {
        paparazzi.snapshot(overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(id = 1, distanceM = 35, speedMs = 8,  lateralPos = -0.3f),
                        Vehicle(id = 2, distanceM = 18, speedMs = 11, lateralPos =  0.2f),
                        Vehicle(id = 3, distanceM =  8, speedMs = 15, lateralPos =  0.5f),
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                )
            )
            setAlertMaxM(20)
        })
    }

    @Test
    fun mixedVehicleSizes() {
        paparazzi.snapshot(overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(id = 1, distanceM = 40, speedMs =  6, size = VehicleSize.BIKE),
                        Vehicle(id = 2, distanceM = 22, speedMs = 10, size = VehicleSize.CAR),
                        Vehicle(id = 3, distanceM = 12, speedMs = 14, size = VehicleSize.TRUCK),
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                )
            )
        })
    }

    @Test
    fun alondsideStationary() {
        // Parked car rendered as hollow outline docked to the edge.
        paparazzi.snapshot(overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(
                            id = 1,
                            distanceM = 3,
                            speedMs = 0,
                            lateralPos = 0.9f,
                            isAlongsideStationary = true,
                        )
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 1f,
                )
            )
        })
    }

    @Test
    fun batteryLow() {
        paparazzi.snapshot(overlay().apply {
            setBatteryLow(setOf("rearvue8"), showLabels = true)
        })
    }

    @Test
    fun dashcamMissing() {
        paparazzi.snapshot(overlay().apply {
            setDashcamStatus(DashcamStatus.Missing, "dc1")
        })
    }

    @Test
    fun dashcamDropped() {
        paparazzi.snapshot(overlay().apply {
            setDashcamStatus(DashcamStatus.Dropped, "dc1")
        })
    }

    @Test
    fun scenarioModeLabel() {
        // Non-null scenarioTimeMs triggers the t+… replay label.
        paparazzi.snapshot(overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 25, speedMs = 10)),
                    source = DataSource.V2,
                    scenarioTimeMs = 12_500L,
                    bikeSpeedMs = 5f,
                )
            )
        })
    }

    @Test
    fun alertLineVisible() {
        // Alert threshold line + label visible when alertMaxM < visualMaxM.
        paparazzi.snapshot(overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 30, speedMs = 9)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                )
            )
            setAlertMaxM(20)
            setAdaptiveAlerts(false)
        })
    }
}
