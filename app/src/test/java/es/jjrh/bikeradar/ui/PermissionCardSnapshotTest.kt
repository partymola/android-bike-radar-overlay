// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the [PermissionCardContent] leaf shared by the
 * Settings → Permissions screen and the onboarding PermissionsStep.
 *
 * Variants exercise the three distinct visual states the leaf has:
 *  - Granted (any spec, any required-ness)
 *  - Required + denied (red accent + Grant button)
 *  - Optional + denied (neutral accent + Enable button)
 *
 * `permanentlyDenied` is also covered so the "Open App info" CTA copy
 * can't silently regress.
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class PermissionCardSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    private val nearby = PermissionSpec(
        permissions = listOf("android.permission.BLUETOOTH_SCAN"),
        title = "Nearby devices",
        rationale = "Scan for and connect to your radar and dashcam over Bluetooth.",
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

    /** Mirrors the gap + horizontal padding of the parent column on
     *  Settings → Permissions, so each card sits at the position it
     *  would on the real screen. */
    @Composable
    private fun PermShell(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }

    @Test
    fun requiredGranted() {
        paparazzi.snapshot {
            UiTheme {
                PermShell {
                    PermissionCardContent(
                        spec = nearby,
                        granted = true,
                        permanentlyDenied = false,
                        onAction = {},
                    )
                }
            }
        }
    }

    @Test
    fun requiredDenied() {
        paparazzi.snapshot {
            UiTheme {
                PermShell {
                    PermissionCardContent(
                        spec = nearby,
                        granted = false,
                        permanentlyDenied = false,
                        onAction = {},
                    )
                }
            }
        }
    }

    @Test
    fun optionalDenied() {
        paparazzi.snapshot {
            UiTheme {
                PermShell {
                    PermissionCardContent(
                        spec = overlay,
                        granted = false,
                        permanentlyDenied = false,
                        onAction = {},
                    )
                }
            }
        }
    }

    @Test
    fun permanentlyDenied() {
        paparazzi.snapshot {
            UiTheme {
                PermShell {
                    PermissionCardContent(
                        spec = nearby,
                        granted = false,
                        permanentlyDenied = true,
                        onAction = {},
                    )
                }
            }
        }
    }
}
