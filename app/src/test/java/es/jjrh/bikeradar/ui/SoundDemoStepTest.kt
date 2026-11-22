// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.AlertCue
import es.jjrh.bikeradar.CuePlayer
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarOverlayView
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors

/** The demo plays only when asked, in order, with the caption in step, and stops when the rider leaves. */
@RunWith(AndroidJUnit4::class)
class SoundDemoStepTest {

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

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val player = FakeCuePlayer()
    private val owner = FakeOwner()
    private var continued = 0
    private var canPlay by mutableStateOf(true)

    @Before
    fun noRadar() {
        RadarStateBus.clear()
    }

    private fun views(v: View = composeRule.activity.window.decorView): List<View> {
        val children = if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        return listOf(v) + children
    }

    private fun screenKeptOn() = views().any { it.keepScreenOn }

    private fun show(alertMaxM: () -> Int = { 20 }, showAlertDistance: Boolean = false) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UiTheme {
                    VolumeKeysFollowCues(Prefs(composeRule.activity))
                    SoundDemoStep(
                        player = player,
                        canPlay = canPlay,
                        onContinue = { continued += 1 },
                        alertMaxM = alertMaxM,
                        showAlertDistance = showAlertDistance,
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private class StripRecorder(bmp: Bitmap) : Canvas(bmp) {
        val labels = mutableListOf<String>()
        val boxes = mutableListOf<RectF>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            labels += text
            super.drawText(text, x, y, paint)
        }
        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            boxes += RectF(rect)
            super.drawRoundRect(rect, rx, ry, paint)
        }
    }

