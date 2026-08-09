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

    /**
     * The rider-only kit: no dashcam, no eBike, no Home Assistant. Every
     * surface this range reworked appears on this screen, and the other two
     * goldens render a fully equipped rider, so without this the radar-only
     * majority had no visual coverage of any of them.
     */
    @Test
    fun menuRadarOnly() {
        captureRoboImage {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot().copy(
                        dashcamOwnership = es.jjrh.bikeradar.data.DashcamOwnership.NO,
                        eBikeOwnership = es.jjrh.bikeradar.data.EBikeOwnership.NO,
                        eBikeDataEnabled = false,
                    ),
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
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

    /**
     * A rider who raised the low-battery threshold. Every other golden runs
     * at the default with chips well clear of any band, so the step from the
     * stored threshold through to the chip colour was pinned nowhere.
     */
    @Test
    fun menuRaisedBatteryThreshold() {
        captureRoboImage {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot().copy(
                        dashcamOwnership = es.jjrh.bikeradar.data.DashcamOwnership.YES,
                        batteryLowThresholdPct = 40,
                    ),
                    // 30% is NORMAL at the default 20 and LOW at 40; 18% is
                    // LOW at the default and CRITICAL at 40. Both chips move
                    // only because the threshold was read.
                    radarBattery = BatteryEntry("radar", "RearVue8", 30, readAtMs = 1_000L),
                    dashcamBattery = BatteryEntry("vue", "Vue", 18, readAtMs = 1_000L),
                    haConfigured = true,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                )
            }
        }
    }

    /**
     * Every required permission granted, optional ones outstanding. The row
     * that used to assert "All granted" over numbers that disagreed with it,
     * and the only place the partial subtitle renders.
     */
    @Test
    fun menuPermissionsPartiallyGranted() {
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
                    permissionsGrantedCount = 2,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 4,
                )
            }
        }
    }
}
