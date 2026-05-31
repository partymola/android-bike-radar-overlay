// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.RadarLightMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for Settings → Radar light auto-mode, using the stateless
 * [SettingsRadarLightContent] leaf (no Context-backed permission state / launcher).
 * Pins the toggle states, the location re-grant card visibility, and the
 * no-read-back disclosure line (shown only while auto-mode is on).
 *
 * Renders via Robolectric Native Graphics. Verify with
 * `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsRadarLightSnapshotTest {

    private val location = PermissionSpec(
        permissions = listOf("android.permission.ACCESS_COARSE_LOCATION"),
        title = "Approximate location",
        rationale = "Used once per ride to compute accurate sunrise/sunset for the " +
            "radar-light auto-mode. Skip it and sunset is estimated for London.",
        required = false,
        markLabel = "Optional",
    )

    @Test
    fun autoOnLocationDenied() {
        captureRoboImage {
            UiTheme {
                SettingsRadarLightContent(
                    onBack = {},
                    autoEnabled = true,
                    dayMode = RadarLightMode.DAY_FLASH,
                    nightMode = RadarLightMode.NIGHT_FLASH,
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
                SettingsRadarLightContent(
                    onBack = {},
                    autoEnabled = true,
                    dayMode = RadarLightMode.DAY_FLASH,
                    nightMode = RadarLightMode.NIGHT_FLASH,
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
                SettingsRadarLightContent(
                    onBack = {},
                    autoEnabled = false,
                    dayMode = RadarLightMode.DAY_FLASH,
                    nightMode = RadarLightMode.NIGHT_FLASH,
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
