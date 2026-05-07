// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import es.jjrh.bikeradar.HaHealth
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi golden for the Settings home menu using the stateless
 * [SettingsMenuBody] leaf so the test does not depend on Prefs, the
 * battery/HA buses, or `HaCredentials`. One default-state variant
 * locks the row-group composition (General + Advanced) plus the system
 * health bar at the top.
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsScreenSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun defaultMenu() {
        paparazzi.snapshot {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot(),
                    radarBattery = null,
                    dashcamBattery = null,
                    haConfigured = false,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                )
            }
        }
    }
}
