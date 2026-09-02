// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.ui.UiTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the radar consent screen, the one surface where a
 * rider decides what another app may do with their radar.
 *
 * Variants:
 *  - firstAsk: no existing grant, both switches off
 *  - alreadyReading: an app that was granted read only, so the screen opens
 *    on what the rider last said rather than on nothing
 *
 * Verify with `:app:verifyRoborazziDebug`; regenerate with
 * `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class RadarConsentSnapshotTest {

    private fun ask(current: RadarGrant? = null) = ConsentRequest.Ask("com.example.trailbuddy", "Trail Buddy", current)

    @Test
    fun firstAsk() {
        captureRoboImage {
            UiTheme { RadarConsentAsk(request = ask(), onCancel = {}, onSave = { _, _ -> }) }
        }
    }

    @Test
    fun alreadyReading() {
        captureRoboImage {
            UiTheme {
                RadarConsentAsk(
                    request = ask(
                        RadarGrant(
                            packageName = "com.example.trailbuddy",
                            certDigest = "aa11",
                            label = "Trail Buddy",
                            grantedAtMs = 0L,
                            lastUsedAtMs = 0L,
                            read = true,
                            control = false,
                        ),
                    ),
                    onCancel = {},
                    onSave = { _, _ -> },
                )
            }
        }
    }

    /**
     * Spanish, where every string on this screen is longer than its English
     * original and the two toggle subtitles are the longest text the app puts
     * inside a row. This is the decision screen for handing another app the
     * rider's radar, so a subtitle that clips here costs them the sentence
     * saying what they are agreeing to.
     */
    @Test
    @Config(qualifiers = "+es")
    fun firstAskEs() {
        captureRoboImage {
            UiTheme { RadarConsentAsk(request = ask(), onCancel = {}, onSave = { _, _ -> }) }
        }
    }
}
