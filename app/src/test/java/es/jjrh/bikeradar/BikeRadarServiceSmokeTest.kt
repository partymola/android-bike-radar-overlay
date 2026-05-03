// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.Assert.assertTrue
import org.junit.After
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
    fun onStartCommandHandlesUpdateNotifAction() {
        val intent = Intent().apply { action = BikeRadarService.ACTION_UPDATE_NOTIF }
        val controller = Robolectric.buildService(BikeRadarService::class.java, intent)
        controller.create()
        controller.startCommand(0, 1)
        controller.destroy()
    }
}
