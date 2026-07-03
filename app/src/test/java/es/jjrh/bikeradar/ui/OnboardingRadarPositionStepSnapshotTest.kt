// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the onboarding [RadarPositionStepContent] leaf:
 * the default centred render (the state every new rider lands on) and an
 * off-centre one exercising the worded "N cm right" readout, mirroring
 * the Settings screen's pair.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class OnboardingRadarPositionStepSnapshotTest {

    @Test
    fun centred() {
        captureRoboImage {
            UiTheme {
                RadarPositionStepContent(
                    offsetCm = 0,
                    onOffsetChange = {},
                    onOffsetCommit = {},
                    onContinue = {},
                )
            }
        }
    }

    @Test
    fun offsetRight() {
        captureRoboImage {
            UiTheme {
                RadarPositionStepContent(
                    offsetCm = 15,
                    onOffsetChange = {},
                    onOffsetCommit = {},
                    onContinue = {},
                )
            }
        }
    }
}
