// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.HaHealth
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden for the Settings home menu using the stateless
 * [SettingsMenuBody] leaf so the test does not depend on Prefs, the
 * battery/HA buses, or `HaCredentials`. Renders a representative configured
 * rider (dev mode unlocked) to lock the row-group composition - the Quick
 * Status card plus the Ride / Connections / System sections.
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
                    devUnlocked = true,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot().copy(
                        dashcamOwnership = es.jjrh.bikeradar.data.DashcamOwnership.YES,
                        autoLightModeEnabled = true,
                        radarLightAutoModeEnabled = true,
                        eBikeOwnership = es.jjrh.bikeradar.data.EBikeOwnership.YES,
                        eBikeDataEnabled = true,
                    ),
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
                    dashcamBattery = BatteryEntry("vue", "Vue", 64, readAtMs = 1_000L),
                    haConfigured = true,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                )
            }
        }
    }
}
