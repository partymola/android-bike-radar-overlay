// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden of the dashcam daytime picker opened in landscape at its default,
 * the fifth of six modes, so the checked row is drawn in view. A dialog is its
 * own window, so the whole screen is captured.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w915dp-h369dp-land-xxhdpi")
class SettingsLightsPickerLandscapeSnapshotTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app)
    }

    @After
    fun tearDown() {
        prefs.autoLightModeEnabled = false
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun dashcamDaytimeAtItsDefault() {
        prefs.dashcamOwnership = DashcamOwnership.YES
        prefs.autoLightModeEnabled = true
        composeRule.setContent { SettingsLights(navController = rememberNavController(), prefs = prefs) }
        composeRule.onNode(hasText("Daytime mode") and hasClickAction()).performScrollTo().performClick()
        composeRule.waitForIdle()
        captureScreenRoboImage()
    }
}
