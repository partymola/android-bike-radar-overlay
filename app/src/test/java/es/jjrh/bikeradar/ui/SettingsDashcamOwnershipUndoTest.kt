// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * Turning the ownership switch off must keep the rider's picked camera.
 *
 * The whole row is the tap target, so this is one accidental tap away, and
 * nothing else reaches the handler: the goldens render states rather than
 * driving them, and `DashcamOwnershipGateTest` exercises `Prefs` directly.
 * Restore the switch's three clearing assignments and every other test still
 * passes.
 *
 * Keeping the pick is what makes a way to clear it necessary, so the Clear
 * row is driven here too: the goldens cannot tell a wired row from a drawn one.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDashcamOwnershipUndoTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mac = "11:22:33:44:55:66"

    private fun prefsWithACameraInUse() = Prefs(app).apply {
        dashcamOwnership = DashcamOwnership.YES
        dashcamMac = this@SettingsDashcamOwnershipUndoTest.mac
        dashcamDisplayName = "Front Cam"
        dashcamWarnWhenOff = true
    }

    private fun prefsWithARememberedCamera(ownership: DashcamOwnership) = prefsWithACameraInUse().apply { dashcamOwnership = ownership }

    private fun show(prefs: Prefs) {
        composeRule.setContent {
            UiTheme {
                SettingsDashcam(navController = rememberNavController(), prefs = prefs)
            }
        }
        composeRule.waitForIdle()
    }

    private fun tapTheOwnershipRow() {
        // By title, which is what the rider reads, and the row carries it.
        composeRule.onNodeWithText(app.getString(R.string.settings_dashcam_have_dashcam)).performClick()
        composeRule.waitForIdle()
    }

    private fun showAndTapTheOwnershipRow(prefs: Prefs) {
        show(prefs)
        tapTheOwnershipRow()
    }

    @Test
    fun switchingOwnershipOffKeepsThePickAndStopsUsingIt() {
        val prefs = prefsWithACameraInUse()
        showAndTapTheOwnershipRow(prefs)

        assertEquals("the switch must record the rider's answer", DashcamOwnership.NO, prefs.dashcamOwnership)
        assertNull("nothing may use the camera while the switch is off", prefs.activeDashcamMac)
        assertEquals("the pick must survive, so switching back on is an undo", mac, prefs.dashcamMac)
        assertEquals("and so must the name it is shown under", "Front Cam", prefs.dashcamDisplayName)
        assertTrue("and the warn-when-off choice it was paired with", prefs.dashcamWarnWhenOff)
    }

    @Test
    fun switchingItBackOnRestoresTheSameCamera() {
        val prefs = prefsWithACameraInUse()
        showAndTapTheOwnershipRow(prefs)
        assertNull(prefs.activeDashcamMac)

        composeRule.onNodeWithText(app.getString(R.string.settings_dashcam_have_dashcam)).performClick()
        composeRule.waitForIdle()

        assertEquals(DashcamOwnership.YES, prefs.dashcamOwnership)
        assertEquals("the rider must get their camera back without re-picking it", mac, prefs.activeDashcamMac)
    }

    // The words are literals, not resource reads: a row whose label went
    // missing would still match itself.

    @Test
    fun aSwitchedOffCameraIsNamedAndCanBeCleared() {
        show(prefsWithARememberedCamera(DashcamOwnership.NO))

        composeRule.onNodeWithText("Saved dashcam").assertIsDisplayed()
        composeRule.onNodeWithText("Front Cam").assertIsDisplayed()
        composeRule.onNodeWithText("Clear").assertIsDisplayed()
    }

    @Test
    fun aRiderWhoNeverAnsweredCanStillClearTheirPick() {
        // Reachable: pick a camera, decline it in onboarding, then take the
        // decline back.
        val prefs = prefsWithARememberedCamera(DashcamOwnership.UNANSWERED)
        show(prefs)

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitForIdle()

        assertNull(prefs.dashcamMac)
        assertEquals("clearing a camera must not answer for them", DashcamOwnership.UNANSWERED, prefs.dashcamOwnership)
    }

    @Test
    fun aCameraSavedWithoutANameIsShownByItsAddress() {
        show(prefsWithARememberedCamera(DashcamOwnership.NO).apply { dashcamDisplayName = null })

        composeRule.onNodeWithText(mac).assertIsDisplayed()
    }

    @Test
    fun clearingSaysSoBecauseTheRowItselfDisappears() {
        show(prefsWithARememberedCamera(DashcamOwnership.NO))

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitForIdle()

        assertEquals("Saved dashcam cleared", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aCameraInUseIsChangedFromItsCardNotCleared() {
        show(prefsWithACameraInUse())

        composeRule.onNodeWithText("Clear").assertDoesNotExist()
        composeRule.onNodeWithText("Saved dashcam").assertDoesNotExist()
    }

    @Test
    fun withNothingRememberedThereIsNothingToClear() {
        show(Prefs(app).apply { dashcamOwnership = DashcamOwnership.NO })

        composeRule.onNodeWithText("Clear").assertDoesNotExist()
    }

    @Test
    fun clearingStopsRememberingTheCameraAndLeavesTheSwitchAlone() {
        val prefs = prefsWithARememberedCamera(DashcamOwnership.NO)
        show(prefs)

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitForIdle()

        assertNull(prefs.dashcamMac)
        assertNull(prefs.dashcamDisplayName)
        assertFalse(prefs.dashcamWarnWhenOff)
        assertEquals("the rider said no camera, and clearing one agrees", DashcamOwnership.NO, prefs.dashcamOwnership)
        composeRule.onNodeWithText("Clear").assertDoesNotExist()
    }

    @Test
    fun afterClearingTheSwitchAsksForAPickRatherThanRestoringOne() {
        val prefs = prefsWithARememberedCamera(DashcamOwnership.NO)
        show(prefs)
        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitForIdle()

        tapTheOwnershipRow()

        composeRule.onNodeWithText("Pick").assertIsDisplayed()
        composeRule.onNodeWithText("Front Cam").assertDoesNotExist()
        assertNull(prefs.activeDashcamMac)
    }
}
