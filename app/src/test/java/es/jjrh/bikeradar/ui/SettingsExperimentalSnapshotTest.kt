// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.runtime.Composable
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

    @Composable
    private fun Screen(
        urgentWaitEnabled: Boolean = false,
        precogEnabled: Boolean = false,
        radarDropTrackFallbackEnabled: Boolean = false,
        radarDropTrackWindowSec: Int = 30,
    ) {
        UiTheme {
            SettingsExperimentalContent(
                navController = rememberNavController(),
                urgentWaitEnabled = urgentWaitEnabled,
                onUrgentWaitChange = {},
                precogEnabled = precogEnabled,
                onPrecogChange = {},
                radarDropTrackFallbackEnabled = radarDropTrackFallbackEnabled,
                onRadarDropTrackFallbackChange = {},
                radarDropTrackWindowSec = radarDropTrackWindowSec,
                onRadarDropTrackWindowChange = {},
                onRadarDropTrackWindowFinished = {},
            )
        }
    }

    @Test
    fun allOff() {
        captureRoboImage { Screen() }
    }

    @Test
    fun urgentWaitOn() {
        captureRoboImage { Screen(urgentWaitEnabled = true) }
    }

    @Test
    fun precogOn() {
        captureRoboImage { Screen(precogEnabled = true) }
    }

    /** The state a fresh install is actually in: the drop fallback defaults on. */
    @Test
    fun dropFallbackOn() {
        captureRoboImage { Screen(radarDropTrackFallbackEnabled = true) }
    }

    /** The minutes form of the window label, and the slider away from its
     *  first stop. The seconds form is in `dropFallbackOn`. */
    @Test
    fun dropWindowStretched() {
        captureRoboImage { Screen(radarDropTrackFallbackEnabled = true, radarDropTrackWindowSec = 600) }
    }

    /** The rung the label switches form on. Seconds below a minute, whole
     *  minutes above it, and 60 s is the first value on the minutes side. */
    @Test
    fun dropWindowAtTheMinuteBoundary() {
        captureRoboImage { Screen(radarDropTrackFallbackEnabled = true, radarDropTrackWindowSec = 60) }
    }

    /** Spanish, at the width the layout is tightest: this screen's titles and
     *  helper text are where es runs longest, and a golden is the only thing
     *  that shows clipping or a wrap the English never hits. */
    @Test
    @Config(qualifiers = "+es")
    fun dropWindowStretchedEs() {
        captureRoboImage { Screen(radarDropTrackFallbackEnabled = true, radarDropTrackWindowSec = 600) }
    }
}
