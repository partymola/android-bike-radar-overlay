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
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the "beep for other vehicles too" switch on the shipped Alerts screen.
 * The goldens render the content leaf with empty lambdas, so a switch that
 * never reached Prefs would leave them byte-identical.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarNextCarCueTest {

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

    private fun row() = composeRule.onNodeWithText(app.getString(R.string.settings_radar_next_car_title)).performScrollTo()

    @Test
    fun itIsOnForANewRider() {
        assertTrue(prefs.nextCarCueEnabled)
        showScreen()
        row().assertIsOn()
    }

    @Test
    fun tappingItOffReachesPrefs() {
        showScreen()
        row().performClick()
        composeRule.waitForIdle()
        assertFalse(Prefs(app).nextCarCueEnabled)
    }

    @Test
    fun itOpensOnTheStoredChoice() {
        prefs.nextCarCueEnabled = false
        showScreen()
        row().assertIsOff()
        row().performClick()
        composeRule.waitForIdle()
        assertTrue(Prefs(app).nextCarCueEnabled)
    }
}
