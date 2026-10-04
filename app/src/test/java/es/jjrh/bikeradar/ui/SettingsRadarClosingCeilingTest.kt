// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the closing-speed ceiling slider on the shipped Alerts screen. The
 * goldens compose `SettingsRadarContent` with literal values and empty
 * lambdas, so an empty release lambda or a wrong stop mapping would leave
 * every golden byte-identical while the rider's choice never persisted.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarClosingCeilingTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        app.getSharedPreferences("bike_radar_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        prefs = Prefs(app)
    }

    private fun slider() = composeRule.onNode(
        hasContentDescription(app.getString(R.string.settings_radar_closing_ceiling_title)) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
    )

    private fun showScreen() {
        composeRule.setContent {
            UiTheme { SettingsRadarBody(rememberNavController(), prefs) }
        }
    }

    @Test
    fun theLastStopPersistsNoLimit() {
        prefs.closingSpeedCeilingMs = 40
        showScreen()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        composeRule.waitForIdle()
        assertNull(Prefs(app).closingSpeedCeilingMs)
    }

    @Test
    fun aMiddleStopPersistsItsSpeed() {
        // Seeded at no limit, away from both the default and the target, so
        // neither an empty release nor a reset to the default can pass.
        prefs.closingSpeedCeilingMs = null
        showScreen()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        composeRule.waitForIdle()
        assertEquals(45, Prefs(app).closingSpeedCeilingMs)
    }

    @Test
    fun theSliderOpensOnTheStoredCeiling() {
        prefs.closingSpeedCeilingMs = 50
        showScreen()
        val node = slider().fetchSemanticsNode()
        assertEquals("180 km/h", node.config[SemanticsProperties.StateDescription])
        assertEquals(3f, node.config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
    }

    @Test
    fun anOffLadderCeilingIsLabelledWithTheStopTheThumbIsOn() {
        // A value between stops is shown as the stop it snaps to (PrefsTest
        // covers a raw stored value). 38 snaps to 40, which is neither the
        // default nor 38's own 137 km/h.
        prefs.closingSpeedCeilingMs = 38
        showScreen()
        assertEquals("144 km/h", slider().fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
}
