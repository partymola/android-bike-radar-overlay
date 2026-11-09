// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.os.SystemClock
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The dashcam walk-away alarm's wiring in the real [BikeRadarService]: its
 * wakelock and the cap it is taken with, and where it learns that the camera
 * answered. The behaviour is [DashcamHeardWindowTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
class DashcamAlarmServiceTest {

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
        }
    }

    @After
    fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        BikeRadarService.radarLinkStateForUi = null
        BatteryStateBus.clearForTest()
    }

    @Test
    fun anArmedDropHoldsTheWalkAwayWakeLock() {
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        val coordinator = service.radarLinkCoordinator
        coordinator.markConnected()
        // Past the alarm's cold start, so it can sound for this drop.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertFalse(service.walkAwayWakeLock.isHeld())
        coordinator.markDisconnected()
        assertTrue(coordinator.snapshot().walkAwayArmed)
        assertTrue(service.walkAwayWakeLock.isHeld())
        // The default 30 s wait plus 15 s.
        assertEquals(45_000L, service.walkAwayWakeLock.lastCapMs)
        coordinator.markConnected()
        assertFalse("the radar back releases it", service.walkAwayWakeLock.isHeld())
        coordinator.markDisconnected()
        assertTrue(service.walkAwayWakeLock.isHeld())
        controller.destroy()
        assertFalse("and the service stopping", service.walkAwayWakeLock.isHeld())
    }

    @Test
    fun theAlarmHearsTheAnswersTheServiceRecords() {
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        val now = SystemClock.elapsedRealtime()
        // The map the reader writes is the one the alarm reads.
        assertSame(service.dashcamAnsweredMs, service.batteryReader.dashcamAnsweredAtMs)
        // And the reader publishes only a change, not every armed read.
        assertEquals(service.haPublisher::publishBatteryIfChanged, service.batteryReader.publishBattery)
        // And the camera's answer reaches the walk-away tick.
        assertEquals(service.radarLinkCoordinator::onDashcamAnswered, service.batteryReader.onDashcamAnswered)
        service.dashcamAnsweredMs[CAM] = now - 3_000L
        assertEquals(now - 3_000L, service.radarLinkCoordinator.dashcamHeardAtMs(now))
        controller.destroy()
    }

    private companion object {
        const val CAM = "CC:CC:CC:CC:CC:01"
    }
}
