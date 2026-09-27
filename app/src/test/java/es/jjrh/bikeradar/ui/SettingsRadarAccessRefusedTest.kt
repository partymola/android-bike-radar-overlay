// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.access.PrefsRadarGrantStore
import es.jjrh.bikeradar.access.RadarGrant
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A grant refused the last time its app asked, because the app could not
 * prove the key the rider approved, must not read as an app using the radar:
 * not on its row, not in the removal dialog, not in the Settings count.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarAccessRefusedTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
    }

    @After
    fun restoreCryptorFactory() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun grant(refused: Boolean) = RadarGrant("com.example.trailbuddy", "aa11", "Trail Buddy", 0L, 0L, read = true, control = true, refused = refused)

    private fun showList(grant: RadarGrant) {
        composeRule.setContent {
            UiTheme { SettingsRadarAccessContent(grants = listOf(grant), onRevoke = {}, onBack = {}) }
        }
        composeRule.waitForIdle()
    }

    private fun count(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun aRefusedGrantSaysSoAndClaimsNothing() {
        showList(grant(refused = true))

        composeRule.onNodeWithText("Blocked last time: couldn't confirm it was the app you allowed", substring = true).assertExists()
        assertEquals(0, count("Sees the radar"))
        assertEquals(0, count("Changes your tail light"))
        assertEquals(0, count("Not used yet"))
    }

    @Test
    fun aRefusedGrantKeepsItsLastRealUse() {
        showList(grant(refused = true).copy(lastUsedAtMs = System.currentTimeMillis() - 3 * 86_400_000L))

        composeRule.onNodeWithText("Last used", substring = true).assertExists()
    }

    @Test
    fun aGrantNotRefusedStillSaysWhatTheAppCanDo() {
        showList(grant(refused = false))

        composeRule.onNodeWithText("Sees the radar", substring = true).assertExists()
        composeRule.onNodeWithText("Changes your tail light and overlay", substring = true).assertExists()
        composeRule.onNodeWithText("Not used yet", substring = true).assertExists()
        assertEquals(0, count("Blocked"))
    }

    private fun openRemoval() {
        composeRule.onAllNodesWithContentDescription("Stop sharing")[0].performClick()
        composeRule.waitForIdle()
    }

    /** "It will no longer see your radar" is not something to say of an app that was refused. */
    @Test
    fun removingARefusedGrantSaysWhatRemovingItDoes() {
        showList(grant(refused = true))
        openRemoval()

        composeRule.onNode(hasText("Bike Radar blocked it the last time", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        composeRule.onNode(hasText("open it and allow it if it asks", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        composeRule.onNode(hasText("\"Stop sharing\" removes your saved choice", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        assertEquals(0, count("It will no longer see your radar"))
    }

    @Test
    fun removingAGrantNotRefusedKeepsTheOrdinaryWarning() {
        showList(grant(refused = false))
        openRemoval()

        composeRule.onNode(hasText("It will no longer see your radar", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        assertEquals(0, count("blocked it the last time"))
    }

    private fun showSettingsOver(vararg grants: RadarGrant) {
        val store = PrefsRadarGrantStore(app.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, Context.MODE_PRIVATE))
        grants.forEach { store.put(it) }
        composeRule.setContent {
            SettingsScreen(navController = rememberNavController(), prefs = Prefs(app))
        }
        composeRule.waitForIdle()
    }

    /** Control-only, so a filter that drops only refused readers still counts it. */
    @Test
    fun theSettingsRowDoesNotCountARefusedGrant() {
        showSettingsOver(grant(refused = true).copy(read = false))
        assertEquals(1, count("No other app is using it"))
    }

    @Test
    fun theSettingsRowCountsAGrantNotRefused() {
        showSettingsOver(grant(refused = false))
        assertEquals(0, count("No other app is using it"))
        assertEquals(1, count("1 app can see what the radar sees"))
    }

    /** A refused grant allowing both beside a working reader: only the reader counts, and it cannot control. */
    @Test
    fun theSettingsRowCountsOnlyTheGrantsNotRefused() {
        showSettingsOver(
            grant(refused = false).copy(control = false),
            RadarGrant("com.example.other", "cc33", "Another Navigator", 0L, 0L, read = true, control = true, refused = true),
        )
        assertEquals(1, count("1 app can see what the radar sees"))
        assertEquals(0, count("change your tail light"))
    }
}
