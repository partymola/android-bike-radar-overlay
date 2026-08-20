// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The paired chip agrees in gender with the device its card is about, so it
 * reads "EMPAREJADO" beside el radar and "EMPAREJADA" beside la cámara.
 *
 * Rendered through [PairingStepContent], the leaf that owns both device
 * cards, because the wiring is where this goes wrong: the chip drew its own
 * wording from a single string, so every card got the masculine form no
 * matter what it was about. A test that composed the chip directly, or that
 * asserted the two resources differ, would have passed throughout.
 *
 * A wrong-gender string is user-visible and nothing else fails on it - no
 * lint check, no golden (the pairing goldens are English), and the app still
 * behaves correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+es")
class PairedChipGenderTest {

    @get:Rule val composeRule = createComposeRule()

    private fun showBothPaired() {
        composeRule.setContent {
            UiTheme {
                PairingStepContent(
                    radarBonded = true,
                    radarLocalName = "RearVue8",
                    radarMac = "00:11:22:33:44:55",
                    dashcamOwnership = DashcamOwnership.YES,
                    dashcamMac = "00:11:22:33:44:66",
                    dashcamDisplayName = "Vue-123",
                    onOpenBluetoothSettings = {},
                    onPickDashcam = {},
                    onDashcamSkip = {},
                    onDashcamReclaim = {},
                    onFinish = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the radar card reads masculine and the camera card reads feminine`() {
        showBothPaired()
        // Bound to each card, not merely present on the screen: asserting
        // both words exist somewhere passes just as happily if the two are
        // SWAPPED, which is the likelier regression now that both call sites
        // take a gender argument. The chip's Row is a sibling of the card's
        // title and the chip text is that Row's child, hence parent-of-
        // sibling rather than a direct sibling match.
        //
        // Literals, not the string resources: reading them back from the code
        // under test would keep this green whatever the two became, including
        // both becoming the same word again.
        composeRule.onNode(
            hasText("EMPAREJADO") and hasParent(hasAnySibling(hasText("Radar trasero"))),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNode(
            hasText("EMPAREJADA") and hasParent(hasAnySibling(hasText("Cámara delantera"))),
        ).performScrollTo().assertIsDisplayed()
    }
}
