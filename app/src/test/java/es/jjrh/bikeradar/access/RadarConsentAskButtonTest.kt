// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    fun aFirstAskWithNothingChosenCannotBeConfirmed() {
        show(current = null)

        composeRule.onNodeWithText("Allow").assertIsNotEnabled()
        composeRule.onNodeWithText("Allow").performClick()
        composeRule.waitForIdle()

        assertNull("a disabled button must not answer for the rider", saved)
    }

    @Test
    fun choosingSomethingEnablesIt() {
        show(current = null)

        composeRule.onAllNodes(isToggleable())[readToggle].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Allow").assertIsEnabled()
        composeRule.onNodeWithText("Allow").performClick()
        composeRule.waitForIdle()
        assertEquals(true to false, saved)
    }

    @Test
    fun turningEverythingOffOverAnExistingGrantOffersToStop() {
        show(current = grant(read = true, control = false))

        composeRule.onNodeWithText("Allow").assertIsEnabled()
        composeRule.onAllNodes(isToggleable())[readToggle].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Stop sharing").assertIsEnabled()
        composeRule.onNodeWithText("Stop sharing").performClick()
        composeRule.waitForIdle()
        assertEquals(false to false, saved)
    }
}
