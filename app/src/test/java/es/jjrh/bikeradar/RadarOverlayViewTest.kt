// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for [RadarOverlayView]. Each test injects a specific
 * [RadarState] and captures the Canvas via Roborazzi (Robolectric Native
 * Graphics) - no device, emulator, or layoutlib, so it runs in cold-cache CI.
 *
 * The view is rendered at its production width (130 dp) and full device
 * height so layout and drawing proportions match the real overlay.
 * Verify with `:app:verifyRoborazziDebug`; regenerate with
 * `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class RadarOverlayViewTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A measured + laid-out overlay at production width (130 dp) and full
     *  device height. Roborazzi draws the view as-is, so it must be sized
     *  before capture. */
    private fun overlay(): RadarOverlayView {
        val metrics = context.resources.displayMetrics
        val widthPx = (130 * metrics.density).toInt()
        val heightPx = metrics.heightPixels
        return RadarOverlayView(context).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, widthPx, heightPx)
        }
    }

    /** Roborazzi's View.captureRoboImage() requires an Activity-attached view;
     *  this overlay is detached, so draw it to a bitmap (Robolectric Native
     *  Graphics) and capture that. Auto-names the golden from the test method. */
    private fun RadarOverlayView.capture() {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        bmp.captureRoboImage()
    }

    @Test
    fun empty() {
        overlay().capture()
    }

    @Test
    fun singleVehicleApproaching() {
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = 12f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun closeApproach() {
        // Vehicle at 5 m triggers the danger-border highlight.
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 5, speedMs = 14f, lateralPos = 0.1f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
            setAlertMaxM(10)
        }.capture()
    }

    @Test
    fun multipleVehicles() {
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(id = 1, distanceM = 35, speedMs = 8f, lateralPos = -0.3f),
                        Vehicle(id = 2, distanceM = 18, speedMs = 11f, lateralPos = 0.2f),
                        Vehicle(id = 3, distanceM = 8, speedMs = 15f, lateralPos = 0.5f),
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
            setAlertMaxM(20)
        }.capture()
    }

    @Test
    fun mixedVehicleSizes() {
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(id = 1, distanceM = 40, speedMs = 6f, size = VehicleSize.CAR),
                        Vehicle(id = 2, distanceM = 22, speedMs = 10f, size = VehicleSize.CAR),
                        Vehicle(id = 3, distanceM = 12, speedMs = 14f, size = VehicleSize.TRUCK),
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun alondsideStationary() {
        // Parked car rendered as hollow outline docked to the edge.
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(
                            id = 1,
                            distanceM = 3,
                            speedMs = 0f,
                            lateralPos = 0.9f,
                            isAlongsideStationary = true,
                        ),
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 1f,
                ),
            )
        }.capture()
    }

    @Test
    fun batteryLow() {
        overlay().apply {
            setBatteryLow(setOf("rearvue8"), showLabels = true)
        }.capture()
    }

    @Test
    fun dashcamMissing() {
        overlay().apply {
            setDashcamStatus(DashcamStatus.Missing, "dc1")
        }.capture()
    }

    @Test
    fun dashcamDropped() {
        overlay().apply {
            setDashcamStatus(DashcamStatus.Dropped, "dc1")
        }.capture()
    }

    @Test
    fun reconnecting() {
        // Rear-radar link down past the visual threshold, radar-only rider: the
        // overlay shows only the dead-radar banner (title alone), no radar canvas.
        overlay().apply {
            setReconnecting(RadarLinkVisualDecider.LinkVisual.RECONNECTING_PLAIN)
        }.capture()
    }

    @Test
    fun reconnectingUnlocked() {
        // eBike rider, bike still unlocked: the banner adds the "...but bike
        // unlocked" line (also a forgot-to-lock hint).
        overlay().apply {
            setReconnecting(RadarLinkVisualDecider.LinkVisual.RECONNECTING_UNLOCKED)
        }.capture()
    }

    @Test
    fun scenarioModeLabel() {
        // Non-null scenarioTimeMs triggers the t+... replay label.
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 25, speedMs = 10f)),
                    source = DataSource.V2,
                    scenarioTimeMs = 12_500L,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun alertLineVisible() {
        // Alert threshold line + label visible when alertMaxM < visualMaxM.
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 30, speedMs = 9f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
            setAlertMaxM(20)
            setAdaptiveAlerts(false)
        }.capture()
    }
}
