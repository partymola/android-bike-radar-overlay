// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the Settings → Permissions screen using the
 * stateless [SettingsPermissionsContent] leaf so the test does not
 * depend on real `Context`-backed permission state.
 *
 * Variants cover the three card-mix states a fresh-install user can
 * actually land in: everything granted, a mix, and everything denied.
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsPermissionsSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
