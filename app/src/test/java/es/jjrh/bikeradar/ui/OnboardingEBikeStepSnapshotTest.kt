// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import es.jjrh.bikeradar.LdiOutcome
import es.jjrh.bikeradar.data.EBikeOwnership
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the [EBikeStepContent] onboarding leaf.
 * Renders the chooser (UNANSWERED), the advertising/walkthrough state
 * (YES + Advertising), the bonded success state (YES + Paired), and a
 * representative failure outcome (NoServiceFound).
 *
 * CI does not run these - Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class OnboardingEBikeStepSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun unanswered() {
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
