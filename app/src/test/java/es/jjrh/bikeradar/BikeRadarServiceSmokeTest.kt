// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
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

/**
 * Smoke tests for [BikeRadarService] lifecycle entrypoints under
 * Robolectric. Exercises the synchronous portion of onCreate
 * (notification channel, startForeground, Prefs / HaCredentials init,
 * capture-log prune, BroadcastReceiver registration, coroutine launches)
 * and the action dispatch in onStartCommand.
 *
 * What this does NOT cover: the BLE-touching paths under registerEventScan
 * and the GATT plumbing both bail out early in Robolectric because the
 * shadow BluetoothLeScanner is null. Live boot is the only place those
 * code paths execute.
 */
@RunWith(RobolectricTestRunner::class)
class BikeRadarServiceSmokeTest {

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
    fun restoreCryptorFactory() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    @Test
    fun onCreateRegistersNotificationChannel() {
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        // The FGS notification needs a channel on Android 8+ or it is
        // silently dropped. A regression that breaks channel creation
        // ships as "service runs but invisible".
        val nm = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(
            "expected at least one notification channel registered, got ${nm.notificationChannels}",
            nm.notificationChannels.isNotEmpty(),
        )
        controller.destroy()
    }

    @Test
    fun onStartCommandHandlesNullIntent() {
        // After process restart with START_STICKY the framework redelivers
        // a null intent. The service must tolerate that path without
        // throwing.
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        controller.startCommand(0, 1)
        controller.destroy()
    }

    @Test
    fun eBikeDataDisabledIsCleanNoOp() {
        // Graceful degradation: with the eBike feature flag off, the service
        // must not start the status reader, even for a rider who owns a Bosch
        // eBike. Pinned via EBikeStateBus publishing no frame through onCreate
        // (lastUpdated stays 0) - a regression ships as "the app talks to
        // eBikes for riders who never opted in".
        EBikeStateBus.reset()
        Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = false
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        assertEquals(0L, EBikeStateBus.lastUpdatedElapsedMs.value)
        controller.destroy()
    }

    @Test
    fun eBikeDataEnabledIsCleanWithoutABondedEBike() {
        // Flag-on companion of the no-op test. Flag is enabled, ownership is
        // YES, but no Bosch eBike is bonded in Robolectric's shadow adapter
        // (the only bonded device fixture is the radar mock, if any). The
        // reader must not start, and EBikeStateBus stays at the never-received
        // sentinel - regression ships as a crash on radar-only riders who
        // toggle the feature on without an eBike.
        EBikeStateBus.reset()
        Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = true
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        assertEquals(0L, EBikeStateBus.lastUpdatedElapsedMs.value)
        controller.destroy()
    }

    @Test
    fun onStartCommandHandlesUpdateNotifAction() {
        val intent = Intent().apply { action = BikeRadarService.ACTION_UPDATE_NOTIF }
        val controller = Robolectric.buildService(BikeRadarService::class.java, intent)
        controller.create()
        controller.startCommand(0, 1)
        controller.destroy()
    }
}
