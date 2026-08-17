// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
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
 * Pins the coordinates affordance on the SHIPPED Settings -> Permissions
 * screen.
 *
 * The Roborazzi goldens for this screen render `SettingsPermissionsContent`,
 * which has no production caller, so they stay green whatever
 * `SettingsPermissionsBody` - the composable the app actually runs - passes to
 * its cards. A body that omits the alternative renders a location card whose
 * rationale offers coordinate entry as the way out of the London fallback,
 * with nothing on the card to enter or read them. Only this test goes red.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsPermissionsCoordinatesTest {

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
    fun restoreCryptorFactory() {
        prefs.setManualLocation(null, null)
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun showPermissionsScreen() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsPermissions(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun setCoordinatesAreShownOnTheCard() {
        prefs.setManualLocation(51.5074, -0.1278)

        showPermissionsScreen()

        composeRule.onNodeWithText("51.5074, -0.1278").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Manual location").assertIsDisplayed()
    }

    @Test
    fun withNoCoordinatesTheCardOffersToEnterThem() {
        showPermissionsScreen()

        composeRule.onNodeWithText("Enter coordinates").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearingFromTheCardClearsThePref() {
        prefs.setManualLocation(51.5074, -0.1278)

        showPermissionsScreen()
        composeRule.onNodeWithText("Clear").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertNull(prefs.manualLocationLat)
        assertNull(prefs.manualLocationLon)
        composeRule.onNodeWithText("51.5074, -0.1278").assertDoesNotExist()
    }

    /**
     * Latitude and longitude are asserted separately and with distinct values,
     * so a save that transposes the pair fails here rather than round-tripping
     * through a validator that accepts both orders.
     */
    @Test
    fun enteringCoordinatesOpensTheDialogAndSavesThem() {
        showPermissionsScreen()

        composeRule.onNodeWithText("Enter coordinates").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Latitude").performTextInput("-33.8688")
        composeRule.onNodeWithText("Longitude").performTextInput("151.2093")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        assertEquals(-33.8688, prefs.manualLocationLat!!, 1e-6)
        assertEquals(151.2093, prefs.manualLocationLon!!, 1e-6)
    }
}
