// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a screen reader is told about a settings toggle.
 *
 * The goldens cannot see any of this: a row whose switch is a nameless pill
 * beside a label renders byte-identically to one that announces its title
 * with its state. So the row's semantics are pinned here, on the shared atom,
 * which every toggle row in Settings is built from.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsToggleRowSemanticsTest {

    @get:Rule val composeRule = createComposeRule()

    private fun show(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit = {}) {
        composeRule.setContent {
            UiTheme {
                SettingsToggleRow(
                    title = "Turn cues",
                    subtitle = "Hold the all-clear through a corner",
                    checked = checked,
                    onCheckedChange = onChange,
                    enabled = enabled,
                )
            }
        }
    }

    @Test
    fun theRowIsTheOneSwitchAndCarriesTheTitleWithTheState() {
        show(checked = true)
        // Exactly one, not at least one: a toggleable pill inside a toggleable
        // row is two switches to a screen reader, one of them nameless.
        val nodes = composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes()
        assertEquals(1, nodes.size)
        val node = nodes.single()
        assertEquals(Role.Switch, node.config[SemanticsProperties.Role])
        assertEquals(ToggleableState.On, node.config[SemanticsProperties.ToggleableState])
        // The title and subtitle are merged into the switch node, which is what
        // makes the announcement "Turn cues, on, switch" rather than "on, switch".
        val text = node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertEquals("Turn cues Hold the all-clear through a corner", text)
    }

    @Test
    fun anOffRowAnnouncesOff() {
        show(checked = false)
        val node = composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().single()
        assertEquals(ToggleableState.Off, node.config[SemanticsProperties.ToggleableState])
    }

    @Test
    fun tappingTheRowFlipsTheValue() {
        var received: Boolean? = null
        show(checked = true) { received = it }
        composeRule.onAllNodes(isToggleable())[0].performClick()
        assertEquals(false, received)
    }

    @Test
    fun tappingAnOffRowTurnsItOn() {
        var received: Boolean? = null
        show(checked = false) { received = it }
        composeRule.onAllNodes(isToggleable())[0].performClick()
        assertEquals(true, received)
    }

    @Test
    fun theRowIsTheOnlyPlaceThePillIsUsed() {
        // A bare BrToggle anywhere else is a switch a screen reader cannot
        // name and a tap cannot reach, and no golden would show it.
        val ui = checkNotNull(RepoFiles.mainSource("ui/UiPrimitives.kt").parentFile)
        val callers = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { f -> Regex("""(?<!fun )BrToggle\(""").findAll(f.readText()).count() }
        assertEquals(1, callers)
    }

    @Test
    fun aDisabledRowIsAnnouncedDisabledAndIgnoresTheTap() {
        var received: Boolean? = null
        show(checked = true, enabled = false) { received = it }
        val row = composeRule.onAllNodes(isToggleable())[0]
        row.assertIsNotEnabled()
        row.performClick()
        assertEquals(null, received)
    }
}
