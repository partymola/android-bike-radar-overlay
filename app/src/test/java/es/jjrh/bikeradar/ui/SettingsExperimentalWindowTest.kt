// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the traffic-window slider on the SHIPPED Experimental screen.
 *
 * The snapshot tests compose `SettingsExperimentalContent` with literal values
 * and empty lambdas, so everything between the rider's finger and
 * [Prefs.radarDropTrackWindowSec] was unexercised: a hardcoded initial value, an
 * empty change or release lambda, or a position written as seconds all left the
 * screen looking correct, every golden byte-identical and every gate green,
 * while the rider's chosen window either never persisted or silently reverted to
 * the default. Only this test composes `SettingsExperimentalBody`.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsExperimentalWindowTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        app.getSharedPreferences("bike_radar_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        prefs = Prefs(app)
        prefs.radarDropTrackFallbackEnabled = true
    }

    private fun showScreen() {
        composeRule.setContent {
            UiTheme { SettingsExperimentalBody(rememberNavController(), prefs) }
        }
    }

    @Test
    fun theSliderOpensOnTheStoredWindowRatherThanTheDefault() {
        // A literal initialiser passes every other check. It also costs the
        // rider the setting twice over: the screen opens at 30 s whatever they
        // chose, and because the release persists the screen's own state, the
        // next touch of the slider writes that wrong base back over their value.
        prefs.radarDropTrackWindowSec = 600
        showScreen()
        composeRule.onNodeWithText("10 min").assertIsDisplayed()
    }

    @Test
    fun draggingTheSliderPersistsTheChosenWindow() {
        // The whole point of the feature, end to end. Seeded away from both the
        // default and the target so neither a no-op nor a reset can pass: an
        // empty release lambda leaves 600, and a position written as raw
        // seconds is clamped back to 30. Neither is 60.
        prefs.radarDropTrackWindowSec = 600
        showScreen()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        composeRule.waitForIdle()
        assertEquals(60, Prefs(app).radarDropTrackWindowSec)
    }

    @Test
    fun anOffLadderWindowIsLabelledWithTheRungTheThumbIsOn() {
        // Every other test here seeds a value that IS a rung, where labelling
        // the stored seconds and labelling the rung give the same answer - so
        // none of them can see the difference. 700 is the case the ladder's
        // nearest-match exists for (a backup restored from a build with other
        // rungs): the thumb sits on 10 min, and labelling the raw value would
        // read "11 min" for a window the app is not using.
        prefs.radarDropTrackWindowSec = 700
        showScreen()
        composeRule.onNodeWithText("10 min").assertIsDisplayed()
    }

    @Test
    fun theSliderIsHiddenWhileTheAlertItConfiguresIsOff() {
        // The setting reaches that one path, so with the alert off this would
        // be a live control writing a value nothing reads. The two other
        // screens that nest a slider under a switch hide theirs the same way.
        prefs.radarDropTrackFallbackEnabled = false
        prefs.radarDropTrackWindowSec = 600
        showScreen()
        // The control's absence, not a label's: a relabel must not quietly
        // turn this into a test of nothing.
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertDoesNotExist()
    }

    @Test
    fun theHelperNamesTheControlItSendsRidersTo() {
        // The helper quotes the home screen's button by name, in both locales,
        // as the way out of an alert a longer window brings on. A relabel of
        // that button would otherwise leave two languages pointing at a control
        // that no longer exists, silently.
        val es = app.createConfigurationContext(
            android.content.res.Configuration(app.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag("es"))
            },
        )
        // Pin that the Spanish context really is Spanish: if it fell back to
        // English, both strings below would be English and the assertion would
        // pass while checking nothing.
        assertEquals("He aparcado", es.getString(R.string.main_cta_parked))
        for (ctx in listOf(app, es)) {
            val helper = ctx.getString(R.string.settings_exp_drop_window_helper)
            assertTrue(
                "the helper does not name the control it sends riders to: $helper",
                helper.contains(ctx.getString(R.string.main_cta_parked)),
            )
        }
    }

    @Test
    fun theSliderAnnouncesTheWindowItIsOnAndNotItsPositionInTheRange() {
        // Both halves on the same node, which is what makes it a statement
        // about the ladder rather than about the plumbing: the rung sits two
        // thirds along a 0..6 range and announces ten minutes, where the
        // platform's own position-derived announcement would say about forty.
        prefs.radarDropTrackWindowSec = 600
        showScreen()
        val node = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .fetchSemanticsNode()
        assertEquals("10 min", node.config[SemanticsProperties.StateDescription])
        val range = node.config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(4f, range.current, 0.001f)
        assertEquals(0f..6f, range.range)
        // The name itself, not merely that the list has an entry: an empty
        // string is a non-empty list and leaves explore-by-touch on a bare
        // value, which is the state this replaced.
        assertEquals(
            listOf(app.getString(R.string.settings_exp_drop_window_title)),
            node.config[SemanticsProperties.ContentDescription],
        )
    }
}
