// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.EBikeStage
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
                        // A camera must be PICKED for its row to describe a
                        // link: without a mac the row falls to "Pick a device"
                        // while the chip above still renders dashcamLink, and
                        // the screen shows a state production cannot produce.
                        dashcamMac = "AA:BB:CC:DD:EE:FF",
                        autoLightModeEnabled = true,
                        radarLightAutoModeEnabled = true,
                        eBikeOwnership = es.jjrh.bikeradar.data.EBikeOwnership.YES,
                        eBikeDataEnabled = true,
                    ),
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIVE,
                    dashcamLink = DeviceLinkState.LIVE,
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
                    dashcamBattery = BatteryEntry("vue", "Vue", 64, readAtMs = 1_000L),
                    ebikeReceiving = true,
                    ebikeStage = EBikeStage.RECEIVING,
                    haConfigured = true,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                    radarGrants = emptyList(),
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
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIVE,
                    dashcamLink = DeviceLinkState.NOT_PAIRED,
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
                    dashcamBattery = null,
                    ebikeReceiving = false,
                    ebikeStage = EBikeStage.NOT_STARTED,
                    haConfigured = false,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                    radarGrants = emptyList(),
                )
            }
        }
    }

    /**
     * Everything set up, nothing delivering: the state a rider is in when
     * they open Settings from a home card that said "No signal". Every other
     * golden here renders a working rider, so the words for the failure - the
     * half of each row a rider reads when something is wrong - had no visual
     * coverage on the screen where they most disagreed.
     */
    @Test
    fun menuPairedButNothingConnected() {
        captureRoboImage {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot().copy(
                        dashcamOwnership = es.jjrh.bikeradar.data.DashcamOwnership.YES,
                        // A camera must be PICKED for its row to describe a
                        // link: without a mac the row falls to "Pick a device"
                        // while the chip above still renders dashcamLink, and
                        // the screen shows a state production cannot produce.
                        dashcamMac = "AA:BB:CC:DD:EE:FF",
                        eBikeOwnership = es.jjrh.bikeradar.data.EBikeOwnership.YES,
                        eBikeDataEnabled = true,
                    ),
                    btEnabled = true,
                    radarLink = DeviceLinkState.NO_SIGNAL,
                    dashcamLink = DeviceLinkState.NO_SIGNAL,
                    // PRESENT, not null, and that is the whole point: an
                    // aborting radar keeps a fresh battery entry while
                    // sending no targets, so this is the real state in which
                    // the chip must refuse to show a percentage. Passing null
                    // would leave the chip's own gate unpinned - remove it and
                    // nothing would change.
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
                    dashcamBattery = BatteryEntry("vue", "Vue", 64, readAtMs = 1_000L),
                    ebikeReceiving = false,
                    ebikeStage = EBikeStage.WAITING,
                    haConfigured = true,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                    radarGrants = emptyList(),
                )
            }
        }
    }

    /**
     * No radar bonded, and Bluetooth off. Two things nothing else here
     * renders: the radar row dropping its two-part form (there is no link to
     * describe until a radar exists, so "Not paired" is the whole answer),
     * and the Bluetooth-off banner that keeps that word from being read as a
     * claim about the hardware.
     */
    @Test
    fun menuNoRadarAndBluetoothOff() {
        captureRoboImage {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot().copy(
                        dashcamOwnership = es.jjrh.bikeradar.data.DashcamOwnership.UNANSWERED,
                        eBikeOwnership = es.jjrh.bikeradar.data.EBikeOwnership.UNANSWERED,
                    ),
                    btEnabled = false,
                    radarLink = DeviceLinkState.NOT_PAIRED,
                    dashcamLink = DeviceLinkState.NOT_PAIRED,
                    radarBattery = null,
                    dashcamBattery = null,
                    ebikeReceiving = false,
                    ebikeStage = EBikeStage.NOT_STARTED,
                    haConfigured = false,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 1,
                    permissionsRequiredMissing = 2,
                    permissionsTotal = 3,
                    radarGrants = emptyList(),
                )
            }
        }
    }

    /**
     * The Settings menu in Spanish, in the state whose words are longest.
     *
     * This screen had no es golden at all, and the change moved a status word
     * into the tightest slot in the app: two half-width quick-status chips
     * side by side. Spanish runs 15-30% longer than English and short labels
     * expand worst, and the status Text is `softWrap = false` with an
     * ellipsis, so an overflow truncates silently rather than failing. This
     * makes it a gate instead of an argument about widths.
     *
     * LIMITED, because the worst case is a long word that ALSO renders the
     * battery chip. "No emparejado" is the longest word (13 glyphs to
     * "Conectando…"'s 11) but suppresses the chip via `isDelivering`, so it
     * leaves the row emptier than "Limitado" plus a percentage does.
     */
    @Test
    @Config(qualifiers = "+es")
    fun menuPairedButNothingConnectedEs() {
        captureRoboImage {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot().copy(
                        dashcamOwnership = es.jjrh.bikeradar.data.DashcamOwnership.YES,
                        dashcamMac = "AA:BB:CC:DD:EE:FF",
                        eBikeOwnership = es.jjrh.bikeradar.data.EBikeOwnership.YES,
                        eBikeDataEnabled = true,
                    ),
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIMITED,
                    dashcamLink = DeviceLinkState.LIMITED,
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
                    dashcamBattery = BatteryEntry("vue", "Vue", 64, readAtMs = 1_000L),
                    ebikeReceiving = false,
                    ebikeStage = EBikeStage.NOT_PERMITTED,
                    haConfigured = true,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                    radarGrants = emptyList(),
                )
            }
        }
    }

    /**
     * A range-only radar. Green is the app's "you are covered" signal and
     * must never appear over a link that cannot raise the urgent cue, and no
     * fixture on this screen rendered LIMITED at all: inverting the
     * `source == V1` test that produces it changed nothing anywhere, so the
     * chip could have gone green over a radar with no urgent warning and no
     * close-pass logging. The radar's own screen has this state pinned; this
     * screen did not.
     */
    @Test
    fun menuRadarLimited() {
        captureRoboImage {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot().copy(
                        dashcamOwnership = es.jjrh.bikeradar.data.DashcamOwnership.YES,
                        dashcamMac = "AA:BB:CC:DD:EE:FF",
                    ),
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIMITED,
                    dashcamLink = DeviceLinkState.LIVE,
                    // Shown on LIMITED: the percentage is a fact about the
                    // device, not a claim about cover.
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
                    dashcamBattery = BatteryEntry("vue", "Vue", 64, readAtMs = 1_000L),
                    ebikeReceiving = false,
                    ebikeStage = EBikeStage.NOT_STARTED,
                    haConfigured = true,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                    radarGrants = emptyList(),
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
                        // A camera must be PICKED for its row to describe a
                        // link: without a mac the row falls to "Pick a device"
                        // while the chip above still renders dashcamLink, and
                        // the screen shows a state production cannot produce.
                        dashcamMac = "AA:BB:CC:DD:EE:FF",
                        batteryLowThresholdPct = 40,
                    ),
                    // 30% is NORMAL at the default 20 and LOW at 40; 18% is
                    // LOW at the default and CRITICAL at 40. Both chips move
                    // only because the threshold was read.
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIVE,
                    dashcamLink = DeviceLinkState.LIVE,
                    radarBattery = BatteryEntry("radar", "RearVue8", 30, readAtMs = 1_000L),
                    dashcamBattery = BatteryEntry("vue", "Vue", 18, readAtMs = 1_000L),
                    ebikeReceiving = true,
                    ebikeStage = EBikeStage.RECEIVING,
                    haConfigured = true,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 3,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 3,
                    radarGrants = emptyList(),
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
                        // A camera must be PICKED for its row to describe a
                        // link: without a mac the row falls to "Pick a device"
                        // while the chip above still renders dashcamLink, and
                        // the screen shows a state production cannot produce.
                        dashcamMac = "AA:BB:CC:DD:EE:FF",
                        autoLightModeEnabled = true,
                        radarLightAutoModeEnabled = true,
                        eBikeOwnership = es.jjrh.bikeradar.data.EBikeOwnership.YES,
                        eBikeDataEnabled = true,
                    ),
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIVE,
                    dashcamLink = DeviceLinkState.LIVE,
                    radarBattery = BatteryEntry("radar", "RearVue8", 78, readAtMs = 1_000L),
                    dashcamBattery = BatteryEntry("vue", "Vue", 64, readAtMs = 1_000L),
                    ebikeReceiving = true,
                    ebikeStage = EBikeStage.RECEIVING,
                    haConfigured = true,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 2,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 4,
                    radarGrants = emptyList(),
                )
            }
        }
    }

    @Test
    fun menuWithAppsUsingTheRadar() {
        captureRoboImage {
            UiTheme {
                SettingsMenuBody(
                    navController = rememberNavController(),
                    devUnlocked = false,
                    prefsSnap = SnapshotFixtures.defaultPrefsSnapshot(),
                    btEnabled = true,
                    radarLink = DeviceLinkState.LIVE,
                    dashcamLink = DeviceLinkState.NOT_PAIRED,
                    radarBattery = null,
                    dashcamBattery = null,
                    ebikeReceiving = false,
                    ebikeStage = EBikeStage.NO_BONDED_BIKE,
                    haConfigured = false,
                    haHealth = HaHealth.Unknown,
                    permissionsGrantedCount = 4,
                    permissionsRequiredMissing = 0,
                    permissionsTotal = 4,
                    radarGrants = listOf(
                        es.jjrh.bikeradar.access.RadarGrant(
                            "com.example.trailbuddy",
                            "aa11",
                            "Trail Buddy",
                            0L,
                            0L,
                            read = true,
                            control = true,
                        ),
                    ),
                )
            }
        }
    }
}
