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

/** Setup's Skip is a button a gloved finger can hit, and it ends setup. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingSkipButtonTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun skipIsAFullHeightButtonThatEndsSetup() {
        var skipped = false
        composeRule.setContent { UiTheme { TopProgress(currentPage = 0, onSkip = { skipped = true }) } }

        val skip = composeRule.onNodeWithText("Skip setup")
        skip.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        skip.assertHeightIsAtLeast(48.dp)
        skip.performClick()
        assertTrue(skipped)
    }
}
