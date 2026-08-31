// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.HaStatus
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The front camera must be described by the same word wherever the rider
 * reads it. Tapping the home System row that says "No signal" opens the
 * Dashcam settings screen, so the card there has to say "No signal" too - a
 * screen reached from a row must not say less than the row that sent them.
 *
 * Expected words are spelled out rather than read back from resources: a test
 * asserting a string against itself stays green when the copy is gutted.
 *
 * The state each test drives is deliberately the wired one - real
 * [SettingsDashcam] against real [Prefs] and the battery bus - because the
 * goldens render only the stateless leaf, and a card whose word came from a
 * broken lookup renders exactly like one whose word is right.
 */
@RunWith(RobolectricTestRunner::class)
class DashcamStatusWordTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app)
        prefs.dashcamOwnership = DashcamOwnership.YES
        // Radio on, because every case below is about the LINK rather than the
        // radio, and a camera reads "Not paired" with the adapter down however
        // its advert is doing. Robolectric leaves the adapter off by default,
        // so leaving this out tests the Bluetooth-off state under four names
        // that all claim to be about something else.
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
    }

    @After
    fun tearDown() {
        BatteryStateBus.clearForTest()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun showSettings() {
        composeRule.setContent {
            SettingsDashcam(navController = rememberNavController(), prefs = prefs)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aPairedCameraWithNoRecentAdvertSaysNoSignal() {
        // The state must be named, not left to the dot: a card carrying only
        // an amber dot announces itself by colour alone, and a rider who
        // arrived from a row reading "No signal" watches it vanish.
        prefs.dashcamMac = "00:11:22:33:44:55"
        prefs.dashcamDisplayName = "Front cam"

        showSettings()

        composeRule.onNodeWithText("No signal").assertIsDisplayed()
    }

    @Test
    fun aPairedCameraAdvertisingNowSaysLive() {
        prefs.dashcamMac = "00:11:22:33:44:55"
        prefs.dashcamDisplayName = "Front cam"
        // Slug, not the display name: BikeRadarService.slug punches
        // non-alphanumerics to underscores, and a fixture spelling it the
        // other way makes the card read "No signal" for a live camera.
        BatteryStateBus.update(BatteryEntry(slug = "front_cam", name = "Front cam", pct = 64))

        showSettings()

        composeRule.onNodeWithText("Live").assertIsDisplayed()
        // The battery lookup shares the slug resolution the word depends on,
        // so a chip missing here narrows where a wrong word came from.
        composeRule.onNodeWithText("64%").assertIsDisplayed()
    }

    @Test
    fun noCameraChosenSaysNotPaired() {
        prefs.dashcamMac = null
        prefs.dashcamDisplayName = null

        showSettings()

        composeRule.onNodeWithText("Not paired").assertIsDisplayed()
    }

    @Test
    fun withTheRadioOffTheCardAgreesWithTheRowThatOpenedIt() {
        // The camera's pairing lives in prefs, which survive the radio being
        // off, so this screen would happily call it paired while the home row
        // that sent the rider here calls it not paired. That is the exact
        // "a screen must not say less than the row that sent them" failure
        // this file exists to pin, arriving through a different input.
        prefs.dashcamMac = "00:11:22:33:44:55"
        prefs.dashcamDisplayName = "Front cam"
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)

        showSettings()

        composeRule.onNodeWithText("Not paired").assertIsDisplayed()
    }

    @Test
    fun theHomeRowUsesTheSameWords() {
        // The other end of the pairing. Both surfaces read one mapping, so a
        // reworded state fails here and on the settings card together; two
        // literals that agree are what makes that a statement about the
        // rider's journey rather than about one screen.
        composeRule.setContent {
            UiTheme {
                SystemCard(
                    // The radar is live so that IT does not also say "No
                    // signal": with both rows saying it, the assertion below
                    // would pass on the radar's word and prove nothing about
                    // the camera's.
                    radarFresh = true,
                    hasBond = true,
                    btEnabled = true,
                    dashcamOwned = true,
                    dashcamFresh = false,
                    dashcamPaired = true,
                    radarBattery = null,
                    dashcamBattery = null,
                    haStatus = HaStatus.READY,
                    onRowClick = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No signal").assertIsDisplayed()
    }
}
