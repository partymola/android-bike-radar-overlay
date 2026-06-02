// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.BatteryEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the full home-screen chrome - [MainScreenContent].
 * Complements [MainScreenSnapshotTest] (which covers individual cards
 * - HeroStatusCard, SystemCard) by exercising the surrounding layout
 * (Bluetooth banner, ClosePassStatsCard, dashcam prompt) at full
 * portrait resolution.
 *
 * Variants picked from the user-visible state space:
 *  - idle: radar live, dashcam paired, HA healthy
 *  - withTraffic: radar live, close-pass logging on
 *  - paused: pausedUntilEpochMs > now -> Paused status + Resume CTA
 *  - dashcamWarning: dashcam off + walk-away alarm armed -> Warn tone
 *  - bluetoothOff: BLE disabled -> banner visible + BT-off hero
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class MainScreenContentSnapshotTest {

    private val radarBattery = BatteryEntry(
        slug = "rearvue",
        name = "RearVue",
        pct = 78,
        readAtMs = 0L,
    )

    private val dashcamBattery = BatteryEntry(
        slug = "front_cam",
        name = "Front cam",
        pct = 64,
        readAtMs = 0L,
    )

    /** Mirrors the production body's outer Box so the systemBars-padded
     *  background fills the whole snapshot, matching what the user sees. */
    @Composable
    private fun MainShell(content: @Composable () -> Unit) {
        val br = LocalBrColors.current
        Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
            content()
        }
    }

    @Test
    fun idle() {
        captureRoboImage {
            UiTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.CheckCircle,
                            tone = MainStatusTone.Good,
                            headline = "Radar live",
                            subtitle = "Dashcam on",
                        ),
                        cta = null,
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = true,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        dashcamDisplayName = "Front cam",
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haHealthy = true,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    fun fullyConfigured() {
        // Same as idle() but with the eBike/Flow card present and receiving -
        // the fully-configured rider used for the README main-screen shot.
        // idle() stays the radar-only (no-eBike) baseline.
        captureRoboImage {
            UiTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.CheckCircle,
                            tone = MainStatusTone.Good,
                            headline = "Radar live",
                            subtitle = "Dashcam on",
                        ),
                        cta = null,
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = true,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        dashcamDisplayName = "Front cam",
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haHealthy = true,
                        eBikeDataEnabled = true,
                        ebikeReceiving = true,
                        ebikeBatterySoc = 80,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    fun withTraffic() {
        captureRoboImage {
            UiTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.CheckCircle,
                            tone = MainStatusTone.Good,
                            headline = "Radar live",
                            subtitle = "Vehicle approaching",
                        ),
                        cta = null,
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = true,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        dashcamDisplayName = "Front cam",
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haHealthy = true,
                        closePassLoggingEnabled = true,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    fun paused() {
        captureRoboImage {
            UiTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.PauseCircle,
                            tone = MainStatusTone.Neutral,
                            headline = "Paused",
                            subtitle = "Resumes 18:30",
                        ),
                        cta = StatusCta(label = "Resume", onClick = {}),
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = false,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = false,
                        dashcamPaired = true,
                        dashcamDisplayName = "Front cam",
                        radarBattery = null,
                        dashcamBattery = null,
                        haHealthy = true,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    fun dashcamWarning() {
        captureRoboImage {
            UiTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.Warning,
                            tone = MainStatusTone.Warn,
                            headline = "Dashcam off",
                            subtitle = "Front cam not broadcasting",
                        ),
                        cta = null,
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = true,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = false,
                        dashcamPaired = true,
                        dashcamDisplayName = "Front cam",
                        radarBattery = radarBattery,
                        dashcamBattery = null,
                        haHealthy = true,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    fun bluetoothOff() {
        captureRoboImage {
            UiTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.BluetoothDisabled,
                            tone = MainStatusTone.Warn,
                            headline = "Bluetooth off",
                            subtitle = "Turn on Bluetooth to scan",
                        ),
                        cta = StatusCta(label = "Turn on Bluetooth", onClick = {}),
                        btEnabled = false,
                        showBtOffBanner = true,
                        showDashcamPrompt = false,
                        radarFresh = false,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = false,
                        dashcamPaired = true,
                        dashcamDisplayName = "Front cam",
                        radarBattery = null,
                        dashcamBattery = null,
                        haHealthy = false,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }
}
