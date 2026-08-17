// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the coordinates affordance on the SHIPPED Settings -> Light auto-mode
 * screen, the companion to `SettingsPermissionsCoordinatesTest`.
 *
 * `SettingsLightsSnapshotTest` injects its own [PermissionAlternative] into
 * the `locationCard` slot, so it renders the card without ever exercising the
 * screen's own wiring. Only this test composes `SettingsLights` itself.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLightsCoordinatesTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app)
        prefs.setManualLocation(null, null)
        // The location card only composes while an auto-mode is on.
        prefs.radarLightAutoModeEnabled = true
    }

    @After
    fun restoreCryptorFactory() {
        prefs.setManualLocation(null, null)
        prefs.radarLightAutoModeEnabled = false
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun showLightsScreen() {
        composeRule.setContent {
            val nav = rememberNavController()
            SettingsLights(navController = nav, prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun setCoordinatesAreShownOnTheCard() {
        prefs.setManualLocation(-33.8688, 151.2093)

        showLightsScreen()

        composeRule.onNodeWithText("-33.8688, 151.2093").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Manual location").assertIsDisplayed()
    }

    @Test
    fun withNoCoordinatesTheCardOffersToEnterThem() {
        showLightsScreen()

        composeRule.onNodeWithText("Enter coordinates").performScrollTo().assertIsDisplayed()
    }
}
