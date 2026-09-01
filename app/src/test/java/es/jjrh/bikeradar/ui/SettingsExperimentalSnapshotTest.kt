// SPDX-License-Identifier: GPL-3.0-or-later
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
                )
            }
        }
    }
}
