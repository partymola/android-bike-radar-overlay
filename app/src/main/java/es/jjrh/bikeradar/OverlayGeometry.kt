// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

internal enum class OverlayEdge { LEFT, RIGHT }

/** Which screen edge the overlay window sits on, and how wide it is. */
internal data class OverlayGeometry(
    val edge: OverlayEdge = OverlayEdge.RIGHT,
    val widthDp: Int = 130,
) {
    // Physical LEFT/RIGHT, never START/END: the setting names a physical edge.
    val gravity: Int
        get() = Gravity.TOP or when (edge) {
            OverlayEdge.LEFT -> Gravity.LEFT
            OverlayEdge.RIGHT -> Gravity.RIGHT
        }

    fun widthPx(density: Float): Int = (widthDp * density).toInt()

    fun layoutParams(density: Float): WindowManager.LayoutParams = WindowManager.LayoutParams(
        widthPx(density),
        // Not the display height: the view would be measured at it while the frame stops at the navigation bar,
        // drawing its bottom off-screen. AndroidOverlayHostTest pins the -1; only a device shows the clipping.
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).also {
        it.gravity = gravity
        it.x = 0
        it.y = 0
        // DEFAULT, not SHORT_EDGES: the strip is meant to sit off a camera cutout, not under it.
        it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
    }
}