    /**
     * What the live strip draws now: the label of its alert line, which stands
     * for the alert distance it holds, and the boxes narrower than the strip,
     * which leaves out the background and the danger border.
     */
    private fun stripDrawn(): StripRecorder {
        val view = views().filterIsInstance<RadarOverlayView>().single()
        val recorder = StripRecorder(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
        view.draw(recorder)
        recorder.boxes.removeAll { it.width() >= view.width - 4 * view.resources.displayMetrics.density }
        return recorder
    }

    private fun tapPlay() {
        composeRule.onNodeWithText("Play").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    /**
     * Steps the clock until [done] or [limitMs] passes, and returns how long that
     * took. The idle after each step runs the scene's coroutine, which the clock
     * alone does not.
     */
    private fun advanceUntil(limitMs: Long, done: () -> Boolean): Long {
        val start = composeRule.mainClock.currentTime
        while (!done() && composeRule.mainClock.currentTime - start < limitMs) {
            composeRule.mainClock.advanceTimeBy(100L)
            composeRule.waitForIdle()
        }
        val took = composeRule.mainClock.currentTime - start
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        return took
    }

    private fun advance(ms: Long) {
        advanceUntil(ms) { false }
    }

    @Test
    fun nothingSoundsUntilTheRiderTapsPlay() {
        show()
        advance(20_000L)
        assertEquals(emptyList<String>(), player.calls)
    }

    @Test
    fun itSaysWhyTheVolumeButtonsCanMoveTheWakeUpAlarm() {
        // An automatic clock: scrolling waits for idle, which the manual one in show() never reaches.
        composeRule.setContent { UiTheme { SoundDemoStep(player = player, canPlay = true, onContinue = {}) } }
        composeRule.onNodeWithText("your wake-up alarm uses that volume too", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noCaptionUntilTheFirstPlay() {
        show()
        composeRule.onNodeWithText("The radar sees nothing within your alert distance: no sound.").assertDoesNotExist()
        tapPlay()
        composeRule.onNodeWithText("The radar sees nothing within your alert distance: no sound.").assertExists()
    }

    @Test
    fun theSceneSoundsInOrderWithTheCaptionInStep() {
        show()
        tapPlay()
        composeRule.onNodeWithText("Playing…").assertIsNotEnabled()
        val firstBeepAfter = advanceUntil(10_000L) { player.calls.isNotEmpty() }
        assertTrue("first beep after $firstBeepAfter ms", firstBeepAfter in 5_500L..6_500L)
        assertEquals(listOf("play1"), player.calls)
        composeRule.onNodeWithText("A car behind you: one beep.").assertExists()

        advanceUntil(20_000L) { player.calls.size >= 5 }
        assertEquals(listOf("play1", "play2", "play3", "clear", "urgent"), player.calls)
        composeRule.onNodeWithText("Closing fast while you are stopped or slow: the urgent warning (only on radars that report speed).").assertExists()
        advanceUntil(5_000L) { false }
        composeRule.onNodeWithText("Play again").assertExists()
    }

    @Test
    fun theSceneRunsAtTheAlertDistanceItIsGiven() {
        show(alertMaxM = { 10 })
        tapPlay()
        // The scene's own times at 10 m are 8,400 ms and 16,600 ms; at 20 m, 6,000 ms and 15,900 ms.
        val firstBeepAfter = advanceUntil(12_000L) { player.calls.isNotEmpty() }
        assertTrue("first beep after $firstBeepAfter ms", firstBeepAfter in 7_900L..8_900L)
        val urgentAfter = advanceUntil(20_000L) { player.calls.size >= 5 } + firstBeepAfter
        assertEquals(listOf("play1", "play2", "play3", "clear", "urgent"), player.calls)
        assertTrue("urgent after $urgentAfter ms", urgentAfter in 16_100L..17_100L)
    }

    @Test
    fun theAlertDistanceLineNamesTheDistanceTheSceneRunsAt() {
        show(alertMaxM = { 10 }, showAlertDistance = true)
        composeRule.onNodeWithText("Alert distance: 10 m").assertExists()
        tapPlay()
        advance(500L)
        composeRule.onNodeWithText("Alert distance: 10 m").assertExists()
    }

    @Test
    fun theAlertDistanceIsReadWhenPlayIsTappedAndHeldForTheRun() {
        var riders by mutableStateOf(20)
        show(alertMaxM = { riders }, showAlertDistance = true)
        composeRule.onNodeWithText("Alert distance: 20 m").assertExists()
        riders = 10
        advance(500L)
        composeRule.onNodeWithText("Alert distance: 20 m").assertExists()
        composeRule.onNodeWithText("Alert distance: 10 m").assertDoesNotExist()
        tapPlay()
        composeRule.onNodeWithText("Alert distance: 10 m").assertExists()
        advance(2_000L)
        assertEquals(listOf("10m"), stripDrawn().labels)
        riders = 30
        advance(500L)
        composeRule.onNodeWithText("Alert distance: 10 m").assertExists()
        composeRule.onNodeWithText("Alert distance: 30 m").assertDoesNotExist()
        assertEquals(listOf("10m"), stripDrawn().labels)
    }

    @Test
    fun theLiveStripFollowsTheAlertDistanceItIsGiven() {
        var alertMaxM by mutableStateOf(10)
        composeRule.setContent {
            UiTheme {
                SoundDemoStepContent(
                    tMs = 2_000L,
                    lastCue = null,
                    playing = true,
                    played = true,
                    canPlay = true,
                    onPlay = {},
                    onContinue = {},
                    alertMaxM = alertMaxM,
                )
            }
        }
        composeRule.waitForIdle()
        val atTen = stripDrawn()
        assertEquals(listOf("10m"), atTen.labels)
        alertMaxM = 40
        composeRule.waitForIdle()
        val atForty = stripDrawn()
        assertEquals(listOf("40m"), atForty.labels)
        // Car 1 is 36 m out at 2 s: past the strip's end at 10 m, drawn at 40 m.
        assertTrue("boxes ${atTen.boxes.size} at 10 m, ${atForty.boxes.size} at 40 m", atForty.boxes.size > atTen.boxes.size)
    }

    @Test
    fun aWatchedSceneAtAnotherAlertDistanceStillSaysItThroughARotation() {
        val restore = StateRestorationTester(composeRule)
        composeRule.mainClock.autoAdvance = false
        restore.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UiTheme { SoundDemoStep(player = player, canPlay = true, onContinue = {}, alertMaxM = { 10 }, showAlertDistance = true) }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        tapPlay()
        advance(25_000L)
        composeRule.mainClock.autoAdvance = true
        restore.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Play again").assertExists()
        composeRule.onNodeWithText("Alert distance: 10 m").assertExists()
    }

    @Test
    fun noAlertDistanceLineUnlessTheScreenAsksForIt() {
        show(alertMaxM = { 10 })
        tapPlay()
        advance(500L)
        composeRule.onNodeWithText("Alert distance:", substring = true).assertDoesNotExist()
    }

    @Test
    fun theSettingsRoutePlaysTheSceneAtTheRidersAlertDistance() {
        Prefs(composeRule.activity).alertMaxDistanceM = 10
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SoundDemoScreen(
                prefs = Prefs(composeRule.activity),
                mark = R.string.alert_sounds_title,
                onDone = {},
                doneLabel = R.string.common_done,
                followsAlertDistance = true,
            )
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Alert distance: 10 m").assertExists()
        tapPlay()
        advance(2_000L)
        assertEquals(listOf("10m"), stripDrawn().labels)
    }

    @Test
    fun onboardingPlaysTheSceneAtTheDefaultDistanceWhateverTheRiderSet() {
        val prefs = Prefs(composeRule.activity)
        prefs.alertMaxDistanceM = 10
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OnboardingScreen(navController = rememberNavController(), prefs = prefs, onFinished = {})
        }
        composeRule.mainClock.advanceTimeByFrame()
        tapPlay()
        advance(2_000L)
        assertEquals(listOf("20m"), stripDrawn().labels)
        composeRule.onNodeWithText("Alert distance:", substring = true).assertDoesNotExist()
    }

    @Test
    fun theScreenStaysOnOnlyWhileTheScenePlays() {
        show()
        assertFalse(screenKeptOn())
        tapPlay()
        assertTrue(screenKeptOn())
        advance(25_000L)
        assertFalse(screenKeptOn())
    }

    @Test
    fun theStripDrawsTheCarTheBeepsAreAbout() {
        shadowOf(composeRule.activity.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        show()
        tapPlay()
        advanceUntil(12_000L) { player.calls.size >= 3 }
        val strip = views().filterIsInstance<RadarOverlayView>().single()
        assertTrue("strip says ${strip.contentDescription}", strip.contentDescription.toString().contains("1 vehicle"))
    }

    @Test
    fun atTheShortestAlertDistanceTheCarIsDrawnJustBeforeItsFirstBeep() {
        show(alertMaxM = { 10 })
        val empty = stripDrawn().boxes.size
        tapPlay()
        val drawnAfter = advanceUntil(12_000L) { stripDrawn().boxes.size > empty }
        assertTrue("no car drawn after $drawnAfter ms", stripDrawn().boxes.size > empty)
        assertEquals("a sound before the car was drawn", emptyList<String>(), player.calls)
        val beepAfterDrawn = advanceUntil(12_000L) { player.calls.isNotEmpty() }
        assertEquals(listOf("play1"), player.calls)
        assertEquals(listOf("10m"), stripDrawn().labels)
        // Measured at one 112 ms step on the run's clock; a scene driven at 9 m takes three.
        assertTrue("car drawn at $drawnAfter ms, then $beepAfterDrawn ms to the beep", beepAfterDrawn <= 250L)
    }

    @Test
    fun continuingStopsTheScene() {
        show()
        tapPlay()
        advanceUntil(10_000L) { player.calls.isNotEmpty() }
        composeRule.onNodeWithText("Continue").performClick()
        advance(20_000L)
        assertEquals(1, continued)
        assertEquals(listOf("play1"), player.calls)
    }

    @Test
    fun leavingTheAppStopsTheScene() {
        show()
        tapPlay()
        advanceUntil(10_000L) { player.calls.isNotEmpty() }
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        advance(20_000L)
        assertEquals(listOf("play1"), player.calls)
    }

    @Test
    fun playAgainStartsTheSceneOver() {
        show()
        tapPlay()
        advance(25_000L)
        composeRule.onNodeWithText("Play again").performClick()
        composeRule.waitForIdle()
        advance(100L)
        composeRule.onNodeWithText("The radar sees nothing within your alert distance: no sound.").assertExists()
        advanceUntil(10_000L) { player.calls.size >= 6 }
        assertEquals(listOf("play1", "play2", "play3", "clear", "urgent", "play1"), player.calls)
    }

    @Test
    fun aWatchedDemoFromSettingsEndsInDone() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UiTheme {
                    SoundDemoStep(player = player, canPlay = true, onContinue = { continued += 1 }, doneLabel = R.string.common_done)
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        tapPlay()
        advance(25_000L)
        composeRule.onNodeWithText("Play again").assertExists()
        composeRule.onNodeWithText("Continue").assertDoesNotExist()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitForIdle()
        assertEquals(1, continued)
    }

    private val audio get() = composeRule.activity.getSystemService(AudioManager::class.java)

    private fun volumes(media: Int, alarm: Int) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, media, 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, alarm, 0)
    }

    private val keysMove get() = composeRule.activity.volumeControlStream

    @Test
    fun theVolumeButtonsMoveTheAlarmVolumeOnlyWhileTheDemoShows() {
        volumes(media = 0, alarm = 5)
        var showing by mutableStateOf(true)
        composeRule.setContent {
            UiTheme {
                if (showing) {
                    VolumeKeysFollowCues(Prefs(composeRule.activity))
                    SoundDemoStep(player = player, canPlay = true, onContinue = {})
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(AudioManager.STREAM_ALARM, keysMove)
        showing = false
        composeRule.waitForIdle()
        assertEquals(AudioManager.USE_DEFAULT_STREAM_TYPE, keysMove)
    }

    @Test
    fun withMediaLouderThanTheAlarmTheButtonsMoveMedia() {
        volumes(media = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), alarm = 1)
        show()
        assertEquals(AudioManager.STREAM_MUSIC, keysMove)
    }

    @Test
    fun theButtonsFollowAVolumeTheRiderChanges() {
        volumes(media = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), alarm = 1)
        show()
        assertEquals(AudioManager.STREAM_MUSIC, keysMove)
        volumes(media = 0, alarm = 1)
        advance(500L)
        assertEquals(AudioManager.STREAM_ALARM, keysMove)
    }

    @Test
    fun theButtonsAreReReadOnReturnNotWhileAway() {
        volumes(media = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), alarm = 1)
        show()
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        volumes(media = 0, alarm = 1)
        advance(1_000L)
        assertEquals(AudioManager.STREAM_MUSIC, keysMove)
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        advance(500L)
        assertEquals(AudioManager.STREAM_ALARM, keysMove)
    }

    @Test
    fun whileACueHoldsTheAlarmUpTheRidersOwnLevelDecides() {
        // Lifted to max for loud media; the slot holds the rider's own 1.
        Prefs(composeRule.activity).alertBeeperSavedAlarmVolume = 1
        volumes(media = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), alarm = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
        show()
        assertEquals(AudioManager.STREAM_MUSIC, keysMove)
        Prefs(composeRule.activity).alertBeeperSavedAlarmVolume = null
    }

    @Test
    fun whileTheRadarStreamsNothingPlaysAndTheScreenSaysWhy() {
        canPlay = false
        show()
        composeRule.onNodeWithText("Plays only while the radar is off.").assertExists()
        composeRule.onNodeWithText("The radar sees nothing within your alert distance: no sound.").assertDoesNotExist()
        composeRule.onNodeWithText("Play").assertIsNotEnabled()
        advance(20_000L)
        assertEquals(emptyList<String>(), player.calls)
    }

    @Test
    fun aStreamingRadarLocksTheDemoScreen() {
        RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = System.currentTimeMillis()))
        try {
            composeRule.setContent {
                SoundDemoScreen(prefs = Prefs(composeRule.activity), mark = R.string.sound_demo_mark, onDone = {}, followsAlertDistance = false)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Play").assertIsNotEnabled()
        } finally {
            RadarStateBus.clear()
        }
    }

    @Test
    fun aRadarThatStartsStreamingStopsTheScene() {
        show()
        tapPlay()
        advanceUntil(10_000L) { player.calls.isNotEmpty() }
        composeRule.onNodeWithText("A car behind you: one beep.").assertExists()
        canPlay = false
        advance(20_000L)
        assertEquals(listOf("play1"), player.calls)
        composeRule.onNodeWithText("A car behind you: one beep.").assertDoesNotExist()
        composeRule.onNodeWithText("Play again").assertDoesNotExist()
        composeRule.onNodeWithText("Play").assertIsNotEnabled()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        assertEquals(1, continued)
    }

    @Test
    fun aRadarThatStartsStreamingWhileTheScreenIsOpenLocksItAtOnce() {
        composeRule.setContent {
            SoundDemoScreen(prefs = Prefs(composeRule.activity), mark = R.string.sound_demo_mark, onDone = {}, followsAlertDistance = false)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Play").assertIsEnabled()
        try {
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = System.currentTimeMillis()))
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithText("Plays only while the radar is off.").assertExists()
            composeRule.onNodeWithText("Play").assertIsNotEnabled()
        } finally {
            RadarStateBus.clear()
        }
    }

