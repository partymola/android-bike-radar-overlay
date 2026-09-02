// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import es.jjrh.bikeradar.access.RadarGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rider cannot re-allow an app from this screen, only from the app itself,
 * so a mis-tap on a row costs them a round trip through the other app. These
 * pin that the row asks first and that the answer decides.
 *
 * The Roborazzi goldens for this screen cannot see any of it: they render the
 * list with no dialog open and stay green whether the row's action revokes
 * immediately or asks.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarAccessRevokeConfirmTest {

    @get:Rule val composeRule = createComposeRule()

    private var revoked: String? = null

    private fun grant(pkg: String, label: String) = RadarGrant(pkg, "aa11", label, 0L, 0L, read = true, control = false)

    private val trailBuddy = grant("com.example.trailbuddy", "Trail Buddy")
    private val navigator = grant("com.example.other", "Another Navigator")

    private fun show(grants: List<RadarGrant>) {
        composeRule.setContent {
            UiTheme {
                SettingsRadarAccessContent(grants = grants, onRevoke = { revoked = it }, onBack = {})
            }
        }
        composeRule.waitForIdle()
    }

    /** The row's own bin, never the confirm button inside the dialog. */
    private fun tapRow(index: Int) {
        composeRule.onAllNodesWithContentDescription("Stop sharing")[index].performClick()
        composeRule.waitForIdle()
    }

    private fun tapInDialog(text: String) {
        composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun theBinIsBigEnoughToHit() {
        // The drawn box is 34dp, which is under Android's 48dp minimum, and the
        // action is destructive. The confirmation dialog does not compensate:
        // it catches a tap the rider did not mean, where this catches a tap
        // they did mean and missed, which produces no dialog and no feedback.
        // Nothing else in the repo asserts a touch target, so without this the
        // modifier can be dropped and every gate stays green.
        show(listOf(trailBuddy))

        composeRule.onAllNodesWithContentDescription("Stop sharing")[0]
            .assertTouchHeightIsEqualTo(48.dp)
            .assertTouchWidthIsEqualTo(48.dp)
    }

    @Test
    fun tappingTheRowAsksAndRevokesNothingYet() {
        show(listOf(trailBuddy))

        tapRow(0)

        composeRule.onNodeWithText("Stop sharing with Trail Buddy?").assertExists()
        assertNull("the tap alone must not revoke", revoked)
    }

    @Test
    fun confirmingRevokes() {
        show(listOf(trailBuddy))

        tapRow(0)
        tapInDialog("Stop sharing")

        assertEquals("com.example.trailbuddy", revoked)
    }

    @Test
    fun cancellingRevokesNothingAndClosesTheDialog() {
        show(listOf(trailBuddy))

        tapRow(0)
        tapInDialog("Cancel")

        assertNull(revoked)
        composeRule.onNodeWithText("Stop sharing with Trail Buddy?").assertDoesNotExist()
    }

    @Test
    fun theWayOutStaysReachableWithARidiculousName() {
        // The label belongs to the app being revoked, so it is the one string
        // on this screen nobody here chose, and every other fixture in this
        // file is a short friendly name.
        //
        // What this pins is the DIALOG, not the `maxLines` cap on its title.
        // Measured both ways: the title renders 36dp with the cap and 36dp
        // without it, because Material3's AlertDialog scrolls its own content,
        // so an unbounded title cannot push the buttons off. The cap is for
        // legibility. This fails if the dialog is ever replaced by a layout
        // that does not scroll, which is the change that would make a long
        // label able to hide the way out.
        val shouty = "Trail Buddy " + "the very best cycling companion ".repeat(12)
        show(listOf(grant("com.example.trailbuddy", shouty)))

        tapRow(0)

        composeRule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        composeRule.onNode(hasText("Stop sharing") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun theRowThatWasTappedIsTheOneRevoked() {
        // A single-grant fixture passes just as well against a dialog wired to
        // the first grant in the list, which would revoke the wrong app for
        // every rider who has allowed more than one.
        show(listOf(trailBuddy, navigator))

        tapRow(1)

        composeRule.onNodeWithText("Stop sharing with Another Navigator?").assertExists()
        tapInDialog("Stop sharing")
        assertEquals("com.example.other", revoked)
    }
}
