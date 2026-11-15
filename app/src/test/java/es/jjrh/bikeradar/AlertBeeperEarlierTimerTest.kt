// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.media.AudioManager
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowSystemClock
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An earlier burst's abandon timer that runs during a later cue leaves that
 * cue's audio focus, alarm-floor lift and [AlertBeeper.cueSounding] alone,
 * whether it is still queued, already dequeued or part-way through its body;
 * a later cue that starts while it runs waits for it and then takes focus and
 * the lift afresh.
 *
 * The playback executor and the main looper are separate threads on a phone.
 * Tests where the cue must wait for the timer run the beeper on a real
 * executor thread while the test thread drives the main looper.
 *
 * Robolectric: alarm range 0..7, media range 0..15. Media at 15 over an alarm
 * at 1 lifts the alarm to 7. A one-beep cue's timer is due 130 ms after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [AlertBeeperEarlierTimerTest.FocusRecordingShadowAudioManager::class])
class AlertBeeperEarlierTimerTest {

    /** Records focus requests and abandons in order, and can run a hook just
     *  before an abandon or an alarm-stream write takes effect. */
    @Implements(AudioManager::class)
    class FocusRecordingShadowAudioManager : ShadowAudioManager() {
        val focusEvents: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile var beforeNextAbandon: (() -> Unit)? = null

        @Volatile var beforeNextAlarmWrite: (() -> Unit)? = null

        @Implementation
        override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
            if (streamType == AudioManager.STREAM_ALARM) {
                beforeNextAlarmWrite?.let {
                    beforeNextAlarmWrite = null
                    it()
                }
            }
            super.setStreamVolume(streamType, index, flags)
        }

        @Implementation
        override fun requestAudioFocus(audioFocusRequest: android.media.AudioFocusRequest): Int {
            focusEvents += "request"
            return super.requestAudioFocus(audioFocusRequest)
        }

