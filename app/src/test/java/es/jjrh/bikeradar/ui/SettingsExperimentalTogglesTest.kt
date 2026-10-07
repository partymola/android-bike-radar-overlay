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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives each switch on the shipped Experimental screen. The goldens render
 * the content leaf with empty lambdas, so a switch wired to nothing, or to its
 * neighbour's setting, would leave them byte-identical.
 *
 * Before each tap the other two switches are set to the opposite of the value
 * the tap writes, so a tap that also wrote a neighbour would show up.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsExperimentalTogglesTest {

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
            UiTheme { SettingsExperimentalBody(rememberNavController(), prefs) }
        }
    }

    private fun row(title: Int) = composeRule.onNodeWithText(app.getString(title)).performScrollTo()

    private fun tap(title: Int) {
        row(title).performClick()
        composeRule.waitForIdle()
    }

    private fun store(urgentWait: Boolean, precog: Boolean, dropFallback: Boolean) {
        prefs.urgentUnconfidentWaitEnabled = urgentWait
        prefs.precogEnabled = precog
        prefs.radarDropTrackFallbackEnabled = dropFallback
    }

    /** urgent wait, precog, drop fallback, as Prefs holds them. */
    private fun stored(): Triple<Boolean, Boolean, Boolean> = Prefs(app).let {
        Triple(it.urgentUnconfidentWaitEnabled, it.precogEnabled, it.radarDropTrackFallbackEnabled)
    }

    @Test
    fun theUrgentWaitIsOffForANewRider() {
        showScreen()
        row(R.string.settings_exp_urgent_hold_title).assertIsOff()
    }

    @Test
    fun tappingTheUrgentWaitOnReachesItsOwnSettingOnly() {
        store(urgentWait = false, precog = false, dropFallback = false)
        showScreen()
        tap(R.string.settings_exp_urgent_hold_title)
        assertEquals(Triple(true, false, false), stored())
    }

    @Test
    fun theUrgentWaitOpensOnTheStoredChoiceAndTurnsOffAlone() {
        store(urgentWait = true, precog = true, dropFallback = true)
        showScreen()
        row(R.string.settings_exp_urgent_hold_title).assertIsOn()
        tap(R.string.settings_exp_urgent_hold_title)
        assertEquals(Triple(false, true, true), stored())
    }

    @Test
    fun tappingPrecogOnReachesItsOwnSettingOnly() {
        store(urgentWait = false, precog = false, dropFallback = false)
        showScreen()
        tap(R.string.settings_exp_precog_title)
        assertEquals(Triple(false, true, false), stored())
    }

    @Test
    fun precogOpensOnTheStoredChoiceAndTurnsOffAlone() {
        store(urgentWait = true, precog = true, dropFallback = true)
        showScreen()
        row(R.string.settings_exp_precog_title).assertIsOn()
        tap(R.string.settings_exp_precog_title)
        assertEquals(Triple(true, false, true), stored())
    }

    @Test
    fun theDropFallbackIsOnForANewRider() {
        showScreen()
        row(R.string.settings_exp_drop_fallback_title).assertIsOn()
    }

    @Test
    fun tappingTheDropFallbackOffReachesItsOwnSettingOnly() {
        store(urgentWait = true, precog = true, dropFallback = true)
        showScreen()
        tap(R.string.settings_exp_drop_fallback_title)
        assertEquals(Triple(true, true, false), stored())
    }

    @Test
    fun tappingTheDropFallbackOnReachesItsOwnSettingOnly() {
        store(urgentWait = false, precog = false, dropFallback = false)
        showScreen()
        row(R.string.settings_exp_drop_fallback_title).assertIsOff()
        tap(R.string.settings_exp_drop_fallback_title)
        assertEquals(Triple(false, false, true), stored())
    }
}
