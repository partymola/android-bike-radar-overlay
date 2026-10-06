// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for [RadarOverlayView]. Each test injects a specific
 * [RadarState] and captures the Canvas via Roborazzi (Robolectric Native
 * Graphics) - no device, emulator, or layoutlib, so it runs in cold-cache CI.
 *
 * The view is rendered at its production width (130 dp) and the window's
 * height, which is the strip's length. Fixtures default to a phone in
 * landscape, the way it is mounted on a bike: at the usual view ranges the
 * range axis then has fewer px per metre than the lateral one, which a
 * portrait render cannot show. Portrait renders are kept for the store image
 * and the tall-strip case. Box shapes are asserted in [RadarOverlayDrawnBoxesTest].
 * Verify with `:app:verifyRoborazziDebug`; regenerate with
 * `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 395 dp wide is the short side of a mounted phone, which a landscape strip
// runs along.
@Config(qualifiers = "w395dp-h997dp-xxhdpi")
class RadarOverlayViewTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A measured + laid-out overlay at production width (130 dp), as tall
     *  as the screen's short side (landscape) or long side ([portrait]).
     *  Roborazzi draws the view as-is, so it must be sized before capture. */
    private fun overlay(portrait: Boolean = false): RadarOverlayView {
        val metrics = context.resources.displayMetrics
        val widthPx = (130 * metrics.density).toInt()
        val heightPx = if (portrait) metrics.heightPixels else metrics.widthPixels
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
                    vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = -12f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun rangeOnlySourceIsColouredByRange() {
        // The overlay's own call site for the source-aware colour choice, which
        // no other golden reaches. On this source every closing speed is the
        // fail-closed zero, so a speed-banded colour would paint a car bearing
        // down at 5 m in the calm colour all the way in. Scored on range it
        // renders in the danger colour, which is a quantity the radar actually
        // measured and matches what the rider is hearing.
        //
        // The full-screen danger BORDER is deliberately absent here: it stays
        // keyed on closing speed, so it can never fire on this source. Read
        // RadarOverlayView's comment before "fixing" that.
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 5, speedMs = 0f, lateralUnknown = true)),
                    source = DataSource.V1,
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
                    vehicles = listOf(Vehicle(id = 1, distanceM = 5, speedMs = -14f, lateralPos = 0.1f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
            setAlertMaxM(10)
        }.capture()
    }

    @Test
    fun fastRecederDrawsNoDangerBorder() {
        // A car pulling AWAY at 14 m/s. Nothing in the overlay may treat it as
        // a threat: no red border, no amber box. Before the sign fix this was
        // the only shape that COULD raise the border, and no fixture contained
        // one, which is why the inversion survived every golden.
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 15, speedMs = 14f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun fastCloserBeyondTheVisualWindowDrawsNoBorder() {
        // Closing hard, but past visualMaxM, so the strip paints no box for it.
        // The border must agree with the strip: a full-screen red alarm over an
        // empty panel is worse than no alarm.
        overlay().apply {
            setVisualMaxM(20)
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 60, speedMs = -16f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun aTargetBehindTheRiderIsNeitherDrawnNorBordered() {
        // No overlay fixture set isBehind before this, so dropping the check
        // was an unkilled mutation on both surfaces. A car already past the
        // rider closing hard must produce an empty strip and no border.
        overlay().apply {
            setVisualMaxM(20)
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 10, speedMs = -16f, isBehind = true)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun precogTargetPredictedPastTheRiderDrawsNoBorder() {
        // With precog on, the strip works in PREDICTED range and drops a
        // target predicted to have passed the rider. At 8 m closing 16 m/s
        // the one-second prediction is behind the rider, so the strip paints
        // nothing, and a border gated on the measured 8 m would alarm the
        // whole screen over an empty panel. No golden set precog before this,
        // so the two filters could disagree unnoticed.
        overlay().apply {
            setVisualMaxM(20)
            setPrecog(true)
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 8, speedMs = -16f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun precogTargetPredictedIntoTheWindowDrawsItsBorder() {
        // The other half: measured 60 m is outside a 20 m window, but the
        // predicted 44 m is inside a 50 m one, so the strip draws it and the
        // border must agree. A border reading measured distance would stay
        // dark over a red box.
        overlay().apply {
            setVisualMaxM(50)
            setPrecog(true)
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 60, speedMs = -16f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun multipleVehicles() {
        // Portrait: this golden is copied as the README and store image.
        overlay(portrait = true).apply {
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(id = 1, distanceM = 35, speedMs = -8f, lateralPos = -0.3f, templateLengthM = 4f, templateWidthM = 1.75f),
                        Vehicle(id = 2, distanceM = 18, speedMs = -11f, lateralPos = 0.2f, templateLengthM = 4f, templateWidthM = 1.75f),
                        Vehicle(id = 3, distanceM = 8, speedMs = -15f, lateralPos = 0.5f, templateLengthM = 4f, templateWidthM = 1.75f),
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
                        Vehicle(id = 1, distanceM = 40, speedMs = -6f, size = VehicleSize.CAR),
                        Vehicle(id = 2, distanceM = 22, speedMs = -10f, size = VehicleSize.CAR),
                        Vehicle(id = 3, distanceM = 12, speedMs = -14f, size = VehicleSize.TRUCK, templateLengthM = 15f, templateWidthM = 2.25f),
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun sizedTargetsAreDrawnToScaleFromTheirFront() {
        // Locked tracks at 3, 15 and 48 m carry the car template and draw as
        // 4 x 1.75 m boxes centred on their range; the TRUCK-class track at
        // 40 m carries the car template too, as most of that class does, and
        // draws car-sized; the long template at 32 m draws 15 m long; the 2 m
        // template at 9 m draws short and narrow; the unlocked track at 22 m
        // draws as a car running back from its range. Rider's 55 m window and
        // 30 m alert line. Portrait, where the range scale is fine enough
        // that cars draw above the size floors.
        overlay(portrait = true).apply {
            setVisualMaxM(55)
            setAlertMaxM(30)
            setState(sizedTargets)
        }.capture()
    }

    @Test
    fun sizedTargetsOnALandscapeStrip() {
        overlay().apply {
            setVisualMaxM(55)
            setAlertMaxM(30)
            setState(sizedTargets)
        }.capture()
    }

    private val sizedTargets = RadarState(
        vehicles = listOf(
            Vehicle(id = 1, distanceM = 3, speedMs = 0f, lateralPos = 0.3f, templateLengthM = 4f, templateWidthM = 1.75f),
            Vehicle(id = 6, distanceM = 9, speedMs = -2f, lateralPos = -0.6f, templateLengthM = 2f, templateWidthM = 1f),
            Vehicle(id = 2, distanceM = 15, speedMs = -4f, lateralPos = -0.2f, templateLengthM = 4f, templateWidthM = 1.75f),
            Vehicle(id = 3, distanceM = 22, speedMs = -6f, lateralPos = -0.4f),
            Vehicle(id = 4, distanceM = 32, speedMs = -7f, lateralPos = 0.7f, size = VehicleSize.TRUCK, templateLengthM = 15f, templateWidthM = 2.25f),
            Vehicle(id = 7, distanceM = 40, speedMs = -5f, lateralPos = -0.5f, size = VehicleSize.TRUCK, templateLengthM = 4f, templateWidthM = 1.75f),
            Vehicle(id = 5, distanceM = 48, speedMs = -8f, lateralPos = 0.2f, templateLengthM = 4f, templateWidthM = 1.75f),
        ),
        source = DataSource.V2,
        bikeSpeedMs = 5f,
    )

    @Test
    fun theNearerTargetIsDrawnOverALongerOneBehindIt() {
        // Same lane: a car at 10 m (8-12 m) and a 15 m template at 17 m
        // (9.5-24.5 m). The car's red box must sit on top of the truck's.
        overlay().apply {
            setVisualMaxM(55)
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(id = 1, distanceM = 10, speedMs = -15f, templateLengthM = 4f, templateWidthM = 1.75f),
                        Vehicle(id = 2, distanceM = 17, speedMs = -2f, size = VehicleSize.TRUCK, templateLengthM = 15f, templateWidthM = 2.25f),
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun underPrecogTheOrderFollowsTheDrawnRange() {
        // Measured, the long template at 12 m is nearer than the car at 20 m.
        // Predicted one second on, the car closing at 15 m/s draws at 3-7 m,
        // inside the long box (4.5-19.5 m), so the car must be painted on top.
        overlay().apply {
            setVisualMaxM(55)
            setPrecog(true)
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(id = 1, distanceM = 20, speedMs = -15f, templateLengthM = 4f, templateWidthM = 1.75f),
                        Vehicle(id = 2, distanceM = 12, speedMs = 0f, size = VehicleSize.TRUCK, templateLengthM = 15f, templateWidthM = 2.25f),
                    ),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
        }.capture()
    }

    @Test
    fun theSizeFloorsHoldWhereTheScaleWouldShrinkABox() {
        // At an 80 m window a 2 x 1 m template on a TRUCK-class track draws
        // smaller than the TRUCK floor on both axes; an unlocked track at 79 m
        // has its far edge clamped at the strip's end, so the CAR height floor
        // sets its box; and one at exactly 80 m would be pushed off the view
        // by that floor, so the bottom cap holds it.
        overlay().apply {
            setVisualMaxM(80)
            setState(
                RadarState(
                    vehicles = listOf(
                        Vehicle(id = 1, distanceM = 50, speedMs = -4f, size = VehicleSize.TRUCK, templateLengthM = 2f, templateWidthM = 1f),
                        Vehicle(id = 2, distanceM = 80, speedMs = -4f, lateralPos = -0.5f),
                        Vehicle(id = 3, distanceM = 79, speedMs = -4f, lateralPos = 0.5f),
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
    fun noRadarThisRide() {
        // A ride the radar never joined: not "disconnected", and the line asks
        // whether it is on.
        overlay().apply {
            setReconnecting(RadarLinkVisualDecider.LinkVisual.NO_RADAR)
        }.capture()
    }

    @Test
    fun theNoRadarBannerIsSpokenAsAQuestion() {
        // The question carries its own mark, so no full stop after it.
        val view = overlay().apply { setReconnecting(RadarLinkVisualDecider.LinkVisual.NO_RADAR) }
        assertEquals("No rear radar. Is it on?", view.contentDescription)
    }

    @Test
    @Config(qualifiers = "+es")
    fun noRadarThisRideEs() {
        overlay().apply {
            setReconnecting(RadarLinkVisualDecider.LinkVisual.NO_RADAR)
        }.capture()
    }

    @Test
    fun scenarioModeLabel() {
        // Non-null scenarioTimeMs triggers the t+... replay label.
        overlay().apply {
            setState(
                RadarState(
                    vehicles = listOf(Vehicle(id = 1, distanceM = 25, speedMs = -10f)),
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
                    vehicles = listOf(Vehicle(id = 1, distanceM = 30, speedMs = -9f)),
                    source = DataSource.V2,
                    bikeSpeedMs = 5f,
                ),
            )
            setAlertMaxM(20)
            setAdaptiveAlerts(false)
        }.capture()
    }
}
