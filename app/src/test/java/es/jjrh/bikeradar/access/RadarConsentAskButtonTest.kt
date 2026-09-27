// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.access

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import es.jjrh.bikeradar.ui.UiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * That the screen actually renders what `consentPrimaryAction` decides, what
 * the second button and the line above the buttons say, and that the buttons
 * stay put as the switches change. The pure rule passing says nothing about
 * which button the rider sees, and the goldens render one state each rather
 * than following a toggle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadarConsentAskButtonTest {

    @get:Rule val composeRule = createComposeRule()

    /** The screen renders read first, then control. */
    private val readToggle = 0

    private var saved: Pair<Boolean, Boolean>? = null

    private fun grant(read: Boolean, control: Boolean) = RadarGrant("com.example.trailbuddy", "aa11", "Trail Buddy", 0L, 0L, read, control)

    private fun show(current: RadarGrant?, setUp: Boolean = true) {
        composeRule.setContent {
            UiTheme {
                RadarConsentAsk(
                    request = ConsentRequest.Ask("com.example.trailbuddy", "Trail Buddy", current),
                    bikeRadarSetUp = setUp,
                    onCancel = {},
                    onSave = { r, c -> saved = r to c },
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The screen scrolls and the buttons sit at the bottom of it, so a tap
     * aimed off-screen is dropped with no error - which reads as "the button
     * did nothing", the very thing these tests are checking for. Scrolling
     * first is what makes a click that lands prove something.
     */
    private fun tap(label: String) {
        composeRule.onNodeWithText(label).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun aFirstAskWithNothingChosenCannotBeConfirmed() {
        show(current = null)

        composeRule.onNodeWithText("Allow").assertIsNotEnabled()
        tap("Allow")

        assertNull("a disabled button must not answer for the rider", saved)
    }

    @Test
    fun choosingSomethingEnablesIt() {
        show(current = null)

        composeRule.onAllNodes(isToggleable())[readToggle].performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Allow").assertIsEnabled()
        composeRule.onNodeWithText(HOW_TO_STOP).assertDoesNotExist()
        tap("Allow")
        assertEquals(true to false, saved)
    }

    @Test
    fun turningEverythingOffOverAnExistingGrantOffersToStop() {
        show(current = grant(read = true, control = false))

        composeRule.onNodeWithText("Allow").assertIsEnabled()
        composeRule.onNodeWithText(HOW_TO_STOP).assertExists()
        composeRule.onAllNodes(isToggleable())[readToggle].performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(HOW_TO_STOP).assertDoesNotExist()
        composeRule.onNodeWithText("Stop sharing").assertIsEnabled()
        tap("Stop sharing")
        assertEquals(false to false, saved)
    }

    /**
     * The second button changes nothing, so over an existing grant it must not
     * read "Don't allow": a rider who came back to stop an app would tap it and
     * keep sharing. Stopping is the primary button with both switches off.
     */
    @Test
    fun overAnExistingGrantTheSecondButtonSaysCancel() = assertCancelAndHowToStop(grant(read = true, control = false))

    /** The grant shape where the rider most wants the off switch: the app can act, not just read. */
    @Test
    fun overAControlOnlyGrantTooTheScreenSaysCancelAndHowToStop() = assertCancelAndHowToStop(grant(read = false, control = true))

    @Test
    fun overAFullGrantTooTheScreenSaysCancelAndHowToStop() = assertCancelAndHowToStop(grant(read = true, control = true))

    private fun assertCancelAndHowToStop(current: RadarGrant) {
        show(current = current)

        composeRule.onNodeWithText("Cancel").assertExists()
        composeRule.onNodeWithText("Don't allow").assertDoesNotExist()
        composeRule.onNodeWithText(HOW_TO_STOP).assertExists()
    }

    /** A grant can be made before setup is finished, so the line shows over one too. */
    @Test
    fun theSetupLineShowsOverAnExistingGrantToo() {
        show(current = grant(read = true, control = false), setUp = false)
        composeRule.onNodeWithText("Bike Radar isn't set up yet.", substring = true).assertExists()
    }

    /**
     * The slot above the buttons must not change height when its text does,
     * or the buttons move under a finger already on its way. Measured where a
     * line is most likely to wrap: a narrow phone at a large font size, and in
     * Spanish, where the stop-sharing line runs a line longer than the other
     * one, so a slot sized for the wrong line shows up here. The gap is taken
     * between two nodes in the same scrolling column, so it does not depend on
     * how far the screen has scrolled.
     */
    @Test
    @Config(qualifiers = "es-w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun theButtonsStayPutWhenStoppingIsOffered() {
        show(current = grant(read = true, control = false))
        val before = gapAboveThePrimaryButton("Permitir", "Tu elección se guarda")

        composeRule.onAllNodes(isToggleable())[readToggle].performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(before, gapAboveThePrimaryButton("Dejar de compartir", "Tu elección se guarda"))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun theButtonsStayPutWhenAFirstChoiceIsMade() {
        show(current = null)
        composeRule.onNodeWithText(HELPER).assertExists()
        val before = gapAboveThePrimaryButton("Allow", BACKUP_NOTE_START)

        composeRule.onAllNodes(isToggleable())[readToggle].performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Allow").assertIsEnabled()
        composeRule.onNodeWithText(HELPER).assertDoesNotExist()
        assertEquals(before, gapAboveThePrimaryButton("Allow", BACKUP_NOTE_START))
    }

    // Unclipped: at a large font size the buttons sit below the visible screen,
    // where clipped bounds read as zero.
    private fun gapAboveThePrimaryButton(label: String, noteStart: String): Float {
        val note = composeRule.onNodeWithText(noteStart, substring = true).fetchSemanticsNode()
        val button = composeRule.onNodeWithText(label).fetchSemanticsNode()
        return button.positionInRoot.y - (note.positionInRoot.y + note.size.height)
    }

    @Test
    fun aFirstAskStillOffersDontAllow() {
        show(current = null)

        composeRule.onNodeWithText("Don't allow").assertExists()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithText(HOW_TO_STOP).assertDoesNotExist()
    }

    private companion object {
        const val HELPER = "Choose at least one to allow."
        const val HOW_TO_STOP = "Turn both off to stop sharing."
        const val BACKUP_NOTE_START = "Your choice is kept on this phone"
    }
}
