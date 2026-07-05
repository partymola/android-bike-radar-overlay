// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.os.VibratorManager
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
import java.time.Duration
import java.util.concurrent.Executor

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
class WalkAwayAlarmBeeperInterlockTest {

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
    }

    /** Real beeper + real walk-away alarm wired to each other exactly as
     *  BikeRadarService wires them. */
    private fun buildPair(): Pair<AlertBeeper, WalkAwayAlarm> {
        val beeper = AlertBeeper(
            audioManager = audioManager,
            executor = directExecutor,
            playTrackOverride = { true },
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
}
