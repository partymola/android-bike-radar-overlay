// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The dashcam light failure alert's wiring in the real [BikeRadarService]: the
 * camera link waits on the rides' own cues, and a stop takes the entry out of
 * the shade. The behaviour itself is [CameraLightLinkControllerHarnessTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
class DashcamLightFailureServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After
    fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        BikeRadarService.radarLinkStateForUi = null
    }

    @Test
    fun theDashcamLightFailureDoesNotOutliveTheService() {
        // Left in the shade after a stop, it would speak for a light nothing
        // watches any more.
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        fun lightFailurePosted() = shadowOf(nm).allNotifications.any { it.channelId == "bike_radar_light_fail" }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        ServiceNotifications(app) { Prefs(app) }.postLightFail("Day Flash", silent = true)
        assertTrue(lightFailurePosted())
        controller.destroy()
        assertFalse("a stopped service must take the entry out of the shade", lightFailurePosted())
    }

    @Test
    fun theDashcamLinkWaitsOnTheCuesOfTheRidesBeeper() {
        // A link wired to any other flow would sound its alert over a cue.
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        try {
            assertSame(requireNotNull(service.alertBeeper).cueSounding, service.cameraLink.cueSounding)
        } finally {
            controller.destroy()
        }
    }
}
