// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers [Permissions.missingForService], the SDK-version branch in it, and
 * the service-start rule built on it.
 *
 * The service needs Nearby devices (BLUETOOTH_SCAN and BLUETOOTH_CONNECT).
 * On Tiramisu (API 33) and above POST_NOTIFICATIONS is also asked for, but
 * does not stop the service; below it, it is never asked for.
 *
 * The Tiramisu-and-above cases run at the project's default Robolectric SDK
 * (35, see robolectric.properties) so the JaCoCo agent attributes their
 * coverage - the default sandbox is the one it instruments. The pre-Tiramisu
 * case uses @Config(sdk=S) to drive the other side of the version gate.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun everythingGrantedStartsTheServiceAndAsksForNothing() {
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        assertTrue(Permissions.hasRequiredForService(app))
        assertNull(Permissions.missingForService(app))
    }

    @Test
    fun theServiceRunsWithoutNotifications() {
        // A refused Notifications grant costs the alarms' Dismiss and Snooze,
        // not the beeps: refusing to run would silence every alert instead.
        // The card still asks for it.
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        assertTrue(Permissions.hasRequiredForService(app))
        assertEquals(MissingPermission.NOTIFICATIONS, Permissions.missingForService(app))
    }

    @Test
    fun missingBluetoothStopsTheService() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(Permissions.hasRequiredForService(app))
    }

    @Test
    fun theMissingPermissionIsNamedNearbyDevicesFirst() {
        assertEquals(MissingPermission.NEARBY_DEVICES, Permissions.missingForService(app))
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(MissingPermission.NEARBY_DEVICES, Permissions.missingForService(app))
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertEquals(MissingPermission.NOTIFICATIONS, Permissions.missingForService(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertNull(Permissions.missingForService(app))
    }

    @Test
    fun scanWithoutConnectIsStillNearbyDevices() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(MissingPermission.NEARBY_DEVICES, Permissions.missingForService(app))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun preTiramisuNeverAsksForNotifications() {
        // API 31 (S) has no notification permission to ask for. This drives
        // the SDK_INT < TIRAMISU side of the version gate.
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        assertNull(Permissions.missingForService(app))
    }
}
