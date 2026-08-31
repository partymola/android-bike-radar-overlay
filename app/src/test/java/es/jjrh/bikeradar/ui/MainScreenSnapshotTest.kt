// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.AttentionItem
import es.jjrh.bikeradar.AttentionKind
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.HaStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the home-screen leaf cards: [HeroStatusCard]
 * and [SystemCard]. Renders each leaf directly with stub state so the
 * test does not depend on Prefs, the radar/battery/HA buses, or the
 * lifecycle-driven pollers in [MainScreen].
 *
 * Variants picked to exercise the visually distinct states:
 *  - Hero: live-good (CheckCircle / Good) and not-paired-bt-on (Error)
 *  - System: empty (nothing paired, HA not configured), populated (radar +
 *    dashcam + eBike + HA all green with battery chips), eBike-waiting (the
 *    eBike row in its amber "Waiting for Flow" state with no stale battery
 *    chip), and the HA row's four states including both es variants
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class MainScreenSnapshotTest {

    /** Mirrors the parent column inside [MainScreen]'s portrait body so
     *  the leaf cards sit at the horizontal padding they would on the
     *  real screen. */
    @Composable
    private fun MainShell(content: @Composable () -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            content()
        }
    }

    @Test
    fun heroLive() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    HeroStatusCard(
                        status = MainStatus(
                            icon = MainStatusIcon.CheckCircle,
                            tone = MainStatusTone.Good,
                            headline = "Radar live",
                            subtitle = "Dashcam on",
                        ),
                        cta = null,
                    )
                }
            }
        }
    }

    @Test
    fun heroNotPaired() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    HeroStatusCard(
                        status = MainStatus(
                            icon = MainStatusIcon.BluetoothDisabled,
                            tone = MainStatusTone.Error,
                            headline = "Radar not paired",
                            subtitle = "Pair in Settings",
                        ),
                        cta = StatusCta(label = "Pair", onClick = {}),
                    )
                }
            }
        }
    }

    @Test
    fun systemEmpty() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    SystemCard(
                        radarFresh = false,
                        hasBond = false,
                        btEnabled = true,
                        dashcamOwned = false,
                        dashcamFresh = false,
                        dashcamPaired = false,
                        dashcamDisplayName = null,
                        radarBattery = null,
                        dashcamBattery = null,
                        // A fresh install has never configured Home Assistant,
                        // so this is the state the shipped defect rendered as
                        // a green "MQTT ready". It is the whole point of this
                        // golden: nothing paired, nothing set up, nothing
                        // claiming to work.
                        haStatus = HaStatus.NOT_CONFIGURED,
                        onRowClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun systemHaConfiguredNotYetPublished() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    SystemCard(
                        radarFresh = true,
                        hasBond = true,
                        btEnabled = true,
                        dashcamOwned = false,
                        dashcamFresh = false,
                        dashcamPaired = false,
                        dashcamDisplayName = null,
                        radarBattery = null,
                        dashcamBattery = null,
                        // Credentials saved, nothing published yet: the resting
                        // state of a correct setup between app start and its
                        // first ride-edge publish. Must read as set up, never as
                        // a working connection and never as a failure.
                        haStatus = HaStatus.CONFIGURED,
                        onRowClick = {},
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun systemHaNotConfiguredEs() {
        // The es strings for the two NEW states, verified as rendered rather
        // than as translations: short labels expand worst in Spanish and this
        // is a tight mono value slot.
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    SystemCard(
                        radarFresh = false,
                        hasBond = false,
                        btEnabled = true,
                        dashcamOwned = false,
                        dashcamFresh = false,
                        dashcamPaired = false,
                        dashcamDisplayName = null,
                        radarBattery = null,
                        dashcamBattery = null,
                        haStatus = HaStatus.NOT_CONFIGURED,
                        onRowClick = {},
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun systemHaConfiguredEs() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    SystemCard(
                        radarFresh = true,
                        hasBond = true,
                        btEnabled = true,
                        dashcamOwned = false,
                        dashcamFresh = false,
                        dashcamPaired = false,
                        dashcamDisplayName = null,
                        radarBattery = null,
                        dashcamBattery = null,
                        haStatus = HaStatus.CONFIGURED,
                        onRowClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun systemHaUnreachable() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    SystemCard(
                        radarFresh = true,
                        hasBond = true,
                        btEnabled = true,
                        dashcamOwned = false,
                        dashcamFresh = false,
                        dashcamPaired = false,
                        dashcamDisplayName = null,
                        radarBattery = null,
                        dashcamBattery = null,
                        haStatus = HaStatus.UNREACHABLE,
                        onRowClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun systemPopulated() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    SystemCard(
                        radarFresh = true,
                        hasBond = true,
                        btEnabled = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        dashcamDisplayName = "Front cam",
                        radarBattery = BatteryEntry(
                            slug = "rearvue",
                            name = "RearVue",
                            pct = 78,
                            readAtMs = 0L,
                        ),
                        dashcamBattery = BatteryEntry(
                            slug = "front-cam",
                            name = "Front cam",
                            pct = 64,
                            readAtMs = 0L,
                        ),
                        haStatus = HaStatus.READY,
                        ebikeEnabled = true,
                        ebikeReceiving = true,
                        ebikeBatterySoc = 82,
                        onRowClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun attentionCard() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    AttentionCard(
                        items = listOf(
                            AttentionItem(AttentionKind.RADAR_BATTERY, 12),
                            AttentionItem(AttentionKind.DASHCAM_BATTERY, 8),
                            AttentionItem(AttentionKind.AUDIO_FAILURES, 2),
                        ),
                        onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun systemEbikeWaiting() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    SystemCard(
                        radarFresh = true,
                        hasBond = true,
                        btEnabled = true,
                        dashcamOwned = false,
                        dashcamFresh = false,
                        dashcamPaired = false,
                        dashcamDisplayName = null,
                        radarBattery = BatteryEntry(
                            slug = "rearvue",
                            name = "RearVue",
                            pct = 78,
                            readAtMs = 0L,
                        ),
                        dashcamBattery = null,
                        haStatus = HaStatus.READY,
                        // Feature on but Flow not running: amber dot, "Waiting
                        // for Flow", and no battery chip (a stale SoC here would
                        // be a regression).
                        ebikeEnabled = true,
                        ebikeReceiving = false,
                        ebikeBatterySoc = 82,
                        onRowClick = {},
                    )
                }
            }
        }
    }
}
