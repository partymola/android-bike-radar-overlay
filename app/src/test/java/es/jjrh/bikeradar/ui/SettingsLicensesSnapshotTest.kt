// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi golden for the open-source-licences screen. Renders the
 * top of the screen — header, intro blurb, and first section groups.
 * Mid-scroll positions are not snapshotted: Paparazzi cannot drive a
 * `verticalScroll(rememberScrollState())` mid-test, and the layout
 * structure is uniform enough that the top frame catches regressions
 * to the row template.
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsLicensesSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun top() {
        paparazzi.snapshot {
            SettingsLicenses(navController = rememberNavController())
        }
    }
}
