// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the Experimental screen - covers each toggle's
 * state plus the directional-audio invert sub-row (only visible when the
 * main toggle is on). Renders the stateless [SettingsExperimentalContent]
 * leaf so no Prefs scaffolding is needed.
 *
 * CI does not run these - Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsExperimentalSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun allOff() {
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
