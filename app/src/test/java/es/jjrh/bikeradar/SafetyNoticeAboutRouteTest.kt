// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings -> About -> the notice, by tapping, in the real activity.
 *
 * Two things only this can see, and both are wiring rather than logic.
 * `settings/safety` could be re-pointed at any other screen and every other
 * gate would stay green, because the route is otherwise only ever checked as a
 * registered string. And its button is the one `onAcknowledge` in the app that
 * pops instead of storing: drop the `popBackStack` and a rider who opens the
 * notice from About is stuck on it with a dead button, which nothing else
 * exercises. The rest of the screen is pinned in `SafetyNoticeTest`, and the
 * first-launch button in `SafetyNoticeAcknowledgeTest`.
 *
 * `init` rather than `@Before`, for the reasons set out in
 * `SafetyNoticeAcknowledgeTest`: the compose rule launches the activity before
 * `@Before` runs, so prefs written there arrive after the start destination has
 * been chosen and after the credential seed has run.
 */
@RunWith(AndroidJUnit4::class)
class SafetyNoticeAboutRouteTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    init {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        Prefs(app).apply {
            firstRunComplete = true
            safetyNoticeAcknowledged = true
        }
    }

    @After
    fun restore() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAboutRowOpensTheWholeNoticeAndTheButtonReturns() {
        // Scroll each row into view before tapping it. Both screens are taller
        // than the viewport, and a tap injected at an off-screen row's centre
        // is absorbed rather than reported, so without this the failure lands
        // further down and names the wrong step.
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("About").performScrollTo().performClick()
        compose.waitForIdle()
        // The subtitle, not the title: the title is the notice's own heading
        // too, so it stops identifying the row the moment the route works.
        compose.onNodeWithText("What the radar can and cannot do").performScrollTo().performClick()
        compose.waitForIdle()

        // Body lines, which appear on no other screen. A route re-pointed at
        // anything else still renders a heading; it does not render these.
        compose.onNodeWithText("It cannot look behind for you.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("only means the radar sees nothing", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Always look behind before you move out.").assertIsDisplayed()

        compose.onNodeWithText("I understand").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Always look behind before you move out.").assertDoesNotExist()
        // Displayed rather than merely present, which asserts one thing beyond
        // "something popped": the restored back-stack entry keeps the scroll
        // position that put this row on screen. If this line ever flakes, that
        // is the dependency to suspect rather than the pop itself.
        compose.onNodeWithText("What the radar can and cannot do").assertIsDisplayed()
    }
}
