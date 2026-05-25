// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.LdiOutcome
import es.jjrh.bikeradar.data.EBikeOwnership
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the [EBikeStepContent] onboarding leaf.
 * Renders the chooser (UNANSWERED), the advertising/walkthrough state
 * (YES + Advertising), the bonded success state (YES + Paired), and a
 * representative failure outcome (NoServiceFound).
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
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
                    bondedAddress = null,
                    outcome = LdiOutcome.Idle,
                    onChooseHave = {},
                    onChooseDontHave = {},
                    onOpenFlow = {},
                    onOpenPermissionSettings = {},
                    onTryAgain = {},
                    onUnpairAndRepair = {},
                    onSkipForNow = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun yesAdvertising() {
        captureRoboImage {
            UiTheme {
                EBikeStepContent(
                    ownership = EBikeOwnership.YES,
                    bondedAddress = null,
                    outcome = LdiOutcome.Advertising,
                    onChooseHave = {},
                    onChooseDontHave = {},
                    onOpenFlow = {},
                    onOpenPermissionSettings = {},
                    onTryAgain = {},
                    onUnpairAndRepair = {},
                    onSkipForNow = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun yesPaired() {
        captureRoboImage {
            UiTheme {
                EBikeStepContent(
                    ownership = EBikeOwnership.YES,
                    bondedAddress = "AA:BB:CC:DD:EE:FF",
                    outcome = LdiOutcome.Paired("AA:BB:CC:DD:EE:FF"),
                    onChooseHave = {},
                    onChooseDontHave = {},
                    onOpenFlow = {},
                    onOpenPermissionSettings = {},
                    onTryAgain = {},
                    onUnpairAndRepair = {},
                    onSkipForNow = {},
                    onFinish = {},
                )
            }
        }
    }

    @Test
    fun yesNoServiceFound() {
        captureRoboImage {
            UiTheme {
                EBikeStepContent(
                    ownership = EBikeOwnership.YES,
                    bondedAddress = null,
                    outcome = LdiOutcome.NoServiceFound,
                    onChooseHave = {},
                    onChooseDontHave = {},
                    onOpenFlow = {},
                    onOpenPermissionSettings = {},
                    onTryAgain = {},
                    onUnpairAndRepair = {},
                    onSkipForNow = {},
                    onFinish = {},
                )
            }
        }
    }
}