    @Test
    fun continuingOnboardingMidSceneLetsTheScreenSleepAgain() {
        val prefs = Prefs(composeRule.activity)
        // A manual clock: an automatic one runs the whole scene to its end on
        // the first idle, and the screen is let go before anything is checked.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OnboardingScreen(navController = rememberNavController(), prefs = prefs, onFinished = {})
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Play").performClick()
        advance(1_000L)
        assertTrue(screenKeptOn())
        composeRule.onNodeWithText("Continue").performClick()
        advance(500L)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.alert_volume_title)).assertExists()
        assertFalse(screenKeptOn())
    }

    @Test
    fun theDemoScreenPlaysWhileNoRadarStreams() {
        composeRule.setContent {
            SoundDemoScreen(prefs = Prefs(composeRule.activity), mark = R.string.sound_demo_mark, onDone = {}, followsAlertDistance = false)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Play").assertIsEnabled()
    }

    @Test
    fun onboardingOpensOnTheDemoThenTheVolumeThenTheFirstStep() {
        val prefs = Prefs(composeRule.activity)
        composeRule.setContent {
            OnboardingScreen(navController = rememberNavController(), prefs = prefs, onFinished = {})
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.sound_demo_title)).assertExists()
        composeRule.onNodeWithText("BEFORE YOUR FIRST RIDE").assertExists()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.alert_volume_title)).assertExists()
        composeRule.onNodeWithText("BEFORE YOUR FIRST RIDE").assertExists()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.onboarding_perm_title)).assertExists()
    }

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun backWalksOnboardingTheWayContinueCame() {
        val prefs = Prefs(composeRule.activity)
        composeRule.setContent {
            OnboardingScreen(navController = rememberNavController(), prefs = prefs, onFinished = {})
        }
        val demo = composeRule.activity.getString(R.string.sound_demo_title)
        val volume = composeRule.activity.getString(R.string.alert_volume_title)
        val perms = composeRule.activity.getString(R.string.onboarding_perm_title)
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(perms).assertExists()
        back()
        composeRule.onNodeWithText(volume).assertExists()
        back()
        composeRule.onNodeWithText(demo).assertExists()
        assertFalse(composeRule.activity.isFinishing)
    }

    @Test
    fun aWatchedSceneStaysWatchedThroughARotation() {
        val restore = StateRestorationTester(composeRule)
        composeRule.mainClock.autoAdvance = false
        restore.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UiTheme { SoundDemoStep(player = player, canPlay = true, onContinue = {}) }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        tapPlay()
        advance(25_000L)
        // The restore takes frames to drop and re-add the content.
        composeRule.mainClock.autoAdvance = true
        restore.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Play again").assertExists()
    }

    @Test
    fun closingTheScreenReleasesItsPlayer() {
        val prefs = Prefs(composeRule.activity)
        val executor = Executors.newSingleThreadExecutor()
        var showing by mutableStateOf(true)
        var shown: CuePlayer? = null
        composeRule.setContent {
            if (showing) shown = rememberDemoCuePlayer(prefs) { newDemoBeeper(it, prefs, executor) }
        }
        composeRule.waitForIdle()
        shown!!.play(1)
        assertFalse(executor.isShutdown)
        showing = false
        composeRule.waitForIdle()
        assertTrue(executor.isShutdown)
    }

    @Test
    fun aRiderPastTheDemoIsNotSentBackToItByARotation() {
        val prefs = Prefs(composeRule.activity)
        val restore = StateRestorationTester(composeRule)
        restore.setContent {
            OnboardingScreen(navController = rememberNavController(), prefs = prefs, onFinished = {})
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.alert_volume_title)).assertExists()
    }

    @Test
    fun aRiderPastTheVolumeIsNotSentBackToItByARotation() {
        val prefs = Prefs(composeRule.activity)
        val restore = StateRestorationTester(composeRule)
        restore.setContent {
            OnboardingScreen(navController = rememberNavController(), prefs = prefs, onFinished = {})
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.onboarding_perm_title)).assertExists()
    }

    @Test
    fun everyCueHasItsOwnCaption() {
        val captions = listOf(null, AlertCue.Silence, AlertCue.Beep(1), AlertCue.Beep(2), AlertCue.Beep(3), AlertCue.Clear, AlertCue.Urgent)
            .map { composeRule.activity.getString(soundDemoCaption(it)) }
        assertEquals(
            listOf(
                "The radar sees nothing within your alert distance: no sound.",
                "The radar sees nothing within your alert distance: no sound.",
                "A car behind you: one beep.",
                "At mid distance: two beeps.",
                "Close to you: three beeps.",
                "The radar sees nothing within your alert distance any more: the all-clear.",
                "Closing fast while you are stopped or slow: the urgent warning (only on radars that report speed).",
            ),
            captions,
        )
    }
}
