// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.MainActivity
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The glossary and the demo reached the way a rider reaches them, through the
 * activity's own navigation graph: a route string that does not match its
 * registration only fails here, as a crash on the tap.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAlertSoundsRouteTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    // An init block, not @Before: the rule launches the activity before @Before runs.
    init {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        Prefs(app).apply {
            safetyNoticeAcknowledged = true
            firstRunComplete = true
            serviceEnabled = false
        }
    }

    @After
    fun restore() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun tap(text: String, scroll: Boolean = true) {
        val node = compose.onNodeWithText(text)
        if (scroll) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    @Test
    fun settingsReachesTheGlossaryAndTheDemoAndComesBack() {
        tap("Settings", scroll = false)
        tap("Alerts")
        tap("Alert sounds")
        compose.onNodeWithText("Three low pulses").assertExists()
        tap("Watch the example ride again")
        compose.onNodeWithText("What you'll hear").assertExists()
        compose.onNodeWithText("BEFORE YOUR FIRST RIDE").assertDoesNotExist()
        compose.onNodeWithText("ALERT SOUNDS").assertExists()
        tap("Continue", scroll = false)
        compose.onNodeWithText("Watch the example ride again").assertExists()
    }

    /**
     * Loud media and a quiet alarm, so any cue the real beeper plays writes the
     * rider's alarm level to the shared slot before it lifts the stream.
     */
    private fun loudMedia(): AudioManager = compose.activity.getSystemService(AudioManager::class.java).apply {
        mode = AudioManager.MODE_NORMAL
        setStreamVolume(AudioManager.STREAM_MUSIC, getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
    }

    private fun slotWritten(): Int? {
        val deadline = System.currentTimeMillis() + 5_000L
        while (Prefs(app).alertBeeperSavedAlarmVolume == null && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        return Prefs(app).alertBeeperSavedAlarmVolume
    }

    @Test
    fun aGlossaryRowSoundsThroughTheRealBeeper() {
        loudMedia()
        tap("Settings", scroll = false)
        tap("Alerts")
        tap("Alert sounds")
        tap("One beep")
        assertEquals(1, slotWritten())
    }

    @Test
    fun theReplaySoundsThroughTheRealBeeper() {
        loudMedia()
        tap("Settings", scroll = false)
        tap("Alerts")
        tap("Alert sounds")
        tap("Watch the example ride again")
        tap("Play", scroll = false)
        compose.mainClock.advanceTimeBy(3_600L)
        compose.waitForIdle()
        assertEquals(1, slotWritten())
    }

    @Test
    fun theVolumeButtonsFollowTheCuesAcrossEveryHopAndLetGoAfter() {
        val audio = compose.activity.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        fun settle() {
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
        }
        tap("Settings", scroll = false)
        tap("Alerts")
        tap("Alert sounds")
        settle()
        assertEquals(AudioManager.STREAM_ALARM, compose.activity.volumeControlStream)
        tap("Watch the example ride again")
        settle()
        assertEquals(AudioManager.STREAM_ALARM, compose.activity.volumeControlStream)
        tap("Continue", scroll = false)
        settle()
        assertEquals(AudioManager.STREAM_ALARM, compose.activity.volumeControlStream)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(AudioManager.USE_DEFAULT_STREAM_TYPE, compose.activity.volumeControlStream)
    }
}
