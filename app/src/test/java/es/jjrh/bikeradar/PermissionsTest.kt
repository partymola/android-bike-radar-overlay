// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the SDK-version branch in [Permissions.hasRequiredForService].
 *
 * On Tiramisu (API 33) and above, POST_NOTIFICATIONS joins BLUETOOTH_SCAN
 * and BLUETOOTH_CONNECT in the required set; below it, only the two
 * Bluetooth permissions are checked.
 *
 * The Tiramisu-and-above cases run at the project's default Robolectric SDK
 * (35, see robolectric.properties) so the JaCoCo agent attributes their
 * coverage - the default sandbox is the one it instruments. The pre-Tiramisu
 * case uses @Config(sdk=S) to drive the other side of the version gate.
 * The shadow application controls which permissions are granted so the
 * all()-predicate is exercised both true and false.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun tiramisuRequiresAllThreeIncludingPostNotifications() {
        // Default SDK 35 (>= TIRAMISU): POST_NOTIFICATIONS is in the set.
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        assertTrue(Permissions.hasRequiredForService(app))
    }

    @Test
    fun tiramisuMissingPostNotificationsFails() {
        // Both Bluetooth permissions granted, notifications withheld: on
        // Tiramisu+ the all() predicate must reject because POST_NOTIFICATIONS
        // is in the required set.
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        assertFalse(Permissions.hasRequiredForService(app))
    }

    @Test
    fun tiramisuMissingBluetoothFails() {
        // Missing one Bluetooth permission must fail the all() predicate.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertFalse(Permissions.hasRequiredForService(app))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun preTiramisuDoesNotRequirePostNotifications() {
        // API 31 (S): only the two Bluetooth permissions are required. With
        // notifications withheld it must still pass - this drives the
        // SDK_INT < TIRAMISU side of the version gate.
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        assertTrue(Permissions.hasRequiredForService(app))
    }
}
