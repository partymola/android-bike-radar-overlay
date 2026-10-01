// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.content.Context
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.AlertBeeper
import es.jjrh.bikeradar.CuePlayer
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The volume step plays a test sound, sets the alert volume the ride uses, and waits while the radar streams. */
@RunWith(AndroidJUnit4::class)
class AlertVolumeStepTest {

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
    private val gains = mutableListOf<Int>()
    private var done = 0
    private val prefs get() = Prefs(composeRule.activity)

    @Before
    fun noRadar() {
        RadarStateBus.clear()
    }

    @After
    fun clear() {
        RadarStateBus.clear()
        composeRule.activity.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun show(canPlay: Boolean = true) {
        composeRule.setContent {
            UiTheme {
                AlertVolumeStep(
                    prefs = prefs,
                    player = player,
                    setGain = { gains += it },
                    canPlay = canPlay,
                    mark = R.string.sound_demo_mark,
                    onContinue = { done += 1 },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun slider() = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    @Test
    fun theTestSoundIsThreeBeeps() {
        show()
        composeRule.onNodeWithText("Play a test sound").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("play3"), player.calls)
    }

    @Test
    fun theScreenOpensAtTheSavedVolume() {
        prefs.alertVolume = 30
        show()
        composeRule.onNodeWithText("30%").assertExists()
    }

    @Test
    fun theSliderSetsTheVolumeTheRideUsesAndTheTestSoundsGain() {
        prefs.alertVolume = 50
        show()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        composeRule.waitForIdle()
        assertEquals(80, prefs.alertVolume)
        assertEquals(80, gains.last())
        composeRule.onNodeWithText("80%").assertExists()
    }

    @Test
    fun whileTheRadarStreamsTheTestSoundWaitsButTheSliderWorks() {
        prefs.alertVolume = 50
        show(canPlay = false)
        composeRule.onNodeWithText("The test sound plays only while the radar is off. The volume still changes.").assertExists()
        composeRule.onNodeWithText("Play a test sound").assertIsNotEnabled()
        composeRule.onNodeWithText("Play a test sound").performClick()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), player.calls)
        assertEquals(20, prefs.alertVolume)
    }

    @Test
    fun aDragThatNeverLiftsStillSavesWhatTheSliderShows() {
        prefs.alertVolume = 50
        show()
        // No up(): the drag is still going, so the slider never reports it finished.
        slider().performTouchInput {
            down(center)
            moveTo(centerRight)
        }
        composeRule.waitForIdle()
        val saved = prefs.alertVolume
        assertTrue("saved $saved", saved > 80)
        composeRule.onNodeWithText("$saved%").assertExists()
        assertEquals(saved, gains.last())
    }

    @Test
    fun continuingMovesOn() {
        show()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        assertEquals(1, done)
    }

    @Test
    fun theScreenLocksWhileARadarStreamsAndPlaysWhileNoneDoes() {
        composeRule.setContent { AlertVolumeScreen(prefs = prefs, mark = R.string.sound_demo_mark, onDone = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Play a test sound").assertIsEnabled()
        RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = System.currentTimeMillis()))
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Play a test sound").assertIsNotEnabled()
    }

    @Test
    fun aTestSoundAfterTheSliderMovesPlaysAtTheNewVolume() {
        // The beeper is built by the first sound, so the slider moves after it.
        prefs.alertVolume = 50
        val audio = composeRule.activity.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        val heard = mutableListOf<String>()
        var beeper: AlertBeeper? = null
        composeRule.setContent {
            AlertVolumeScreen(prefs = prefs, mark = R.string.sound_demo_mark, onDone = {}, create = {
                AlertBeeper(audioManager = audio, executor = { it.run() }, playTrackOverride = { true }, onCue = { heard += it })
                    .also { b ->
                        b.setVolumePct(prefs.alertVolume)
                        beeper = b
                    }
            })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Play a test sound").performClick()
        composeRule.waitForIdle()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        composeRule.waitForIdle()
        assertEquals(80, beeper!!.currentVolumePct)
        composeRule.onNodeWithText("Play a test sound").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("beep count=3", "beep count=3"), heard)
        assertEquals(80, beeper.currentVolumePct)
    }

    @Test
    fun theVolumeButtonsFollowTheAlertLoudnessOnTheScreen() {
        val audio = composeRule.activity.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        composeRule.setContent { AlertVolumeScreen(prefs = prefs, mark = R.string.sound_demo_mark, onDone = {}) }
        composeRule.waitForIdle()
        assertEquals(AudioManager.STREAM_MUSIC, composeRule.activity.volumeControlStream)
    }

    @Test
    fun withNoMediaPlayingTheVolumeButtonsMoveTheAlarmOnTheScreen() {
        val audio = composeRule.activity.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        composeRule.setContent { AlertVolumeScreen(prefs = prefs, mark = R.string.sound_demo_mark, onDone = {}) }
        composeRule.waitForIdle()
        assertEquals(AudioManager.STREAM_ALARM, composeRule.activity.volumeControlStream)
    }
}
