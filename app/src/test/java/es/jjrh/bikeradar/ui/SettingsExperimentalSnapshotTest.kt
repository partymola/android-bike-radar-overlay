// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the Experimental screen — both states of the
 * precog toggle. Renders the stateless [SettingsExperimentalContent]
 * leaf so no Prefs scaffolding is needed.
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsExperimentalSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun precogOff() {
        paparazzi.snapshot {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
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
                )
            }
        }
    }
}
