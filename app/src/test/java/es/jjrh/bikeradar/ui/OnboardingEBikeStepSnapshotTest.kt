// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.data.EBikeOwnership
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the [EBikeStepContent] onboarding leaf: the chooser
 * (UNANSWERED), and the YES "how it works" body in its three adaptive-CTA
 * states - Flow not installed (Install), Flow installed but no data yet
 * (Open), and receiving (a confirmation, no button). There is no pairing
 * walkthrough; the feature just reads live data while Bosch Flow is open.
 *
 * Renders via Robolectric Native Graphics. Verify with
 * `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class OnboardingEBikeStepSnapshotTest {

    @Test
    fun unanswered() {
        captureRoboImage {
            UiTheme {
                EBikeStepContent(
                    ownership = EBikeOwnership.UNANSWERED,
                    receiving = false,
                    flowInstalled = true,
                    onChooseHave = {},
                    onChooseDontHave = {},
                    onOpenFlow = {},
                    onBack = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun yesInstallFlow() {
        captureRoboImage {
            UiTheme {
                EBikeStepContent(
                    ownership = EBikeOwnership.YES,
                    receiving = false,
                    flowInstalled = false,
                    onChooseHave = {},
                    onChooseDontHave = {},
                    onOpenFlow = {},
                    onBack = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun yesOpenFlow() {
        captureRoboImage {
            UiTheme {
                EBikeStepContent(
                    ownership = EBikeOwnership.YES,
                    receiving = false,
                    flowInstalled = true,
                    onChooseHave = {},
                    onChooseDontHave = {},
                    onOpenFlow = {},
                    onBack = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun yesReceiving() {
        captureRoboImage {
            UiTheme {
                EBikeStepContent(
                    ownership = EBikeOwnership.YES,
                    receiving = true,
                    flowInstalled = true,
                    onChooseHave = {},
                    onChooseDontHave = {},
                    onOpenFlow = {},
                    onBack = {},
                    onFinish = {},
                )
            }
        }
    }
}
