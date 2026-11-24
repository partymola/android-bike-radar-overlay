// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.RadarLinkVisualDecider.LinkVisual
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/**
 * The ride service's banner reads the radar's stream from its own
 * coordinator. The service is created by calling onCreate directly, so the
 * coordinator's own posts stay queued until a test idles the main looper.
 */
@RunWith(RobolectricTestRunner::class)
class ReconnectBannerServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var service: BikeRadarService? = null

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        service?.onDestroy()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        BikeRadarService.radarLinkStateForUi = null
    }

    @Test
    fun theBannerStaysDownOnceTheRadarStreamsAndGoesWithTheService() {
        val s = Robolectric.buildService(BikeRadarService::class.java).get()
        service = s
        s.onCreate()
        val windows = Shadow.extract<ShadowWindowManagerImpl>(s.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        s.reconnectBanner.show(LinkVisual.RECONNECTING_PLAIN)
        assertEquals(1, windows.views.size)
        s.radarLinkCoordinator.markConnected()
        s.reconnectBanner.show(LinkVisual.RECONNECTING_PLAIN)
        assertTrue(windows.views.isEmpty())

        s.radarLinkCoordinator.markDisconnected()
        s.reconnectBanner.show(LinkVisual.RECONNECTING_PLAIN)
        assertEquals(1, windows.views.size)
        s.onDestroy()
        service = null
        assertTrue(windows.views.isEmpty())
    }

    @Test
    fun theRadarsFirstFrameTakesTheBannerDownThroughTheMainThread() {
        val s = Robolectric.buildService(BikeRadarService::class.java).get()
        service = s
        s.onCreate()
        val windows = Shadow.extract<ShadowWindowManagerImpl>(s.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        s.reconnectBanner.show(LinkVisual.RECONNECTING_PLAIN)
        assertEquals(1, windows.views.size)
        s.radarLinkCoordinator.markConnected()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(windows.views.isEmpty())
    }

    @Test
    fun aDropPastTheBannerThresholdPutsItUpThroughTheMainThread() {
        RadarStateBus.clear()
        val s = Robolectric.buildService(BikeRadarService::class.java).get()
        service = s
        s.onCreate()
        val windows = Shadow.extract<ShadowWindowManagerImpl>(s.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        // Traffic seen, which the collector records on the service's IO scope.
        RadarStateBus.publish(RadarState(vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = 0f)), source = DataSource.V1))
        val trackDeadline = System.currentTimeMillis() + 5_000L
        while (s.lastTrackActivityMs == null && System.currentTimeMillis() < trackDeadline) Thread.sleep(10)
        assertTrue("traffic reached the service", s.lastTrackActivityMs != null)
        s.radarLinkCoordinator.markConnected()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        s.radarLinkCoordinator.markDisconnected()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        // Only the service's own tick decides: it runs on a real 2 s cadence
        // during a drop, and its posts reach Main in the order it made them.
        val bannerDeadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < bannerDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (windows.views.isNotEmpty()) break
            Thread.sleep(20)
        }
        assertEquals(1, windows.views.size)
    }
}
