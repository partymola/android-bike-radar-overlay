// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.R
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
        titleRes = R.string.permission_nearby_title,
        rationaleRes = R.string.permission_nearby_rationale,
        required = true,
    )

    private val notifications = PermissionSpec(
        permissions = listOf("android.permission.POST_NOTIFICATIONS"),
        titleRes = R.string.permission_notifications_title,
        rationaleRes = R.string.permission_notifications_rationale,
        required = true,
    )

    private val overlay = PermissionSpec(
        permissions = emptyList(),
        titleRes = R.string.permission_overlay_title,
        rationaleRes = R.string.permission_overlay_rationale,
        required = false,
        markLabelRes = R.string.permission_mark_recommended,
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
