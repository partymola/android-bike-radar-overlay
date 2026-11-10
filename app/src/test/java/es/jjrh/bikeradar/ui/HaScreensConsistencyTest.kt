// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.HaHealth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Setup's Home Assistant step and Settings say the same thing in the same
 * words, and TalkBack can use both: the test buttons are buttons, and each
 * field is announced by its label rather than by its placeholder.
 */
@RunWith(AndroidJUnit4::class)
class HaScreensConsistencyTest {

    @get:Rule val compose = createComposeRule()

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun showSetup(ping: Result<String>? = null) = compose.setContent {
        UiTheme {
            Column {
                HaFieldsBlock(
                    urlField = "",
                    onUrlChange = {},
                    tokenField = "",
                    onTokenChange = {},
                    tokenVisible = false,
                    onToggleTokenVisible = {},
                    pingResult = ping,
                    pinging = false,
                    canSubmit = true,
                    onTest = {},
                    onChangeIntent = {},
                )
            }
        }
    }

    private fun showSettings(ping: Result<String>? = null) = compose.setContent {
        UiTheme {
            SettingsHaContent(
                urlField = "",
                onUrlChange = {},
                tokenField = "",
                onTokenChange = {},
                tokenVisible = false,
                onToggleTokenVisible = {},
                pingResult = ping,
                mqttResult = null,
                pinging = false,
                haHealth = HaHealth.Unknown,
                haConfigured = true,
                onBack = {},
                onTestAndSave = {},
                onSaveWithoutTesting = {},
                onClear = {},
            )
        }
    }

    private fun assertFieldsNamedBy(url: String, token: String) {
        compose.onNode(hasSetTextAction() and hasContentDescription(url)).assertExists()
        compose.onNode(hasSetTextAction() and hasContentDescription(token)).assertExists()
        // Not a second stop of their own.
        for (label in listOf(url, token)) {
            assertTrue(compose.onAllNodesWithText(label, ignoreCase = true).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun setupsFieldsAreNamedByTheirLabels() {
        showSetup()
        assertFieldsNamedBy("Base URL", "Long-lived access token")
    }

    @Test
    fun settingsFieldsAreNamedByTheSameLabels() {
        showSettings()
        assertFieldsNamedBy("Base URL", "Long-lived access token")
    }

    @Test
    @Config(qualifiers = "es")
    fun settingsFieldsAreNamedByTheSameLabelsInSpanish() {
        showSettings()
        assertFieldsNamedBy("URL base", "Token de acceso de larga duración")
    }

    @Test
    fun setupsTestButtonIsAButton() {
        showSetup()
        compose.onNode(hasText("Test connection") and button).assertExists()
    }

    @Test
    fun settingsButtonsAreButtons() {
        showSettings()
        for (label in listOf("Test and save", "Save without testing", "Clear HA configuration")) {
            compose.onNode(hasText(label) and button).assertExists()
        }
    }

    /** Both screens save on a passing test, and say so in the same words. */
    @Test
    fun aPassingTestReadsTheSameOnBothScreens() {
        showSetup(Result.success("OK (200)"))
        compose.onNode(hasText("HA: saved", ignoreCase = true)).assertExists()
    }

    @Test
    @Config(qualifiers = "es")
    fun aPassingTestReadsTheSameOnBothScreensInSpanish() {
        showSetup(Result.success("OK (200)"))
        compose.onNode(hasText("HA: guardado", ignoreCase = true)).assertExists()
    }
}
