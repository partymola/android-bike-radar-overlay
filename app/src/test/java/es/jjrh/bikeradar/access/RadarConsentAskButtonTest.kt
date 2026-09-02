// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/**
 * That the screen actually renders what `consentPrimaryAction` decides. The
 * pure rule passing says nothing about which button the rider sees, and the
 * goldens render one state each rather than following a toggle.
 */
@RunWith(RobolectricTestRunner::class)
class RadarConsentAskButtonTest {

    @get:Rule val composeRule = createComposeRule()

    /** The screen renders read first, then control. */
    private val readToggle = 0

    private var saved: Pair<Boolean, Boolean>? = null

    private fun grant(read: Boolean, control: Boolean) = RadarGrant("com.example.trailbuddy", "aa11", "Trail Buddy", 0L, 0L, read, control)

    private fun show(current: RadarGrant?) {
        composeRule.setContent {
            UiTheme {
                RadarConsentAsk(
                    request = ConsentRequest.Ask("com.example.trailbuddy", "Trail Buddy", current),
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
        tap("Allow")
        assertEquals(true to false, saved)
    }

    @Test
    fun turningEverythingOffOverAnExistingGrantOffersToStop() {
        show(current = grant(read = true, control = false))

        composeRule.onNodeWithText("Allow").assertIsEnabled()
        composeRule.onAllNodes(isToggleable())[readToggle].performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Stop sharing").assertIsEnabled()
        tap("Stop sharing")
        assertEquals(false to false, saved)
    }

    @Test
    fun theHelperLineKeepsItsSlotOnceSomethingIsChosen() {
        // The line above the buttons is only worth showing while nothing is
        // chosen. REMOVING it rather than emptying it lifts both buttons by a
        // line at the exact moment the rider's finger is already travelling
        // towards where Allow was, and where it would land is Don't allow.
        //
        // What this asserts is that the slot survives, not a pixel position.
        // Positions are not a usable measurement here: the screen sits in a
        // scroll container, so a reading taken before the toggle is scrolled
        // into view is not comparable with one taken after - an offset between
        // two nodes measured that way came back negative. So the height itself
        // is unpinned, and only a golden or a device would show it.
        show(current = null)
        assertEquals(1, composeRule.onAllNodesWithText(HELPER).fetchSemanticsNodes().size)
        val slots = composeRule.onAllNodes(hasText("")).fetchSemanticsNodes().size

        composeRule.onAllNodes(isToggleable())[readToggle].performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithText(HELPER).fetchSemanticsNodes().size)
        assertEquals(
            "the emptied line has to stay in the tree, or the buttons move up into it",
            slots + 1,
            composeRule.onAllNodes(hasText("")).fetchSemanticsNodes().size,
        )
    }

    private companion object {
        const val HELPER = "Choose at least one to allow."
    }
}
