// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Smoke tests for BootReceiver.onReceive: verify the receiver runs without
 * throwing under each gate-state combination, and starts the service only
 * when all gates pass. The whole-process boot path (BootReceiver registered
 * via the manifest) is what fires after a phone reboot or APK update, so a
 * crash here is invisible until the next reboot — exactly the kind of
 * failure mode JVM tests should catch.
 */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val receiver = BootReceiver()

    @Test
    fun ignoresUnrelatedAction() {
        receiver.onReceive(app, Intent("some.other.action"))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun bootCompletedDoesNotStartServiceWhenFirstRunIncomplete() {
        Prefs(app).firstRunComplete = false
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun bootCompletedDoesNotStartServiceWhenServiceDisabled() {
        Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = false
        }
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun bootCompletedDoesNotStartServiceWhenPermissionsMissing() {
        Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
        }
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun bootCompletedStartsServiceWhenAllGatesPass() {
        Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
        }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(app).peekNextStartedService()
        assertEquals(BikeRadarService::class.java.name, started?.component?.className)
    }

    @Test
    fun packageReplacedStartsServiceWhenAllGatesPass() {
        Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
        }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val started = shadowOf(app).peekNextStartedService()
        assertEquals(BikeRadarService::class.java.name, started?.component?.className)
    }
}
