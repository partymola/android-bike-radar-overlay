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
import es.jjrh.bikeradar.BatteryEntry
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
 *  - System: empty (no devices live), populated (radar + dashcam + eBike +
 *    HA all green with battery chips), and eBike-waiting (the eBike row in
 *    its amber "Waiting for Flow" state with no stale battery chip)
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
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
                        ebikeEnabled = true,
                        ebikeReceiving = true,
                        ebikeBatterySoc = 82,
                    )
                }
            }
        }
    }

    @Test
    fun systemEbikeWaiting() {
        captureRoboImage {
            UiTheme {
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
                        haHealthy = true,
                        // Feature on but Flow not running: amber dot, "Waiting
                        // for Flow", and no battery chip (a stale SoC here would
                        // be a regression).
                        ebikeEnabled = true,
                        ebikeReceiving = false,
                        ebikeBatterySoc = 82,
                    )
                }
            }
        }
    }
}
