// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where the ride service puts its two overlay windows, the strip and the
 * dead-radar banner, for the rider's overlay settings and after a
 * configuration change. Each test attaches a view to both hosts itself,
 * since no radar links here.
 *
 * The service is created by calling onCreate directly rather than through the
 * controller, which idles the main looper: that would let the settings
 * collector correct a window opened in the wrong place before the test looks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class OverlayGeometryServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private var service: BikeRadarService? = null

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        prefs = Prefs(app)
    }

    @After
    fun tearDown() {
        service?.onDestroy()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        BikeRadarService.radarLinkStateForUi = null
    }

    private class Windows(val strip: View, val banner: View)

    private fun start(): Windows {
        val s = Robolectric.buildService(BikeRadarService::class.java).get()
        service = s
        s.onCreate()
        val strip = s.overlayHost.createView().also { assertNull(s.overlayHost.attach(it)) }
        val banner = s.reconnectHost.createView().also { assertNull(s.reconnectHost.attach(it)) }
        return Windows(strip, banner)
    }

    private fun View.params() = layoutParams as WindowManager.LayoutParams

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(what, condition())
    }

    @Test
    fun bothWindowsOpenWhereTheRiderLeftThem() {
        prefs.overlayOnLeft = true
        prefs.overlayNarrow = true
        val w = start()
        assertEquals(0x33, w.strip.params().gravity)
        assertEquals(240, w.strip.params().width)
        assertEquals(0x33, w.banner.params().gravity)
        assertEquals(390, w.banner.params().width)
    }

    @Test
    fun aNewRiderGetsBothWindowsWideOnTheRight() {
        val w = start()
        assertEquals(0x35, w.strip.params().gravity)
        assertEquals(390, w.strip.params().width)
        assertEquals(0x35, w.banner.params().gravity)
        assertEquals(390, w.banner.params().width)
    }

    @Test
    fun movingTheStripLeftMovesBothWindows() {
        val w = start()
        prefs.overlayOnLeft = true
        waitFor("the strip never moved left") { w.strip.params().gravity == 0x33 }
        waitFor("the banner never moved left") { w.banner.params().gravity == 0x33 }
        assertEquals(390, w.strip.params().width)
        assertEquals(390, w.banner.params().width)

        prefs.overlayOnLeft = false
        waitFor("the strip never moved back") { w.strip.params().gravity == 0x35 }
        waitFor("the banner never moved back") { w.banner.params().gravity == 0x35 }
    }

    @Test
    fun narrowingTheStripLeavesTheBannerAlone() {
        val w = start()
        prefs.overlayNarrow = true
        waitFor("the strip never narrowed") { w.strip.params().width == 240 }
        // A later change the banner does follow, so its width is read after
        // its own relayout has run.
        prefs.overlayOnLeft = true
        waitFor("the banner never moved left") { w.banner.params().gravity == 0x33 }
        assertEquals(390, w.banner.params().width)
        assertEquals(240, w.strip.params().width)
    }

    @Test
    fun aDensityChangeReLaysOutBothWindowsAtTheirOwnWidths() {
        prefs.overlayNarrow = true
        val w = start()
        assertEquals(240, w.strip.params().width)
        assertEquals(390, w.banner.params().width)
        RuntimeEnvironment.setQualifiers("+xhdpi")
        service!!.onConfigurationChanged(Configuration(app.resources.configuration))
        assertEquals(160, w.strip.params().width)
        assertEquals(260, w.banner.params().width)
    }
}
