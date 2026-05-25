// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.HaHealth
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden for the Settings home menu using the stateless
 * [SettingsMenuBody] leaf so the test does not depend on Prefs, the
 * battery/HA buses, or `HaCredentials`. One default-state variant
 * locks the row-group composition (General + Advanced) plus the system
 * health bar at the top.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsScreenSnapshotTest {

    @Test
    fun defaultMenu() {
        captureRoboImage {
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
