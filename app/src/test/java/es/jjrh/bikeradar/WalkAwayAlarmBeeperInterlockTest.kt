// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.os.VibratorManager
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ui.newDemoBeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pins the STREAM_ALARM interlock between [AlertBeeper]'s media-volume floor
 * and [WalkAwayAlarm]'s force-max override - two independent overrides of the
 * same stream that CAN interleave.
 *
 * The dangerous ordering: (1) a cue burst lifts the stream from the rider's
 * level O to a floor F, (2) the walk-away alarm starts mid-burst, (3) the
 * burst's restore timer fires, (4) the walk-away stops. Without the interlock,
 * step 2 captured F as "the rider's original", step 3 yanked the blaring alarm
 * down to O mid-episode, and step 4 stranded the slider at F forever - with
 * both crash-repair slots clean, so nothing ever caught it. With it, the
 * walk-away captures the beeper's true baseline ([AlertBeeper.alarmFloorBaseline])
 * and the beeper hands its restore off while the walk-away override is active.
 *
 * The reverse ordering (walk-away first, cue during the episode) must stay
 * safe: the floor keeps entirely off the stream while the override holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [WalkAwayAlarmBeeperInterlockTest.AlarmWriteHookShadowAudioManager::class])
class WalkAwayAlarmBeeperInterlockTest {

    /** Can run a hook just before or just after the next alarm-stream write
     *  takes effect. */
    @Implements(AudioManager::class)
    class AlarmWriteHookShadowAudioManager : ShadowAudioManager() {
        @Volatile var beforeNextAlarmWrite: (() -> Unit)? = null

        @Volatile var afterNextAlarmWrite: (() -> Unit)? = null

