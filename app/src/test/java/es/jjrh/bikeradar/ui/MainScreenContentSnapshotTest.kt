// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.ClosePassStateBus
import es.jjrh.bikeradar.EBikeStage
import es.jjrh.bikeradar.HaStatus
import es.jjrh.bikeradar.R
import org.junit.Before
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

    /** [ClosePassStateBus] is a process-wide singleton; reset it before every
     *  test so a variant that seeds a session count (fullShowcase) can't bleed
     *  into another variant's golden. */
    @Before
    fun resetClosePassBus() {
        ClosePassStateBus.reset()
    }

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
            SnapshotTheme {
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
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    // The two row states the home screen used to lack. Without them a
    // range-only radar rendered a green "Live" identical to a healthy one
    // while the urgent warning was held shut, and a radar the app was
    // actively retrying rendered "No signal".
    @Test
    fun radarLimitedSource() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.Warning,
                            tone = MainStatusTone.Warn,
                            headline = "Radar live, distance only",
                            subtitle = "No urgent warning from this radar",
                        ),
                        cta = null,
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = true,
                        radarLimited = true,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    // The eBike row must name the app's OWN precondition - no bike paired -
    // rather than blaming a third-party app for it. Portrait; the landscape
    // variant below is a separate call site and needs its own golden.
    @Test
    fun ebikeNotPaired() {
        captureRoboImage {
            SnapshotTheme {
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
                        eBikeDataEnabled = true,
                        ebikeStage = EBikeStage.NO_BONDED_BIKE,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    // The same state in LANDSCAPE, which is a separate SystemCard call site.
    // Without this golden the stage can be dropped from that call and nothing
    // fails: the row falls back to the parameter's default and every other
    // test renders the default anyway. That is how it came to say "Waiting for
    // Flow" in one orientation and the truth in the other.
    @Test
    fun ebikeNotPairedLandscape() {
        captureRoboImage {
            SnapshotTheme {
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
                        eBikeDataEnabled = true,
                        ebikeStage = EBikeStage.NO_BONDED_BIKE,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = false,
                        isLandscape = true,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    // Landscape has its own SystemCard call site and, until this, no golden
    // at all - so the row states were verified in one orientation only.
    @Test
    fun radarLimitedSourceLandscape() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.Warning,
                            tone = MainStatusTone.Warn,
                            headline = "Radar live, distance only",
                            subtitle = "No urgent warning from this radar",
                        ),
                        cta = null,
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = true,
                        radarLimited = true,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = false,
                        isLandscape = true,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    fun radarConnecting() {
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.CheckCircle,
                            tone = MainStatusTone.Good,
                            headline = "Connecting",
                            subtitle = "Setting up the radar",
                        ),
                        cta = null,
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = false,
                        radarConnecting = true,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = true,
                        dashcamPaired = true,
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
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
            SnapshotTheme {
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
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        eBikeDataEnabled = true,
                        ebikeReceiving = true,
                        ebikeBatterySoc = 80,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun fullyConfiguredEs() {
        // The fully-configured main screen rendered in Spanish (values-es) -
        // the README language-gallery shot, and a guard that the es copy fits
        // the layout. Hero text comes from string resources so it translates;
        // the rest of MainScreenContent already resolves res IDs.
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.CheckCircle,
                            tone = MainStatusTone.Good,
                            headline = stringResource(R.string.main_status_live_title),
                            subtitle = stringResource(R.string.main_status_live_dashcam_on_sub),
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
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        eBikeDataEnabled = true,
                        ebikeReceiving = true,
                        ebikeBatterySoc = 80,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    fun fullShowcase() {
        // The README main-screen shot: every accessory connected (radar,
        // dashcam, eBike, Home Assistant) with battery levels, AND close-pass
        // counting live with a few passes logged this ride - the full feature
        // set in one frame. fullyConfigured stays the counting-off variant.
        ClosePassStateBus.increment(3)
        captureRoboImage {
            SnapshotTheme {
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
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        eBikeDataEnabled = true,
                        ebikeReceiving = true,
                        ebikeBatterySoc = 80,
                        closePassLoggingEnabled = true,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun fullShowcaseEs() {
        // Spanish twin of fullShowcase - the "En español" README shot.
        ClosePassStateBus.increment(3)
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.CheckCircle,
                            tone = MainStatusTone.Good,
                            headline = stringResource(R.string.main_status_live_title),
                            subtitle = stringResource(R.string.main_status_live_dashcam_on_sub),
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
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        eBikeDataEnabled = true,
                        ebikeReceiving = true,
                        ebikeBatterySoc = 80,
                        closePassLoggingEnabled = true,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
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
            SnapshotTheme {
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
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = true,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
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
            SnapshotTheme {
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
                        radarBattery = null,
                        dashcamBattery = null,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    fun radarDownWithParkedOffered() {
        // The only state the parked declaration is offered in: the radar has
        // been down past the banner threshold in a session that had one.
        // Rendered because the control suppresses a safety cue.
        //
        // The deriver never returns Warning/Warn here, so a fixture that builds
        // one is a picture of a screen the app cannot render. What pins the
        // icon and tone is `offeringParkedDoesNotRepaintTheCard`, not this
        // golden, which only shows what the chosen values look like.
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.Sensors,
                            tone = MainStatusTone.Neutral,
                            headline = stringResource(R.string.main_status_ride_over_title),
                            subtitle = stringResource(R.string.main_status_ride_over_sub),
                        ),
                        cta = StatusCta(label = stringResource(R.string.main_cta_parked), onClick = {}),
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = false,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = false,
                        dashcamPaired = true,
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = true,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun radarDownWithParkedOfferedEs() {
        // The Spanish layout of the same card. The es title runs longer than
        // the English, and the repo rule is to verify a new es label against
        // the golden rather than reasoning about its width.
        captureRoboImage {
            SnapshotTheme {
                MainShell {
                    MainScreenContent(
                        status = MainStatus(
                            icon = MainStatusIcon.Sensors,
                            tone = MainStatusTone.Neutral,
                            headline = stringResource(R.string.main_status_ride_over_title),
                            subtitle = stringResource(R.string.main_status_ride_over_sub),
                        ),
                        cta = StatusCta(label = stringResource(R.string.main_cta_parked), onClick = {}),
                        btEnabled = true,
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = false,
                        hasBond = true,
                        dashcamOwned = true,
                        dashcamFresh = false,
                        dashcamPaired = true,
                        radarBattery = radarBattery,
                        dashcamBattery = dashcamBattery,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = true,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
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
            SnapshotTheme {
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
                        radarBattery = radarBattery,
                        dashcamBattery = null,
                        haStatus = HaStatus.READY,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
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
            SnapshotTheme {
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
                        // False, because the hero IS the Bluetooth-off card
                        // here. Production computes this as `!btEnabled &&
                        // !heroIsBtOff`, so it never shows both; a fixture
                        // passing true rendered the message twice, which the
                        // rider never sees.
                        showBtOffBanner = false,
                        showDashcamPrompt = false,
                        radarFresh = false,
                        // hasBond FALSE with the radio off, because that is
                        // the only pair hardware produces: getBondedDevices
                        // returns an empty set while the adapter is down, so
                        // the app cannot know a radar is bonded. Robolectric's
                        // shadow ignores adapter state, so nothing else here
                        // would have caught the impossible combination.
                        hasBond = false,
                        dashcamOwned = true,
                        dashcamFresh = false,
                        dashcamPaired = true,
                        radarBattery = null,
                        dashcamBattery = null,
                        haStatus = HaStatus.UNREACHABLE,
                        closePassLoggingEnabled = false,
                        isLandscape = false,
                        onWordmarkLongPress = {},
                        onBtBannerTap = {},
                        onSettingsClick = {},
                        onSystemRowClick = {},
                        onDashcamYes = {},
                        onDashcamNo = {},
                    )
                }
            }
        }
    }
}
