// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The status card reads as a heading and its button as a button. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroStatusCardA11yTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun theCardIsAHeadingAndItsButtonAFullHeightButton() {
        var tapped = false
        composeRule.setContent {
            UiTheme {
                HeroStatusCard(
                    status = MainStatus(
                        icon = MainStatusIcon.Warning,
                        tone = MainStatusTone.Error,
                        headline = "Permissions needed",
                        subtitle = "No alerts until you grant Nearby devices",
                    ),
                    cta = StatusCta(label = "Grant permissions", onClick = { tapped = true }),
                )
            }
        }

        composeRule.onNodeWithText("Permissions needed", useUnmergedTree = false)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val button = composeRule.onNodeWithText("Grant permissions")
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        button.assertHeightIsAtLeast(48.dp)
        button.performClick()
        assertTrue(tapped)
    }

    @Test
    fun bothDashcamPromptAnswersAreFullHeightButtons() {
        composeRule.setContent { UiTheme { DashcamPromptCard(onYes = {}, onNo = {}) } }
        for (label in listOf("Set it up", "I don't have one")) {
            val node = composeRule.onNodeWithText(label)
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            node.assertHeightIsAtLeast(48.dp)
        }
    }
}
