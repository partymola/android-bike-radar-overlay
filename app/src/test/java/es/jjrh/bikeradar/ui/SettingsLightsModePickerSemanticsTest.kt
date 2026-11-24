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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.CameraLightMode
import es.jjrh.bikeradar.RadarLightMode
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
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
 * What a screen reader is told about the light-mode pickers on the shipped
 * Settings -> Light auto-mode screen. Each option row must itself be a radio
 * button carrying whether it is the current mode, and every row must sit
 * directly in one selectable group. Read from the unmerged tree, which is
 * where TalkBack finds a row's role and state. No golden can see any of it.
 *
 * Each picker is opened with a stored mode that is not the pref's default,
 * and the two pickers of each kind with modes at different positions, so a
 * selection keyed to a fixed mode or position cannot pass.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLightsModePickerSemanticsTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    private val inDialog = hasAnyAncestor(isDialog())
    private val isRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val isGroup = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app)
    }

    @After
    fun tearDown() {
        prefs.radarLightAutoModeEnabled = false
        prefs.autoLightModeEnabled = false
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun showScreen() {
        composeRule.setContent {
            SettingsLights(navController = rememberNavController(), prefs = prefs)
        }
    }

    private fun modeRow(row: String) = composeRule.onNode(hasText(row) and hasClickAction()).performScrollTo()

    private fun openPicker(row: String) {
        showScreen()
        modeRow(row).performClick()
        composeRule.waitForIdle()
    }

    /** Each mode row's title to the action TalkBack reads for a double-tap. */
    private fun modeRowClickLabels(): Map<String, String?> = listOf("Daytime mode", "Night mode").associateWith {
        modeRow(it).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
    }

    private fun radioRows(): List<SemanticsNode> = composeRule.onAllNodes(isRadio and inDialog, useUnmergedTree = true).fetchSemanticsNodes()

    private fun label(row: SemanticsNode): String = row.children
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        .joinToString(" ") { it.text }

    /** Each radio row's label to the selected state on the row itself. */
    private fun dialogRadios(): Map<String, Boolean?> = radioRows().associate { label(it) to it.config.getOrNull(SemanticsProperties.Selected) }

    private fun assertOneGroupHoldsEveryRow(entries: Int) {
        val group = composeRule.onNode(isGroup and inDialog, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(List(entries) { group.id }, radioRows().map { it.parent?.id })
    }

    private fun assertPickerClosed() {
        assertEquals(0, composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
    }

    private fun storeRadarModes() {
        prefs.radarLightAutoModeEnabled = true
        prefs.radarLightDayMode = RadarLightMode.SOLID
        prefs.radarLightNightMode = RadarLightMode.OFF
    }

    private fun storeDashcamModes() {
        prefs.dashcamOwnership = DashcamOwnership.YES
        prefs.autoLightModeEnabled = true
        prefs.cameraLightDayMode = CameraLightMode.HIGH
        prefs.cameraLightNightMode = CameraLightMode.NIGHT_FLASH
    }

    private fun showRadarPicker(row: String) {
        storeRadarModes()
        openPicker(row)
    }

    private fun showDashcamPicker(row: String) {
        storeDashcamModes()
        openPicker(row)
    }

    @Test
    fun theRadarModeRowsSayTappingChangesTheMode() {
        storeRadarModes()
        showScreen()
        assertEquals(mapOf("Daytime mode" to "change", "Night mode" to "change"), modeRowClickLabels())
    }

    @Test
    fun theDashcamModeRowsSayTappingChangesTheMode() {
        storeDashcamModes()
        showScreen()
        assertEquals(mapOf("Daytime mode" to "change", "Night mode" to "change"), modeRowClickLabels())
    }

    @Test
    fun theRadarDaytimePickerAnnouncesEachModeAsARadioWithItsState() {
        showRadarPicker("Daytime mode")
        assertEquals(
            mapOf("Night flash" to false, "Day flash" to false, "Solid" to true, "Peloton" to false, "Off" to false),
            dialogRadios(),
        )
        assertOneGroupHoldsEveryRow(RadarLightMode.entries.size)
    }

    @Test
    fun theRadarNightPickerAnnouncesEachModeAsARadioWithItsState() {
        showRadarPicker("Night mode")
        assertEquals(
            mapOf("Night flash" to false, "Day flash" to false, "Solid" to false, "Peloton" to false, "Off" to true),
            dialogRadios(),
        )
        assertOneGroupHoldsEveryRow(RadarLightMode.entries.size)
    }

    @Test
    fun theDashcamNightPickerAnnouncesEachModeAsARadioWithItsState() {
        showDashcamPicker("Night mode")
        assertEquals(
            mapOf(
                "High" to false,
                "Medium" to false,
                "Low" to false,
                "Night flash" to true,
                "Day flash" to false,
                "Off" to false,
            ),
            dialogRadios(),
        )
        assertOneGroupHoldsEveryRow(CameraLightMode.entries.size)
    }

    @Test
    fun theDashcamDaytimePickerAnnouncesEachModeAsARadioWithItsState() {
        showDashcamPicker("Daytime mode")
        assertEquals(
            mapOf(
                "High" to true,
                "Medium" to false,
                "Low" to false,
                "Night flash" to false,
                "Day flash" to false,
                "Off" to false,
            ),
            dialogRadios(),
        )
        assertOneGroupHoldsEveryRow(CameraLightMode.entries.size)
    }

    @Test
    fun choosingARadarModeSavesItAndClosesThePicker() {
        showRadarPicker("Daytime mode")
        composeRule.onNode(isRadio and inDialog and hasText("Peloton")).performClick()
        composeRule.waitForIdle()

        assertEquals(RadarLightMode.PELOTON, prefs.radarLightDayMode)
        assertPickerClosed()
    }

    @Test
    fun choosingARadarNightModeSavesOnlyTheNightMode() {
        showRadarPicker("Night mode")
        composeRule.onNode(isRadio and inDialog and hasText("Peloton")).performClick()
        composeRule.waitForIdle()

        assertEquals(RadarLightMode.PELOTON, prefs.radarLightNightMode)
        assertEquals(RadarLightMode.SOLID, prefs.radarLightDayMode)
        assertPickerClosed()
    }

    @Test
    fun choosingADashcamDaytimeModeSavesOnlyTheDaytimeMode() {
        showDashcamPicker("Daytime mode")
        composeRule.onNode(isRadio and inDialog and hasText("Medium")).performClick()
        composeRule.waitForIdle()

        assertEquals(CameraLightMode.MEDIUM, prefs.cameraLightDayMode)
        assertEquals(CameraLightMode.NIGHT_FLASH, prefs.cameraLightNightMode)
        assertPickerClosed()
    }

    @Test
    fun choosingADashcamModeSavesItAndClosesThePicker() {
        showDashcamPicker("Night mode")
        composeRule.onNode(isRadio and inDialog and hasText("Off")).performClick()
        composeRule.waitForIdle()

        assertEquals(CameraLightMode.OFF, prefs.cameraLightNightMode)
        assertPickerClosed()
    }
}