        @Implementation
        override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
            if (streamType == AudioManager.STREAM_ALARM) {
                beforeNextAlarmWrite?.let {
                    beforeNextAlarmWrite = null
                    it()
                }
            }
            super.setStreamVolume(streamType, index, flags)
            if (streamType == AudioManager.STREAM_ALARM) {
                afterNextAlarmWrite?.let {
                    afterNextAlarmWrite = null
                    it()
                }
            }
        }
    }

    private lateinit var audioManager: AudioManager

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val directExecutor: Executor = Executor { it.run() }

    private var persistedFloor: Int? = null

    private val maxAlarm get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    private val alarmVol get() = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

    /** Wired after construction so the beeper's lambda can see the alarm. */
    private var walkAway: WalkAwayAlarm? = null

    private class FakeTone : AlarmTone {
        var playing = false
            private set

        override val isPlaying: Boolean get() = playing

        override fun play() {
            playing = true
        }

        override fun stop() {
            playing = false
        }
    }

    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        audioManager = ctx.getSystemService(AudioManager::class.java)
        audioManager.setMode(AudioManager.MODE_NORMAL)
        val vm = ctx.getSystemService(VibratorManager::class.java)
        shadowOf(vm.defaultVibrator).setHasVibrator(true)
    }

    @After fun tearDown() {
        scope.cancel()
        worker?.shutdownNow()
    }

    private var worker: ExecutorService? = null

    /** Waits a hook gave up on. A hook runs inside the code under test, on
     *  either thread, often within its catch-all around a volume write, so it
     *  records a miss here and the test thread asserts the list is empty. */
    private val hookFailures: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Real beeper + real walk-away alarm wired to each other exactly as
     *  BikeRadarService wires them. */
    private fun buildPair(
        executor: Executor = directExecutor,
        playTrack: (android.media.AudioTrack) -> Boolean = { true },
    ): Pair<AlertBeeper, WalkAwayAlarm> {
        val beeper = AlertBeeper(
            audioManager = audioManager,
            executor = executor,
            playTrackOverride = playTrack,
            saveAlarmFloor = { persistedFloor = it },
            loadAlarmFloor = { null },
            walkAwayOverrideActive = { walkAway?.overrideActive == true },
        )
        val toneFactory: (AudioAttributes) -> AlarmTone = { FakeTone() }
        val alarm = WalkAwayAlarm(
            RuntimeEnvironment.getApplication(),
            scope,
            toneFactory,
            beeperAlarmBaseline = { beeper.alarmFloorBaseline() },
        )
        walkAway = alarm
        return beeper to alarm
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    @Test
    fun floorLift_thenWalkAwayStart_thenBothRestores_landsOnTheRidersLevel() {
        // Ordering A. O = 1 (rider's quiet preset), loud media so play() lifts.
        val riderLevel = 1
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0,
        )
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, riderLevel, 0)
        val (beeper, alarm) = buildPair()

        // 1. Cue burst lifts the floor: stream at F > O, baseline O saved.
        beeper.play(2)
        assertTrue("the floor must have lifted the stream", alarmVol > riderLevel)

        // 2. Walk-away starts mid-burst: must capture O (the beeper's saved
        //    baseline), never the lifted F, and force the stream to max.
        alarm.start()
        assertEquals("walk-away must force max", maxAlarm, alarmVol)

        // 3. The burst's restore timer fires while the walk-away blares: the
        //    beeper must hand off - the stream stays at max, not yanked to O.
        idleMainLooper()
        assertEquals(
            "the beeper's restore must not pull the blaring walk-away down",
            maxAlarm,
            alarmVol,
        )
        assertNull("the beeper's crash-repair slot must be cleared by the hand-off", persistedFloor)

        // 4. Walk-away stops: the rider's ORIGINAL level comes back - not the
        //    lifted floor the stream happened to sit at when start() ran.
        alarm.stop()
        assertEquals(
            "the rider's own alarm level must survive both restores",
            riderLevel,
            alarmVol,
        )
    }

    @Test
    fun walkAwayFirst_cueDuringEpisode_staysSafe() {
        // Reverse ordering: the walk-away override holds the stream; a cue
        // during the episode must leave the floor entirely off the stream.
        val riderLevel = 1
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0,
        )
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, riderLevel, 0)
        val (beeper, alarm) = buildPair()

        alarm.start()
        assertEquals(maxAlarm, alarmVol)

        beeper.play(2)
        assertEquals("the floor must not touch the stream mid-episode", maxAlarm, alarmVol)
        assertNull("the floor must not save a baseline mid-episode", persistedFloor)

        idleMainLooper() // burst timer: nothing to restore
        assertEquals(maxAlarm, alarmVol)

        alarm.stop()
        assertEquals("the rider's level comes back after the episode", riderLevel, alarmVol)
    }

    @Test
    fun aWalkAwayStartingDuringACuesLiftStaysAtMax() {
        // Media at 8 of 15 lifts the rider's alarm 1 to 6, one short of max.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val exec = Executors.newSingleThreadExecutor().also { worker = it }
        val workerThread = exec.submit<Thread> { Thread.currentThread() }.get()
        val (beeper, alarm) = buildPair(executor = exec)
        val started = CountDownLatch(1)
        // On the worker, after the cue has read the rider's level and before
        // it writes its lift: the walk-away starts on another thread.
        hookShadow().beforeNextAlarmWrite = {
            val starter = Thread {
                alarm.start()
                started.countDown()
            }.also { it.start() }
            if (!waitUntil { started.count == 0L || waitsOnLockOf(starter, owner = workerThread) }) {
                hookFailures += "the walk-away start to finish or wait on the cue's lift"
            }
        }
        beeper.play(1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        exec.submit {}.get()
        assertEquals(emptyList<String>(), hookFailures)
        assertEquals("the walk-away sounds at max", 7, alarmVol)
        idleMainLooper()
        assertEquals("the cue's restore leaves it at max", 7, alarmVol)
        alarm.stop()
        assertEquals(1, alarmVol)
        assertNull(persistedFloor)
    }

    @Test
    fun aWalkAwayStartingDuringACuesRestoreStaysAtMax() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val (beeper, alarm) = buildPair()
        beeper.play(1)
        assertEquals(6, alarmVol)
        val started = CountDownLatch(1)
        val mainThread = Thread.currentThread()
        // On the main thread, in the cue's timer, after it has found no
        // walk-away override and before it writes the rider's level back.
        hookShadow().beforeNextAlarmWrite = {
            val starter = Thread {
                alarm.start()
                started.countDown()
            }.also { it.start() }
            if (!waitUntil { started.count == 0L || waitsOnLockOf(starter, owner = mainThread) }) {
                hookFailures += "the walk-away start to finish or wait on the cue's restore"
            }
        }
        idleMainLooper()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), hookFailures)
        assertEquals("the walk-away sounds at max", 7, alarmVol)
        alarm.stop()
        assertEquals(1, alarmVol)
        assertNull(persistedFloor)
    }

    @Test
    fun aCueWhileTheWalkAwaysToneStartsLeavesItAtMax() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val (beeper, _) = buildPair()
        beeper.play(1)
        // The tone starts outside the beepers' lock, so a cue can lift then.
        val tone = object : AlarmTone {
            var playing = false
            override val isPlaying: Boolean get() = playing

            override fun play() {
                beeper.play(1)
                playing = true
            }

            override fun stop() {
                playing = false
            }
        }
        val alarm = WalkAwayAlarm(
            RuntimeEnvironment.getApplication(),
            scope,
            { tone },
            beeperAlarmBaseline = { beeper.alarmFloorBaseline() },
        )
        walkAway = alarm
        alarm.start()
        assertEquals("the walk-away sounds at max", 7, alarmVol)
        idleMainLooper()
        assertEquals(7, alarmVol)
        alarm.stop()
        assertEquals(1, alarmVol)
    }

    @Test
    fun aCueAsTheWalkAwayStopsIsLiftedOnceItsOverrideEnds() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val exec = Executors.newSingleThreadExecutor().also { worker = it }
        val workerThread = exec.submit<Thread> { Thread.currentThread() }.get()
        val playedAt = mutableListOf<Int>()
        val (beeper, alarm) = buildPair(executor = exec, playTrack = {
            playedAt += alarmVol
            true
        })
        alarm.start()
        assertEquals(7, alarmVol)
        val cueDone = CountDownLatch(1)
        val stopThread = Thread.currentThread()
        // On this thread, in stop(), after it has written the rider's level
        // back and before it clears its override: a cue starts on the worker.
        hookShadow().afterNextAlarmWrite = {
            beeper.play(1)
            exec.execute { cueDone.countDown() }
            if (!waitUntil { cueDone.count == 0L || waitsOnLockOf(workerThread, owner = stopThread) }) {
                hookFailures += "the cue to finish or wait on stop()'s restore"
            }
        }
        alarm.stop()
        assertTrue(cueDone.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), hookFailures)
        assertEquals("the cue sounds lifted, never at the rider's bare level", listOf(6), playedAt)
        idleMainLooper()
        assertEquals(1, alarmVol)
        assertNull(persistedFloor)
    }

    @Test
    fun aCueAsAFailedWalkAwayRollsBackIsLiftedOnceItsOverrideEnds() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val exec = Executors.newSingleThreadExecutor().also { worker = it }
        val workerThread = exec.submit<Thread> { Thread.currentThread() }.get()
        val playedAt = mutableListOf<Int>()
        val (beeper, _) = buildPair(executor = exec, playTrack = {
            playedAt += alarmVol
            true
        })
        val cueDone = CountDownLatch(1)
        val startThread = Thread.currentThread()
        // The tone fails to start, so start() writes the rider's level back
        // and then clears its override: a cue starts on the worker in between.
        val failing = object : AlarmTone {
            override val isPlaying: Boolean get() = false

            override fun play() {
                hookShadow().afterNextAlarmWrite = {
                    beeper.play(1)
                    exec.execute { cueDone.countDown() }
                    if (!waitUntil { cueDone.count == 0L || waitsOnLockOf(workerThread, owner = startThread) }) {
                        hookFailures += "the cue to finish or wait on the rollback's restore"
                    }
                }
                throw IllegalStateException("ringtone unavailable")
            }

            override fun stop() {}
        }
        val alarm = WalkAwayAlarm(
            RuntimeEnvironment.getApplication(),
            scope,
            { failing },
            beeperAlarmBaseline = { beeper.alarmFloorBaseline() },
        )
        walkAway = alarm
        alarm.start()
        assertTrue(cueDone.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), hookFailures)
        assertEquals("the cue sounds lifted, never at the rider's bare level", listOf(6), playedAt)
        idleMainLooper()
        assertEquals(1, alarmVol)
        assertNull(persistedFloor)
    }

    private fun hookShadow(): AlarmWriteHookShadowAudioManager = Shadow.extract(audioManager)

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

    @Test
    fun aSoundDemoLiftIsInterlockedWithTheWalkAwayAlarmToo() {
        // The demo's beeper is not the one the service wires in, so the walk-away
        // sees its lift only through the crash-repair slot the two share.
        val app = RuntimeEnvironment.getApplication()
        val prefs = Prefs(app)
        try {
            val riderLevel = 1
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, riderLevel, 0)
            val demo = newDemoBeeper(app, prefs, directExecutor)!!
            val alarm = WalkAwayAlarm(app, scope, { FakeTone() }, prefs, beeperAlarmBaseline = { null })

            demo.play(2)
            assertTrue("the demo must have lifted the stream", alarmVol > riderLevel)
            alarm.start()
            idleMainLooper()
            assertEquals("the demo's restore must not pull the blaring walk-away down", maxAlarm, alarmVol)
            assertNull("the shared slot must be cleared by the hand-off", prefs.alertBeeperSavedAlarmVolume)
            alarm.stop()
            assertEquals("the rider's own alarm level must survive both restores", riderLevel, alarmVol)
            demo.release()
        } finally {
            app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
