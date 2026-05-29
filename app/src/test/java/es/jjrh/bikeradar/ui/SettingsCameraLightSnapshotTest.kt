// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.CameraLightMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Settings → Dashcam light auto-mode screen
 * using the stateless [SettingsCameraLightContent] leaf so the test does
 * not depend on real `Context`-backed permission state or a permission
 * launcher.
 *
 * Variants pin the M7 location re-grant surface: the card is shown only
 * while it's actionable (auto-mode on AND location not yet granted), and
 * hidden otherwise. When shown, the card is the launcher-free
 * [PermissionCardContent] with a stub action.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsCameraLightSnapshotTest {

    private val location = PermissionSpec(
        permissions = listOf("android.permission.ACCESS_COARSE_LOCATION"),
        title = "Approximate location",
        rationale = "Used once per ride to compute accurate sunrise/sunset for the " +
            "dashcam-light auto-mode. Skip it and sunset is estimated for London.",
        required = false,
        markLabel = "Optional",
    )

    @Test
    fun autoOnLocationDenied() {
        captureRoboImage {
            UiTheme {
                SettingsCameraLightContent(
                    onBack = {},
                    autoEnabled = true,
                    dayMode = CameraLightMode.DAY_FLASH,
                    nightMode = CameraLightMode.NIGHT_FLASH,
                    locGranted = false,
                    locationCard = {
                        PermissionCardContent(
                            spec = location,
                            granted = false,
                            permanentlyDenied = false,
                            onAction = {},
                        )
                    },
                )
            }
        }
    }

    @Test
    fun autoOnLocationGranted() {
        captureRoboImage {
            UiTheme {
                SettingsCameraLightContent(
                    onBack = {},
                    autoEnabled = true,
                    dayMode = CameraLightMode.DAY_FLASH,
                    nightMode = CameraLightMode.NIGHT_FLASH,
                    locGranted = true,
                    locationCard = {
                        PermissionCardContent(
                            spec = location,
                            granted = true,
                            permanentlyDenied = false,
                            onAction = {},
                        )
                    },
                )
            }
        }
    }

    @Test
    fun autoOff() {
        captureRoboImage {
            UiTheme {
                SettingsCameraLightContent(
                    onBack = {},
                    autoEnabled = false,
                    dayMode = CameraLightMode.DAY_FLASH,
                    nightMode = CameraLightMode.NIGHT_FLASH,
                    locGranted = false,
                    locationCard = {
                        PermissionCardContent(
                            spec = location,
                            granted = false,
                            permanentlyDenied = false,
                            onAction = {},
                        )
                    },
                )
            }
        }
    }
}
