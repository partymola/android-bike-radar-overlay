// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.CameraLightMode
import es.jjrh.bikeradar.RadarLightMode
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the consolidated Settings → Light auto-mode screen,
 * using the stateless [SettingsLightsContent] leaf (no Context-backed
 * permission state / launcher). Pins: the radar + dashcam sub-sections, the
 * radar-only no-read-back disclosure line, the SHARED location re-grant card
 * (shown when either auto-mode is on and location is denied), and the
 * no-dashcam degradation (front section collapses to a deep-link row).
 *
 * Renders via Robolectric Native Graphics. Verify with
 * `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsLightsSnapshotTest {

    private val location = PermissionSpec(
        permissions = listOf("android.permission.ACCESS_COARSE_LOCATION"),
        title = "Approximate location",
        rationale = "Used once per ride to compute accurate sunrise/sunset for the " +
            "light auto-modes. Skip it and sunset is estimated for London.",
        required = false,
        markLabel = "Optional",
    )

    @Test
    fun bothOnLocationDenied() {
        captureRoboImage {
            UiTheme {
                SettingsLightsContent(
                    onBack = {},
                    rearAuto = true,
                    radarDay = RadarLightMode.DAY_FLASH,
                    radarNight = RadarLightMode.NIGHT_FLASH,
                    dashcamOwnership = DashcamOwnership.YES,
                    frontAuto = true,
                    dashcamDay = CameraLightMode.DAY_FLASH,
                    dashcamNight = CameraLightMode.NIGHT_FLASH,
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
    fun bothOnLocationGranted() {
        captureRoboImage {
            UiTheme {
                SettingsLightsContent(
                    onBack = {},
                    rearAuto = true,
                    radarDay = RadarLightMode.SOLID,
                    radarNight = RadarLightMode.NIGHT_FLASH,
                    dashcamOwnership = DashcamOwnership.YES,
                    frontAuto = true,
                    dashcamDay = CameraLightMode.DAY_FLASH,
                    dashcamNight = CameraLightMode.LOW,
                    locGranted = true,
                )
            }
        }
    }

    @Test
    fun bothOff() {
        captureRoboImage {
            UiTheme {
                SettingsLightsContent(
                    onBack = {},
                    rearAuto = false,
                    radarDay = RadarLightMode.DAY_FLASH,
                    radarNight = RadarLightMode.NIGHT_FLASH,
                    dashcamOwnership = DashcamOwnership.YES,
                    frontAuto = false,
                    dashcamDay = CameraLightMode.DAY_FLASH,
                    dashcamNight = CameraLightMode.NIGHT_FLASH,
                    locGranted = true,
                )
            }
        }
    }

    @Test
    fun radarOnDashcamOff() {
        // Independent toggles: radar auto on, dashcam owned but its auto off.
        // Pins the mixed layout (live radar pickers + radar caveat above greyed
        // dashcam pickers) the two-toggle independence allows.
        captureRoboImage {
            UiTheme {
                SettingsLightsContent(
                    onBack = {},
                    rearAuto = true,
                    radarDay = RadarLightMode.DAY_FLASH,
                    radarNight = RadarLightMode.NIGHT_FLASH,
                    dashcamOwnership = DashcamOwnership.YES,
                    frontAuto = false,
                    dashcamDay = CameraLightMode.DAY_FLASH,
                    dashcamNight = CameraLightMode.NIGHT_FLASH,
                    locGranted = true,
                )
            }
        }
    }

    @Test
    fun noDashcam() {
        captureRoboImage {
            UiTheme {
                SettingsLightsContent(
                    onBack = {},
                    rearAuto = true,
                    radarDay = RadarLightMode.DAY_FLASH,
                    radarNight = RadarLightMode.NIGHT_FLASH,
                    dashcamOwnership = DashcamOwnership.NO,
                    frontAuto = false,
                    dashcamDay = CameraLightMode.DAY_FLASH,
                    dashcamNight = CameraLightMode.NIGHT_FLASH,
                    locGranted = true,
                )
            }
        }
    }
}
