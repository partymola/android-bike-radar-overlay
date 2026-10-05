// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Both screens that state the screenshot cap show the number, not a raw
 * placeholder. The strings take it as a format argument, and a call site that
 * drops it still compiles.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotCapShownTest {

    @get:Rule val composeRule = createComposeRule()

    @After
    fun restoreCryptorFactory() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    @Test
    fun thePrivacyScreenStatesTheCap() {
        composeRule.setContent { SettingsPrivacy(navController = rememberNavController()) }
        composeRule.onNodeWithText("Only the newest 30 are kept", substring = true).assertExists()
    }

    @Test
    fun theDebugScreenStatesTheCap() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        val prefs = Prefs(ApplicationProvider.getApplicationContext<Application>())
        composeRule.setContent { DebugScreen(navController = rememberNavController(), prefs = prefs) }
        composeRule.onNodeWithText("Keeps the newest 30", substring = true).assertExists()
    }
}
