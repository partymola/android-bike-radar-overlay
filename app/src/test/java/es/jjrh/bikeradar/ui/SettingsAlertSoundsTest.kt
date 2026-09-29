// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.CuePlayer
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each row plays its own sound, nothing plays while the radar streams, and the demo is one tap away. */
@RunWith(AndroidJUnit4::class)
class SettingsAlertSoundsTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeCuePlayer : CuePlayer {
        val calls = mutableListOf<String>()
        override fun play(beeps: Int) {
            calls += "play$beeps"
        }
        override fun playClear() {
            calls += "clear"
        }
        override fun playUrgent() {
            calls += "urgent"
        }
        override fun playRadarDropped() {
            calls += "dropped"
        }
        override fun playRadarReconnected() {
            calls += "reconnected"
        }
    }

    private val player = FakeCuePlayer()
    private var demos = 0
    private var volumes = 0

    private val rows = listOf(
        "One beep" to "play1",
        "Two beeps" to "play2",
        "Three beeps" to "play3",
        "All-clear" to "clear",
        "Urgent warning" to "urgent",
        "Three low pulses" to "dropped",
        "One low pulse" to "reconnected",
    )

    @Before
    @After
    fun clearBus() {
        RadarStateBus.clear()
    }

    private fun show(riding: Boolean) {
        composeRule.setContent {
            UiTheme {
                SettingsAlertSoundsBody(
                    player = player,
                    riding = riding,
                    onBack = {},
                    onWatchDemo = { demos += 1 },
                    onSetVolume = { volumes += 1 },
                )
            }
        }
    }

    private fun tap(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun eachRowPlaysItsOwnSound() {
        show(riding = false)
        rows.forEach { (title, _) -> tap(title) }
        assertEquals(rows.map { it.second }, player.calls)
    }

    @Test
    fun nothingPlaysWhileTheRadarStreams() {
        show(riding = true)
        composeRule.onNodeWithText("The sounds play only while the radar is off.").assertExists()
        rows.forEach { (title, _) -> tap(title) }
        tap("Watch the example ride again")
        assertEquals(emptyList<String>(), player.calls)
        assertEquals(0, demos)
    }

    @Test
    fun theDemoIsOneTapAway() {
        show(riding = false)
        composeRule.onNodeWithText("The sounds play only while the radar is off.").assertDoesNotExist()
        tap("Watch the example ride again")
        assertEquals(1, demos)
    }

    @Test
    fun theVolumeIsOneTapAwayEvenWhileTheRadarStreams() {
        show(riding = true)
        tap("Set the alert volume")
        assertEquals(1, volumes)
        assertEquals(0, demos)
    }

    @Test
    fun withMediaLouderThanTheAlarmTheButtonsMoveMedia() {
        val audio = composeRule.activity.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val prefs = Prefs(ApplicationProvider.getApplicationContext<Application>())
        composeRule.setContent { SettingsAlertSounds(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
        assertEquals(AudioManager.STREAM_MUSIC, composeRule.activity.volumeControlStream)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        assertEquals(AudioManager.STREAM_ALARM, composeRule.activity.volumeControlStream)
    }

    @Test
    fun aStreamingRadarLocksTheScreenItOpens() {
        RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = System.currentTimeMillis()))
        val prefs = Prefs(ApplicationProvider.getApplicationContext<Application>())
        composeRule.setContent { SettingsAlertSounds(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("The sounds play only while the radar is off.").assertExists()
    }

    @Test
    fun aRadarThatStopsStreamingUnlocksTheScreenWithoutLeavingIt() {
        // The last frame goes stale 10 s after it arrived, five seconds from now:
        // wide enough that a slow first composition still sees it fresh.
        RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = System.currentTimeMillis() - 5_000L))
        val prefs = Prefs(ApplicationProvider.getApplicationContext<Application>())
        composeRule.setContent { SettingsAlertSounds(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("The sounds play only while the radar is off.").assertExists()
        Thread.sleep(5_500L)
        composeRule.mainClock.advanceTimeBy(5_100L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("The sounds play only while the radar is off.").assertDoesNotExist()
    }

    @Test
    fun aRadarThatStartsStreamingWhileTheScreenIsOpenLocksItAtOnce() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext<Application>())
        composeRule.setContent { SettingsAlertSounds(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("The sounds play only while the radar is off.").assertDoesNotExist()
        RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = System.currentTimeMillis()))
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("The sounds play only while the radar is off.").assertExists()
    }

    @Test
    fun aRadarThatIsNotStreamingLeavesItOpen() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext<Application>())
        composeRule.setContent { SettingsAlertSounds(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("The sounds play only while the radar is off.").assertDoesNotExist()
    }
}
