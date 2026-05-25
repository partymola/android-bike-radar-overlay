// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the [PermissionCardContent] leaf shared by the
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
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class PermissionCardSnapshotTest {

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
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
