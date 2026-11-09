// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The camera switch-off's wiring in the real [BikeRadarService]: the camera
 * link reports to the service's own coordinator, the service's tick acts on
 * it, and a camera link that is only reconnecting does not stamp the camera
 * seen. The behaviour itself is [DashcamSwitchOffTest]'s and
 * [CameraLightLinkControllerHarnessTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
class DashcamSwitchOffServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        BatteryStateBus.clearForTest()
        Prefs(app).apply {
            dashcamOwnership = DashcamOwnership.YES
            dashcamMac = CAM
            dashcamDisplayName = "Cam"
            autoLightModeEnabled = true
        }
    }

    @After
    fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        BikeRadarService.radarLinkStateForUi = null
        BatteryStateBus.clearForTest()
    }

    @Test
    fun theCameraLinkReportsToTheServicesCoordinator() {
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        // A gateway that is not the coordinator would compile and report into
        // nothing.
        assertSame(service.radarLinkCoordinator, service.cameraLink.dashcamLink)

        // A ride ends with the radar dropping; the switch-off then reaches the
        // service's own tick, which goes BLANK and frees the ride wakelock.
        val coordinator = service.radarLinkCoordinator
        // Moving 60 s before the drop: the ride wakelock is held and the
        // alarm, outside the 30 s riding gate, arms.
        service.lastRidingActivityMs = android.os.SystemClock.elapsedRealtime() - 60_000L
        coordinator.markConnected()
        coordinator.markDisconnected()
        assertTrue(service.rideWakeLock.isHeld())
        coordinator.markDashcamSwitchedOff(CAM)
        // The loop's sleep is in real time; the stale window it would
        // otherwise wait for is 20 s.
        val deadline = System.currentTimeMillis() + 5_000L
        while (service.rideWakeLock.isHeld() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse("the tick never acted on the switch-off", service.rideWakeLock.isHeld())
        assertFalse(coordinator.snapshot().walkAwayArmed)
        controller.destroy()
    }

    @Test
    fun aCameraLinkThatIsOnlyReconnectingDoesNotStampTheCameraSeen() {
        // Bluetooth coming back on replays the cached camera into a link loop
        // that has not reached it: a camera switched off must stay unheard.
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        val heardAt = android.os.SystemClock.elapsedRealtime() - 60_000L
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80, readAtMs = 0L, lastSeenElapsedMs = heardAt))
        // Read just now, so a sighting that fell through to the throttle
        // would stamp the camera there instead.
        app.getSharedPreferences(PREFS_THROTTLE, android.content.Context.MODE_PRIVATE).edit()
            .putLong("${KEY_LAST_TS}_cam", System.currentTimeMillis()).commit()
        fun sighting(id: Int) = service.onStartCommand(
            Intent(app, BikeRadarService::class.java)
                .setAction(BikeRadarService.ACTION_READ_DEVICE)
                .putExtra(BikeRadarService.EXTRA_NAME, "Cam")
                .putExtra(BikeRadarService.EXTRA_MAC, CAM),
            0,
            id,
        )
        sighting(1)
        assertTrue("the first sighting starts the link loop", service.cameraLink.isActive())
        assertFalse(service.cameraLink.isGattActive())
        sighting(2)
        assertEquals(heardAt, BatteryStateBus.entries.value["cam"]?.lastSeenElapsedMs)
        controller.destroy()
    }

    @Test
    fun withTheAutoSwitchOffASightingStillStampsTheCameraSeen() {
        // No camera light link runs, so the sighting goes to the throttle,
        // which stamps the camera seen.
        Prefs(app).autoLightModeEnabled = false
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        val heardAt = android.os.SystemClock.elapsedRealtime() - 60_000L
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 80, readAtMs = 0L, lastSeenElapsedMs = heardAt))
        app.getSharedPreferences(PREFS_THROTTLE, android.content.Context.MODE_PRIVATE).edit()
            .putLong("${KEY_LAST_TS}_cam", System.currentTimeMillis()).commit()
        service.onStartCommand(
            Intent(app, BikeRadarService::class.java)
                .setAction(BikeRadarService.ACTION_READ_DEVICE)
                .putExtra(BikeRadarService.EXTRA_NAME, "Cam")
                .putExtra(BikeRadarService.EXTRA_MAC, CAM),
            0,
            1,
        )
        assertFalse(service.cameraLink.isActive())
        assertTrue(BatteryStateBus.entries.value["cam"]!!.lastSeenElapsedMs > heardAt)
        controller.destroy()
    }

    private companion object {
        const val CAM = "CC:CC:CC:CC:CC:01"
    }
}
