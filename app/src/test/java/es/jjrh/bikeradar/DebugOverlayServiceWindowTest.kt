// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.content.res.Configuration
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class DebugOverlayServiceWindowTest {

    @Test
    fun theDebugOverlayUsesTheSharedWindowAndRemovesItOnStop() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(DebugOverlayService::class.java).create()
        val wm = controller.get().getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val shadow = Shadow.extract<ShadowWindowManagerImpl>(wm)
        val params = shadow.views.single().layoutParams as WindowManager.LayoutParams
        assertEquals(0x35, params.gravity)
        assertEquals(390, params.width)
        assertEquals(-1, params.height)
        assertEquals(2038, params.type)

        controller.destroy()
        assertTrue(shadow.views.isEmpty())
    }

    @Test
    fun aConfigurationChangeReLaysOutTheDebugOverlay() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(DebugOverlayService::class.java).create()
        val wm = controller.get().getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = Shadow.extract<ShadowWindowManagerImpl>(wm).views.single()
        val before = view.layoutParams

        controller.get().onConfigurationChanged(Configuration(controller.get().resources.configuration))

        assertNotSame(before, view.layoutParams)
        assertEquals(0x35, (view.layoutParams as WindowManager.LayoutParams).gravity)
        controller.destroy()
    }
}
