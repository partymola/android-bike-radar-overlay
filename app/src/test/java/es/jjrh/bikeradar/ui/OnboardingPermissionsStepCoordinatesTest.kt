// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the coordinates affordance on the SHIPPED onboarding permissions step.
 *
 * `OnboardingPermissionsStepSnapshotTest` renders `PermissionsStepContent` with
 * its own summary and callbacks injected, so it cannot see whether
 * `PermissionsStep` - the composable onboarding actually runs - holds any state
 * or shows the dialog at all. This is the rider who declined the location
 * grant, which is the rider the coordinate path exists for.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingPermissionsStepCoordinatesTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app)
        prefs.setManualLocation(null, null)
    }

    @After
    fun tearDown() {
        prefs.setManualLocation(null, null)
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun showStep() {
        composeRule.setContent {
            UiTheme { PermissionsStep(prefs = prefs, onContinue = {}) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun setCoordinatesAreShownOnTheCard() {
        prefs.setManualLocation(-33.8688, 151.2093)

        showStep()

        composeRule.onNodeWithText("-33.8688, 151.2093").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Manual location").assertIsDisplayed()
    }

    @Test
    fun enteringCoordinatesSavesThemUntransposed() {
        showStep()

        composeRule.onNodeWithText("Enter coordinates").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Latitude").performTextInput("-33.8688")
        composeRule.onNodeWithText("Longitude").performTextInput("151.2093")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        assertEquals(-33.8688, prefs.manualLocationLat!!, 1e-6)
        assertEquals(151.2093, prefs.manualLocationLon!!, 1e-6)
        composeRule.onNodeWithText("Latitude").assertDoesNotExist()
        composeRule.onNodeWithText("-33.8688, 151.2093").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearingFromTheCardClearsThePref() {
        prefs.setManualLocation(-33.8688, 151.2093)

        showStep()
        composeRule.onNodeWithText("Clear").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertNull(prefs.manualLocationLat)
        assertNull(prefs.manualLocationLon)
    }

    @Test
    fun cancellingTheDialogLeavesTheCoordinatesUnset() {
        showStep()

        composeRule.onNodeWithText("Enter coordinates").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Latitude").assertDoesNotExist()
        assertNull(prefs.manualLocationLat)
    }
}
