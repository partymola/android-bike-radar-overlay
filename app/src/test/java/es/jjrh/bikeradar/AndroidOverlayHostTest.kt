// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class AndroidOverlayHostTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val wm = RecordingWindowManager()

    @Test
    fun theWindowIsAnUntouchableTranslucentOverlayClearOfTheCutout() {
        val p = OverlayGeometry(OverlayEdge.RIGHT, 130).layoutParams(3.0f)
        assertEquals(2038, p.type)
        assertEquals(0x8 or 0x10 or 0x100, p.flags)
        assertEquals(-3, p.format)
        assertEquals(0, p.layoutInDisplayCutoutMode)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
        assertEquals(-1, p.height)
    }

    @Test
    fun theParamsFollowTheGeometry() {
        val right = OverlayGeometry(OverlayEdge.RIGHT, 130).layoutParams(3.0f)
        assertEquals(0x35, right.gravity)
        assertEquals(390, right.width)
        assertEquals(-1, right.height)
        val left = OverlayGeometry(OverlayEdge.LEFT, 80).layoutParams(2.625f)
        assertEquals(0x33, left.gravity)
        assertEquals(210, left.width)
        assertEquals(-1, left.height)
    }

    @Test
    fun attachUsesTheHostsGeometry() {
        val host = AndroidOverlayHost(context, OverlayGeometry(OverlayEdge.LEFT, 80), wm)
        val view = host.createView()
        host.attach(view)
        val add = wm.calls.single()
        assertEquals("add", add.op)
        assertSame(view, add.view)
        assertEquals(0x33, add.params.gravity)
        assertEquals(240, add.params.width)
        assertEquals(-1, add.params.height)
    }

    @Test
    fun aGeometryChangeReLaysOutTheAttachedView() {
        val host = AndroidOverlayHost(context, wm = wm)
        val view = host.createView()
        host.attach(view)
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        assertEquals(listOf("add", "update"), wm.calls.map { it.op })
        val update = wm.calls.last()
        assertSame(view, update.view)
        assertEquals(0x33, update.params.gravity)
        assertEquals(240, update.params.width)
        assertEquals(-1, update.params.height)
    }

    @Test
    fun anUnchangedGeometryTouchesNoWindow() {
        val host = AndroidOverlayHost(context, OverlayGeometry(OverlayEdge.LEFT, 80), wm)
        host.attach(host.createView())
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        assertEquals(listOf("add"), wm.calls.map { it.op })
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun theWidthFollowsTheDisplayDensity() {
        val host = AndroidOverlayHost(context, wm = wm)
        host.attach(host.createView())
        assertEquals(260, wm.calls.single().params.width)
    }

    @Test
    fun detachingAnOlderViewKeepsTheNewerOneTracked() {
        val host = AndroidOverlayHost(context, wm = wm)
        val older = host.createView()
        val newer = host.createView()
        host.attach(older)
        host.attach(newer)
        host.detach(older)
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        assertEquals(listOf("add", "add", "remove", "update"), wm.calls.map { it.op })
        assertSame(newer, wm.calls.last().view)
    }

    @Test
    fun aGeometryChangeOnADetachedHostWaitsForTheNextAttach() {
        val host = AndroidOverlayHost(context, wm = wm)
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        assertEquals(emptyList<String>(), wm.calls.map { it.op })
        host.attach(host.createView())
        val add = wm.calls.single()
        assertEquals(0x33, add.params.gravity)
        assertEquals(240, add.params.width)
    }

    @Test
    fun aGeometryChangeAfterDetachTouchesNoWindow() {
        val host = AndroidOverlayHost(context, wm = wm)
        val view = host.createView()
        host.attach(view)
        host.detach(view)
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        assertEquals(listOf("add", "remove"), wm.calls.map { it.op })
    }

    @Test
    fun aConfigurationChangeKeepsTheChangedGeometry() {
        val host = AndroidOverlayHost(context, wm = wm)
        host.attach(host.createView())
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        host.onConfigurationChanged()
        assertEquals(listOf("add", "update", "update"), wm.calls.map { it.op })
        assertEquals(0x33, wm.calls.last().params.gravity)
    }

    @Test
    fun aFailedReLayoutIsContained() {
        val host = AndroidOverlayHost(context, wm = wm)
        host.attach(host.createView())
        wm.failUpdates = true
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        host.onConfigurationChanged()
        assertEquals(listOf("add"), wm.calls.map { it.op })
    }

    @Test
    fun aFailedAttachLeavesNothingToReLayOut() {
        val host = AndroidOverlayHost(context, wm = wm)
        wm.failAdds = true
        assertTrue(host.attach(host.createView()) is WindowManager.BadTokenException)
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        assertEquals(emptyList<String>(), wm.calls.map { it.op })
    }

    @Test
    fun aFailedDetachStillForgetsTheView() {
        val host = AndroidOverlayHost(context, wm = wm)
        val view = host.createView()
        host.attach(view)
        wm.failRemoves = true
        host.detach(view)
        host.setGeometry(OverlayGeometry(OverlayEdge.LEFT, 80))
        assertEquals(listOf("add"), wm.calls.map { it.op })
    }

    private class Call(val op: String, val view: View, val params: WindowManager.LayoutParams)

    private class RecordingWindowManager : WindowManager {
        val calls = mutableListOf<Call>()
        var failAdds = false
        var failUpdates = false
        var failRemoves = false

        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            if (failAdds) throw WindowManager.BadTokenException("permission denied for window type 2038")
            calls += Call("add", view, params as WindowManager.LayoutParams)
        }

        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
            if (failUpdates) throw IllegalArgumentException("View not attached to window manager")
            calls += Call("update", view, params as WindowManager.LayoutParams)
        }

        override fun removeView(view: View) {
            if (failRemoves) throw IllegalArgumentException("View not attached to window manager")
            calls += Call("remove", view, WindowManager.LayoutParams())
        }

        override fun removeViewImmediate(view: View) = removeView(view)

        @Deprecated("Unused by the host")
        override fun getDefaultDisplay(): Display = throw UnsupportedOperationException()
    }
}
