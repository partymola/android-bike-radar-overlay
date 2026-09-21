// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The notice, the real button and the real activity, in one test.
 *
 * Everything either side of this is pinned separately: the route order in
 * `SafetyNoticeGateTest`, the wording in `SafetyNoticeTest`, the stored flag
 * in `SafetyNoticePrefsTest`. What none of them can see is the tap actually
 * reaching the flag through the activity's own navigation, which is the one
 * step that decides whether a rider meets this screen again tomorrow.
 */
@RunWith(AndroidJUnit4::class)
class SafetyNoticeAcknowledgeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /**
     * An `init` block, NOT `@Before`, and both halves of this matter.
     *
     * `createAndroidComposeRule` LAUNCHES the activity, and its
     * `ActivityScenarioRule` wraps the statement that carries `@Before`, so a
     * `@Before` here runs after `MainActivity.onCreate` has already happened.
     * JUnit constructs the test instance before it applies any rule, so an
     * `init` block is the seam that gets in front of the launch.
     *
     * What that ordering costs if it is wrong: `onCreate` calls
     * `HaCredentials.seedFromBuildConfigIfEmpty()`, and a debug build carries
     * whatever `local.properties` holds, so a late cryptor install puts a real
     * Home Assistant token through the production keystore inside a unit test.
     * And `firstRunComplete` would be written after the start destination had
     * already been chosen, leaving this test on the fresh-install path while
     * its name claims the upgrading one.
     *
     * `firstRunComplete = true` is what makes this the UPGRADING rider: past
     * onboarding, so any ordering that treats the notice as an onboarding step
     * sends them to the home screen and this test finds no button to press.
     */
    init {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        Prefs(app).firstRunComplete = true
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
    fun tappingTheButtonAcknowledgesAndMovesOn() {
        assertFalse("the launch itself must not acknowledge", Prefs(app).safetyNoticeAcknowledged)

        // The notice really is what an already-onboarded rider is looking at.
        compose.onNodeWithText("Before you ride").assertIsDisplayed()
        compose.onNodeWithText("Always look behind before you move out.").assertIsDisplayed()

        compose.onNodeWithText("I understand").performClick()
        compose.waitForIdle()

        assertTrue("the tap did not reach the stored flag", Prefs(app).safetyNoticeAcknowledged)
        compose.onNodeWithText("I understand").assertDoesNotExist()
    }
}