        @Implementation
        override fun abandonAudioFocusRequest(audioFocusRequest: android.media.AudioFocusRequest): Int {
            beforeNextAbandon?.let {
                beforeNextAbandon = null
                it()
            }
            focusEvents += "abandon"
            return super.abandonAudioFocusRequest(audioFocusRequest)
        }
    }

    private lateinit var audio: AudioManager
    private lateinit var focus: FocusRecordingShadowAudioManager
    private val main get() = shadowOf(Looper.getMainLooper())
    private val alarm get() = audio.getStreamVolume(AudioManager.STREAM_ALARM)
    private var worker: ExecutorService? = null

    /** Waits a hook gave up on. A hook runs inside the code under test, on
     *  either thread, sometimes within a catch-all, so it records a miss here
     *  and the test thread asserts the list is empty. */
    private val hookFailures: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun setup() {
        audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        focus = Shadow.extract(audio)
        audio.mode = AudioManager.MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 15, 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
    }

    @After fun tearDown() {
        worker?.shutdownNow()
    }

    @Test
    fun aTimerDueDuringALaterCuesPlayLeavesItsFocusAndLift() {
        var plays = 0
        val beeper = AlertBeeper(
            audioManager = audio,
            executor = { it.run() },
            playTrackOverride = {
                plays++
                // The first timer comes due while the second cue plays.
                if (plays == 2) main.idleFor(Duration.ofMillis(1))
                true
            },
        )
        beeper.play(1)
        main.idleFor(Duration.ofMillis(129))
        beeper.play(1)
        assertEquals(listOf("request"), focus.focusEvents)
        assertEquals(7, alarm)
        beeper.release()
    }

    @Test
    fun aTimerDueDuringALaterCuesPlayLeavesItReadingSounding() {
        var plays = 0
        val seen = mutableListOf<Boolean>()
        lateinit var beeper: AlertBeeper
        beeper = AlertBeeper(
            audioManager = audio,
            executor = { it.run() },
            playTrackOverride = {
                plays++
                if (plays == 2) {
                    main.idleFor(Duration.ofMillis(1))
                    seen += beeper.cueSounding.value
                }
                true
            },
        )
        beeper.play(1)
        main.idleFor(Duration.ofMillis(129))
        beeper.play(1)
        assertEquals(listOf(true), seen)
        beeper.release()
    }

    @Test
    fun anEarlierBurstsAbandonTimerDoesNotEndALaterCue() {
        // Dequeued before the second cue took the timer back, so it runs after
        // that cue has started: by hand, at its own due time, 1 ms into the cue.
        val beeper = AlertBeeper(audioManager = audio, executor = { it.run() }, playTrackOverride = { true })
        val abandon = AlertBeeper::class.java.getDeclaredField("abandonRunnable")
            .apply { isAccessible = true }.get(beeper) as Runnable
        beeper.play(1)
        main.idleFor(Duration.ofMillis(129))
        beeper.play(1)
        main.idleFor(Duration.ofMillis(1))
        abandon.run()
        assertEquals(listOf("request"), focus.focusEvents)
        assertEquals(7, alarm)
        assertTrue(beeper.cueSounding.value)
        // The second cue's own timer, 130 ms after it, ends the burst once.
        main.idleFor(Duration.ofMillis(128))
        assertEquals(listOf("request"), focus.focusEvents)
        main.idleFor(Duration.ofMillis(1))
        assertEquals(listOf("request", "abandon"), focus.focusEvents)
        assertEquals(1, alarm)
        assertFalse(beeper.cueSounding.value)
        main.idleFor(Duration.ofSeconds(1))
        assertEquals(listOf("request", "abandon"), focus.focusEvents)
        beeper.release()
    }

    @Test
    fun aTimerDequeuedAsALaterCueStartsDoesNothingDuringItsPlay() {
        // Dequeued at its due time just before the second cue began, so the cue
        // found nothing to take back; its body runs while the cue plays. The
        // clock moves without running the looper, and the body is run by hand.
        lateinit var abandon: Runnable
        var plays = 0
        val beeper = AlertBeeper(
            audioManager = audio,
            executor = { it.run() },
            playTrackOverride = {
                plays++
                if (plays == 2) abandon.run()
                true
            },
        )
        abandon = AlertBeeper::class.java.getDeclaredField("abandonRunnable")
            .apply { isAccessible = true }.get(beeper) as Runnable
        beeper.play(1)
        ShadowSystemClock.advanceBy(Duration.ofMillis(130))
        beeper.play(1)
        assertEquals(listOf("request"), focus.focusEvents)
        assertEquals(7, alarm)
        assertTrue(beeper.cueSounding.value)
        beeper.release()
    }

    @Test
    fun aReleaseDuringALaterCuesFloorLiftStillRestoresTheRidersLevel() {
        val beeper = beeperOnAWorker()
        val mainThread = Thread.currentThread()
        val inLift = CountDownLatch(1)
        val released = AtomicBoolean(false)
        // On the worker, as the second cue writes its lift.
        focus.beforeNextAlarmWrite = {
            inLift.countDown()
            if (!waitUntil { released.get() || waitsOnLockOf(mainThread, owner = workerThread) }) {
                hookFailures += "the release to finish or wait on the cue's lift"
            }
        }
        startSecondCue()
        assertTrue(inLift.await(5, TimeUnit.SECONDS))
        beeper.release()
        released.set(true)
        assertTrue(secondCueDone.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), hookFailures)
        main.idleFor(Duration.ofSeconds(1))
        assertEquals(1, alarm)
    }

    @Test
    fun aCueStartingWhileATimerRestoresTheAlarmWaitsAndLiftsItAgain() {
        val armed = AtomicBoolean(false)
        val beeper = beeperOnAWorker(
            walkAwayOverrideActive = {
                // restoreAlarmFloor's walk-away check: the timer has given focus
                // back and not yet written the rider's level.
                if (armed.getAndSet(false)) startSecondCueAndWait()
                false
            },
        )
        armed.set(true)
        main.idleFor(Duration.ofMillis(130))
        assertTrue(secondCueDone.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), hookFailures)
        assertEquals(listOf("request", "abandon", "request"), focus.focusEvents)
        assertEquals(7, alarm)
        assertTrue(beeper.cueSounding.value)
        beeper.release()
    }

    @Test
    fun aCueStartingWhileATimerAbandonsFocusWaitsAndTakesItAgain() {
        val beeper = beeperOnAWorker()
        // Inside the timer, after it has read that focus is held.
        focus.beforeNextAbandon = { startSecondCueAndWait() }
        main.idleFor(Duration.ofMillis(130))
        assertTrue(secondCueDone.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), hookFailures)
        assertEquals(listOf("request", "abandon", "request"), focus.focusEvents)
        assertEquals(7, alarm)
        assertTrue(beeper.cueSounding.value)
        beeper.release()
    }

    @Test
    fun aCueWhosePlayThrowsStillEndsItsBurst() {
        val beeper = AlertBeeper(
            audioManager = audio,
            executor = { it.run() },
            playTrackOverride = { throw IllegalStateException("audioserver down") },
        )
        beeper.play(1)
        assertEquals(7, alarm)
        main.idleFor(Duration.ofSeconds(1))
        assertEquals(listOf("request", "abandon"), focus.focusEvents)
        assertEquals(1, alarm)
        assertFalse(beeper.cueSounding.value)
        beeper.release()
    }

    private lateinit var startSecondCue: () -> Unit
    private lateinit var secondCueDone: CountDownLatch
    private lateinit var workerThread: Thread

    /** A beeper on its own executor thread that played one cue at t=0, at
     *  t=129. [secondCueDone] opens once [startSecondCue]'s cue has played. */
    private fun beeperOnAWorker(walkAwayOverrideActive: () -> Boolean = { false }): AlertBeeper {
        val exec = Executors.newSingleThreadExecutor().also { worker = it }
        workerThread = exec.submit<Thread> { Thread.currentThread() }.get()
        secondCueDone = CountDownLatch(2)
        val beeper = AlertBeeper(
            audioManager = audio,
            executor = exec,
            playTrackOverride = { true },
            onCue = { secondCueDone.countDown() },
            walkAwayOverrideActive = walkAwayOverrideActive,
        )
        beeper.play(1)
        exec.submit {}.get()
        main.idleFor(Duration.ofMillis(129))
        startSecondCue = { beeper.play(1) }
        return beeper
    }

    /** Called on the main thread from inside the timer: hand the worker a
     *  cue, then return once it has finished or is waiting on a lock this
     *  thread holds. */
    private fun startSecondCueAndWait() {
        startSecondCue()
        if (!waitUntil { secondCueDone.count == 0L || waitsOnLockOf(workerThread, owner = Thread.currentThread()) }) {
            hookFailures += "the second cue to finish or wait on the timer's lock"
        }
    }

    /** True once [done] holds, false if it still does not after 5 s. */
    private fun waitUntil(done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(1)
        }
        return true
    }

    private fun waitsOnLockOf(waiter: Thread, owner: Thread): Boolean {
        val info = ManagementFactory.getThreadMXBean().getThreadInfo(waiter.threadId()) ?: return false
        return info.threadState == Thread.State.BLOCKED && info.lockOwnerId == owner.threadId()
    }
}
