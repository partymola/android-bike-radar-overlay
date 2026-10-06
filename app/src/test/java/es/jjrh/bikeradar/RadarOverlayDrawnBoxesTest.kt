// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The boxes [RadarOverlayView] actually draws, measured off the canvas rather
 * than through the render math, across both strip orientations, every window
 * length and every range inside it, for each template the radar sends.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 395 dp wide is the short side of a mounted phone, which a landscape strip
// runs along.
@Config(qualifiers = "w395dp-h997dp-xxhdpi")
class RadarOverlayDrawnBoxesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // One per view, reused for every draw: a fresh bitmap per draw, even
    // recycled, takes this class's test JVM past 13 GB.
    private class BoxRecorder(private val bmp: Bitmap) : Canvas(bmp) {
        val rects = mutableListOf<RectF>()
        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            rects += RectF(rect)
            super.drawRoundRect(rect, rx, ry, paint)
        }
        fun clear() {
            rects.clear()
            bmp.eraseColor(0)
        }
    }

    private fun RadarOverlayView.recorder() = BoxRecorder(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))

    private data class Target(val name: String, val vehicle: (distanceM: Int, lateralPos: Float) -> Vehicle)

    private val targets = listOf(
        Target("unlocked car") { d, x -> Vehicle(id = 1, distanceM = d, speedMs = -5f, lateralPos = x) },
        Target("unlocked truck") { d, x -> Vehicle(id = 1, distanceM = d, speedMs = -5f, lateralPos = x, size = VehicleSize.TRUCK) },
        Target("4 x 1.75") { d, x -> Vehicle(id = 1, distanceM = d, speedMs = -5f, lateralPos = x, templateLengthM = 4f, templateWidthM = 1.75f) },
        Target("15 x 2.25") { d, x -> Vehicle(id = 1, distanceM = d, speedMs = -5f, lateralPos = x, size = VehicleSize.TRUCK, templateLengthM = 15f, templateWidthM = 2.25f) },
        Target("2 x 1") { d, x -> Vehicle(id = 1, distanceM = d, speedMs = -5f, lateralPos = x, templateLengthM = 2f, templateWidthM = 1f) },
        Target("truck class, car template") { d, x -> Vehicle(id = 1, distanceM = d, speedMs = -5f, lateralPos = x, size = VehicleSize.TRUCK, templateLengthM = 4f, templateWidthM = 1.75f) },
        Target("parked alongside") { d, x -> Vehicle(id = 1, distanceM = d, speedMs = 0f, lateralPos = x, isAlongsideStationary = true) },
    )

    private val garbageWidth = Target("width byte 0xFF") { d, x ->
        Vehicle(id = 1, distanceM = d, speedMs = -5f, lateralPos = x, templateLengthM = 4f, templateWidthM = 63.75f)
    }

    private fun overlay(portrait: Boolean): RadarOverlayView {
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

    /** The boxes [view] draws for [vehicle] alone. The background and the
     *  danger border span the view, so they are left out. */
    private fun RadarOverlayView.boxesFor(vehicle: Vehicle, canvas: BoxRecorder): List<RectF> {
        setState(RadarState(vehicles = listOf(vehicle), source = DataSource.V2, bikeSpeedMs = 1f))
        canvas.clear()
        draw(canvas)
        return canvas.rects.filter { it.width() < width - 4 * resources.displayMetrics.density }
    }

    private fun boxesDrawn(portrait: Boolean, check: (Target, String, RectF, Float) -> Unit) {
        val view = overlay(portrait)
        val canvas = view.recorder()
        for (window in listOf(10, 15, 20, 30, 55, 80)) {
            view.setVisualMaxM(window)
            for (target in targets + garbageWidth) {
                for (distance in (0..window step 3) + window) {
                    for (lateral in listOf(0f, 1f)) {
                        val boxes = view.boxesFor(target.vehicle(distance, lateral), canvas)
                        val where = "${target.name} at $distance m of $window, lateral $lateral, ${if (portrait) "portrait" else "landscape"}"
                        assertTrue("$where: nothing drawn", boxes.isNotEmpty())
                        boxes.forEach { check(target, where, it, view.width.toFloat()) }
                    }
                }
            }
        }
    }

    private fun assertLongerThanWide(where: String, box: RectF) = assertTrue("$where: ${box.width()} wide, ${box.height()} long", box.width() <= box.height() + 0.01f)

    @Test
    fun onALandscapeStripNoBoxIsWiderThanItIsLong() = boxesDrawn(portrait = false) { _, where, box, _ -> assertLongerThanWide(where, box) }

    @Test
    fun onAPortraitStripNoBoxIsWiderThanItIsLong() = boxesDrawn(portrait = true) { _, where, box, _ -> assertLongerThanWide(where, box) }

    @Test
    fun noRealTemplateDrawsPastTheStrip() {
        // The rect only: the 2.5 dp stroke of a 15 x 2.25 m box at the edge
        // already clips by about 1 dp.
        for (portrait in listOf(false, true)) {
            boxesDrawn(portrait) { target, where, box, width ->
                if (target != garbageWidth) {
                    assertTrue("$where: ${box.left}..${box.right} of $width", box.left >= 0f && box.right <= width)
                }
            }
        }
    }

    @Test
    fun aCarOnALandscapeStripIsDrawnCarShaped() {
        // A width on the lateral scale would draw this car square: the length
        // cap alone cannot tell that from the right shape.
        val view = overlay(portrait = false).apply { setVisualMaxM(55) }
        val car = Vehicle(id = 1, distanceM = 30, speedMs = -5f, templateLengthM = 4f, templateWidthM = 1.75f)
        view.boxesFor(car, view.recorder()).forEach { box ->
            assertTrue("${box.width()} wide, ${box.height()} long", box.width() < 0.6f * box.height())
        }
    }
}
