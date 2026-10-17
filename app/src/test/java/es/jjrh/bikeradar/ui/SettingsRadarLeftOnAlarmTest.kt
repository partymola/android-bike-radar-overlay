// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.app.NotificationManager
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.ServiceNotifications
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Drives the radar-left-on switch and its Do Not Disturb row on the shipped
 * Alerts screen. The goldens render the content leaf with empty lambdas, so
 * neither wire would show there.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarLeftOnAlarmTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

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

    private fun row() = composeRule.onNodeWithText(app.getString(R.string.settings_radar_left_on_title)).performScrollTo()

    private fun dndRow() = composeRule.onNodeWithText(app.getString(R.string.settings_walkaway_dnd_title))

    @Test
    fun itIsOnForANewRider() {
        showScreen()
        row().assertIsOn()
    }

    @Test
    fun tappingItOffReachesPrefsAndHidesTheDndRow() {
        showScreen()
        dndRow().performScrollTo().assertExists()
        row().performClick()
        composeRule.waitForIdle()
        assertFalse(Prefs(app).radarLeftOnAlarmEnabled)
        dndRow().assertDoesNotExist()
    }

    @Test
    fun itOpensOnTheStoredChoice() {
        prefs.radarLeftOnAlarmEnabled = false
        showScreen()
        row().assertIsOff()
        dndRow().assertDoesNotExist()
        row().performClick()
        composeRule.waitForIdle()
        assertTrue(Prefs(app).radarLeftOnAlarmEnabled)
    }

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun theDndRowReadsTheGrantAgainWhenTheRiderComesBack() {
        // The grant is made in system settings, which the row opens.
        val nm = app.getSystemService(NotificationManager::class.java)
        ServiceNotifications(app) { prefs }.ensureChannels()
        val owner = FakeOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UiTheme { SettingsRadarBody(rememberNavController(), prefs) }
            }
        }
        val tap = app.getString(R.string.settings_walkaway_dnd_tap)
        val allowed = app.getString(R.string.settings_walkaway_dnd_allowed)
        composeRule.onNodeWithText(tap).performScrollTo().assertExists()

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        val ch = nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID)
        ch.setBypassDnd(true)
        nm.createNotificationChannel(ch)
        assertTrue("fixture: the grant reads back", nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID).canBypassDnd())
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        composeRule.onNodeWithText(allowed).performScrollTo().assertExists()
    }

    @Test
    fun theDndRowOpensTheWalkAwayChannel() {
        // The same channel the dashcam screen opens: one setting for both alarms.
        showScreen()
        dndRow().performScrollTo().performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started?.action)
        assertEquals("bike_radar_walkaway_v3", started?.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(app.packageName, started?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
