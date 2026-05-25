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
 * Roborazzi goldens for the Experimental screen - covers each toggle's
 * state plus the directional-audio invert sub-row (only visible when the
 * main toggle is on). Renders the stateless [SettingsExperimentalContent]
 * leaf so no Prefs scaffolding is needed.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsExperimentalSnapshotTest {

    @Test
    fun allOff() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
                    lateralPanningEnabled = false,
                    onLateralPanningChange = {},
                    lateralPanningInvertLR = false,
                    onLateralPanningInvertLRChange = {},
                )
            }
        }
    }

    @Test
    fun precogOn() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = true,
                    onPrecogChange = {},
                    lateralPanningEnabled = false,
                    onLateralPanningChange = {},
                    lateralPanningInvertLR = false,
                    onLateralPanningInvertLRChange = {},
                )
            }
        }
    }

    @Test
    fun lateralPanningOn() {
        // Sub-row "Invert left/right" becomes visible when the main
        // toggle is on. Pinned so a future refactor of the conditional
        // render is caught.
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
                    lateralPanningEnabled = true,
                    onLateralPanningChange = {},
                    lateralPanningInvertLR = false,
                    onLateralPanningInvertLRChange = {},
                )
            }
        }
    }

    @Test
    fun lateralPanningOnInverted() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
                    lateralPanningEnabled = true,
                    onLateralPanningChange = {},
                    lateralPanningInvertLR = true,
                    onLateralPanningInvertLRChange = {},
                )
            }
        }
    }
}
