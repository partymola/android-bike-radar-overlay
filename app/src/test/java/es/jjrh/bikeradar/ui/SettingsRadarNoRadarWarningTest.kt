// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the riding-without-the-radar switch on the shipped Alerts screen.
 * The goldens render the content leaf with empty lambdas, so a switch that
 * never reached Prefs would leave them byte-identical.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarNoRadarWarningTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        app.getSharedPreferences("bike_radar_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        prefs = Prefs(app)
    }

    private fun showScreen() {
        composeRule.setContent {
            UiTheme { SettingsRadarBody(rememberNavController(), prefs) }
        }
    }

    private fun row() = composeRule.onNodeWithText(app.getString(R.string.settings_radar_no_radar_warning_title)).performScrollTo()

    @Test
    fun itIsOnForANewRider() {
        assertTrue(prefs.noRadarRideWarningEnabled)
        showScreen()
        row().assertIsOn()
    }

    @Test
    fun tappingItOffReachesPrefs() {
        showScreen()
        row().performClick()
        composeRule.waitForIdle()
        assertFalse(Prefs(app).noRadarRideWarningEnabled)
    }

    @Test
    fun itSays90SecondsAtTheDefaultReconnectInterval() {
        showScreen()
        composeRule.onNodeWithText("90 seconds into a ride", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun itSaysTheLongerGraceAfterTheIntervalIsRaised() {
        // A 120 s interval sleeps up to 144 s, plus 30 s for the connect.
        prefs.radarLongOfflineCapSec = 120
        showScreen()
        composeRule.onNodeWithText("174 seconds into a ride", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun itIsHiddenForARiderWithNoEBike() {
        // The warning needs a Bosch eBike; the row beside it stays.
        prefs.eBikeOwnership = EBikeOwnership.NO
        showScreen()
        composeRule.onNodeWithText(app.getString(R.string.settings_radar_no_radar_warning_title)).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.settings_radar_banner_persistent_title)).performScrollTo().assertExists()
    }

    @Test
    fun itShowsForARiderWithAnEBike() {
        prefs.eBikeOwnership = EBikeOwnership.YES
        showScreen()
        row().assertIsOn()
    }

    @Test
    fun itShowsForARiderWhoNeverAnswered() {
        // An upgrader can have the eBike reader on without ever answering.
        prefs.eBikeOwnership = EBikeOwnership.UNANSWERED
        showScreen()
        row().assertIsOn()
    }

    @Test
    fun itRoundsAPartSecondUp() {
        // 51 s sleeps up to 61.2 s, so the grace is 91.2 s: shown as 92, the
        // same figure the connection log records.
        prefs.radarLongOfflineCapSec = 51
        showScreen()
        composeRule.onNodeWithText("92 seconds into a ride", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun itOpensOnTheStoredChoice() {
        prefs.noRadarRideWarningEnabled = false
        showScreen()
        row().assertIsOff()
        row().performClick()
        composeRule.waitForIdle()
        assertTrue(Prefs(app).noRadarRideWarningEnabled)
    }
}
