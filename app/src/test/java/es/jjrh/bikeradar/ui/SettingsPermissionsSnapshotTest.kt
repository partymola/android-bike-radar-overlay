// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Settings → Permissions screen using the
 * stateless [SettingsPermissionsContent] leaf so the test does not
 * depend on real `Context`-backed permission state.
 *
 * Variants cover the three card-mix states a fresh-install user can
 * actually land in: everything granted, a mix, and everything denied.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsPermissionsSnapshotTest {

    private val nearby = PermissionSpec(
        permissions = listOf("android.permission.BLUETOOTH_SCAN"),
        title = "Nearby devices",
        rationale = "Scan for and connect to your radar and dashcam over Bluetooth.",
        required = true,
    )

    private val notifications = PermissionSpec(
        permissions = listOf("android.permission.POST_NOTIFICATIONS"),
        title = "Notifications",
        rationale = "Post the silent service notification and any ride alerts.",
        required = true,
    )

    private val overlay = PermissionSpec(
        permissions = emptyList(),
        title = "Draw over other apps",
        rationale = "Draw the radar overlay on top of whatever's on screen. " +
            "Without this, alerts still play but you won't see the overlay.",
        required = false,
        markLabel = "Recommended",
    )

    @Test
    fun allGranted() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to true,
                        notifications to true,
                        overlay to true,
                    ),
                )
            }
        }
    }

    @Test
    fun mixed() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to true,
                        notifications to false,
                        overlay to false,
                    ),
                )
            }
        }
    }

    @Test
    fun allDenied() {
        captureRoboImage {
            UiTheme {
                SettingsPermissionsContent(
                    navController = rememberNavController(),
                    specsAndGranted = listOf(
                        nearby to false,
                        notifications to false,
                        overlay to false,
                    ),
                )
            }
        }
    }
}
