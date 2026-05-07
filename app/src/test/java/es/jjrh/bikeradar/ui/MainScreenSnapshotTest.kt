// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import es.jjrh.bikeradar.BatteryEntry
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the home-screen leaf cards: [HeroStatusCard]
 * and [SystemCard]. Renders each leaf directly with stub state so the
 * test does not depend on Prefs, the radar/battery/HA buses, or the
 * lifecycle-driven pollers in [MainScreen].
 *
 * Variants picked to exercise the visually distinct states:
 *  - Hero: live-good (CheckCircle / Good) and not-paired-bt-on (Error)
 *  - System: empty (no devices live) and populated (radar + dashcam +
 *    HA all green with battery chips)
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class MainScreenSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

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
        paparazzi.snapshot {
            UiTheme {
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
        paparazzi.snapshot {
            UiTheme {
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
        paparazzi.snapshot {
            UiTheme {
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
                        haHealthy = false,
                    )
                }
            }
        }
    }

    @Test
    fun systemPopulated() {
        paparazzi.snapshot {
            UiTheme {
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
                        haHealthy = true,
                    )
                }
            }
        }
    }
}
