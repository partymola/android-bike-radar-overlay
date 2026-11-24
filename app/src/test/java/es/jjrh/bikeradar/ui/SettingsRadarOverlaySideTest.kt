// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The overlay's screen-side row, its picker and the narrow switch on the
 * shipped Alerts screen. The goldens render the content leaf with empty
 * lambdas, so none of the wiring shows there; the picker's radio semantics are
 * read from the unmerged tree, where TalkBack finds them.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarOverlaySideTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    private val inDialog = hasAnyAncestor(isDialog())
    private val isRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val isGroup = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

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

    private fun sideRow() = composeRule.onNode(hasText("Screen side") and hasClickAction()).performScrollTo()

    private fun openPicker() {
        sideRow().performClick()
        composeRule.waitForIdle()
    }

    private fun radioRows(): List<SemanticsNode> = composeRule.onAllNodes(isRadio and inDialog, useUnmergedTree = true).fetchSemanticsNodes()

    private fun label(row: SemanticsNode): String = row.children
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        .joinToString(" ") { it.text }

    private fun dialogRadios(): Map<String, Boolean?> = radioRows().associate { label(it) to it.config.getOrNull(SemanticsProperties.Selected) }

    private fun dialogCount() = composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().size

    @Test
    fun choosingLeftSavesItClosesThePickerAndRetitlesTheRow() {
        showScreen()
        sideRow().assert(hasText("Right"))
        openPicker()
        assertEquals(1, dialogCount())
        composeRule.onNode(isRadio and inDialog and hasText("Left")).performClick()
        composeRule.waitForIdle()

        assertTrue(prefs.overlayOnLeft)
        assertEquals(0, dialogCount())
        sideRow().assert(hasText("Left"))
    }

    @Test
    fun choosingRightFromLeftSavesIt() {
        prefs.overlayOnLeft = true
        showScreen()
        sideRow().assert(hasText("Left"))
        openPicker()
        composeRule.onNode(isRadio and inDialog and hasText("Right")).performClick()
        composeRule.waitForIdle()

        assertFalse(prefs.overlayOnLeft)
        assertEquals(0, dialogCount())
        sideRow().assert(hasText("Right"))
    }

    @Test
    fun theSideRowSaysTappingChangesIt() {
        showScreen()
        assertEquals("change", sideRow().fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
    }

    @Test
    fun cancellingLeavesTheSideAlone() {
        prefs.overlayOnLeft = true
        showScreen()
        openPicker()
        composeRule.onNode(hasText("Cancel") and inDialog).performClick()
        composeRule.waitForIdle()

        assertTrue(prefs.overlayOnLeft)
        assertEquals(0, dialogCount())
    }

    @Test
    fun thePickerAnnouncesBothSidesAsRadiosWithTheStoredOneSelected() {
        // Stored Left, the non-default, so a selection keyed to the default fails.
        prefs.overlayOnLeft = true
        showScreen()
        openPicker()
        assertEquals(mapOf("Left" to true, "Right" to false), dialogRadios())
        val group = composeRule.onNode(isGroup and inDialog, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(List(2) { group.id }, radioRows().map { it.parent?.id })
    }

    @Test
    fun theNarrowSwitchReachesPrefsBothWays() {
        showScreen()
        val switch = composeRule.onNodeWithText("Narrow overlay").performScrollTo()
        switch.assertIsOff()
        switch.performClick()
        composeRule.waitForIdle()
        assertTrue(Prefs(app).overlayNarrow)
        switch.assertIsOn()
        switch.performClick()
        composeRule.waitForIdle()
        assertFalse(Prefs(app).overlayNarrow)
    }

    @Test
    fun theNarrowSwitchOpensOnTheStoredChoice() {
        prefs.overlayNarrow = true
        showScreen()
        composeRule.onNodeWithText("Narrow overlay").performScrollTo().assertIsOn()
    }
}
