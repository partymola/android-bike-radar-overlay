// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Experimental screen, in each toggle state.
 * Renders the stateless [SettingsExperimentalContent] leaf so no Prefs
 * scaffolding is needed.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsExperimentalSnapshotTest {

    @Test
    fun allOff() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
                    radarDropTrackFallbackEnabled = false,
                    onRadarDropTrackFallbackChange = {},
                    radarDropTrackWindowSec = 30,
                    onRadarDropTrackWindowChange = {},
                    onRadarDropTrackWindowFinished = {},
                )
            }
        }
    }

    @Test
    fun precogOn() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = true,
                    onPrecogChange = {},
                    radarDropTrackFallbackEnabled = false,
                    onRadarDropTrackFallbackChange = {},
                    radarDropTrackWindowSec = 30,
                    onRadarDropTrackWindowChange = {},
                    onRadarDropTrackWindowFinished = {},
                )
            }
        }
    }

    /** The state a fresh install is actually in: the drop fallback defaults on. */
    @Test
    fun dropFallbackOn() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
                    radarDropTrackFallbackEnabled = true,
                    onRadarDropTrackFallbackChange = {},
                    radarDropTrackWindowSec = 30,
                    onRadarDropTrackWindowChange = {},
                    onRadarDropTrackWindowFinished = {},
                )
            }
        }
    }

    /** The minutes form of the window label, and the slider away from its
     *  first stop. The seconds form is covered by every other case here. */
    @Test
    fun dropWindowStretched() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
                    radarDropTrackFallbackEnabled = true,
                    onRadarDropTrackFallbackChange = {},
                    radarDropTrackWindowSec = 600,
                    onRadarDropTrackWindowChange = {},
                    onRadarDropTrackWindowFinished = {},
                )
            }
        }
    }

    /** The rung the label switches form on. Seconds below a minute, whole
     *  minutes above it, and 60 s is the first value on the minutes side. */
    @Test
    fun dropWindowAtTheMinuteBoundary() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
                    radarDropTrackFallbackEnabled = true,
                    onRadarDropTrackFallbackChange = {},
                    radarDropTrackWindowSec = 60,
                    onRadarDropTrackWindowChange = {},
                    onRadarDropTrackWindowFinished = {},
                )
            }
        }
    }

    /** Spanish, at the width the layout is tightest: this screen's titles and
     *  helper text are where es runs longest, and a golden is the only thing
     *  that shows clipping or a wrap the English never hits. */
    @Test
    @Config(qualifiers = "+es")
    fun dropWindowStretchedEs() {
        captureRoboImage {
            UiTheme {
                SettingsExperimentalContent(
                    navController = rememberNavController(),
                    precogEnabled = false,
                    onPrecogChange = {},
                    radarDropTrackFallbackEnabled = true,
                    onRadarDropTrackFallbackChange = {},
                    radarDropTrackWindowSec = 600,
                    onRadarDropTrackWindowChange = {},
                    onRadarDropTrackWindowFinished = {},
                )
            }
        }
    }
}
