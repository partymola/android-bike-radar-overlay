// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioManager
import android.os.Looper
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
 * Pins the media-volume floor: before a cue sounds, [AlertBeeper] lifts the
 * system alarm stream so a safety alert stays audible above the rider's chosen
 * media level, then restores it when the burst ends - never turning the rider's
 * alarm DOWN, never touching it when no media plays, and never firing while a
 * call suppresses the audio path. A process death inside the lift window is
 * repaired from a persisted slot at the next start.
 *
 * The pure index math is pinned directly; the lift/restore lifecycle is driven
 * through the injectable executor + persistence seams and the real
 * ShadowAudioManager stream volumes (as WalkAwayAlarmTest does for its own
 * alarm-stream override).
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperMediaFloorTest {

    private lateinit var audioManager: AudioManager
    private val directExecutor: Executor = Executor { it.run() }

    /** Captures the persisted crash-repair slot: the value AlertBeeper asks the
     *  service to store (baseline on lift, null on restore). */
    private var persisted: Int? = null
    private var persistWrites = 0

    private val maxAlarm get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    private val maxMusic get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val alarmVol get() = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        audioManager = ctx.getSystemService(AudioManager::class.java)
        audioManager.setMode(AudioManager.MODE_NORMAL)
    }

    private fun beeper(loadFloor: () -> Int? = { null }): AlertBeeper = AlertBeeper(
        audioManager = audioManager,
        executor = directExecutor,
        playTrackOverride = { true },
        saveAlarmFloor = {
            persisted = it
            persistWrites++
        },
        loadAlarmFloor = loadFloor,
    )

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    @Test fun computeAlarmFloorIndex_liftsAboveScaledMusic_flooredAtRiderLevel_cappedAtMax() {
        val b = beeper()
        // Loud media (max) with the alarm range = music range -> pinned to max.
        assertEquals(10, b.computeAlarmFloorIndex(musicVol = 10, musicMax = 10, alarmVol = 2, alarmMax = 10))
        // Mid media: scaled-in + ~6 dB margin, still below max.
        assertEquals(5 + AlertBeeper.ALARM_MARGIN_STEPS, b.computeAlarmFloorIndex(5, 10, 0, 10))
        // Never turn the alarm DOWN: rider's own louder preset wins.
        assertEquals(9, b.computeAlarmFloorIndex(1, 10, 9, 10))
        // No media playing -> leave the alarm exactly as the rider set it.
        assertEquals(4, b.computeAlarmFloorIndex(0, 10, 4, 10))
        // Degenerate ranges -> no change.
        assertEquals(3, b.computeAlarmFloorIndex(5, 0, 3, 10))
        assertEquals(3, b.computeAlarmFloorIndex(5, 10, 3, 0))
        b.release()
    }

    @Test fun loudMedia_quietAlarm_liftsThenRestoresOnBurstEnd() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0) // loud podcast
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0) // quiet alarm preset
        val b = beeper()

        b.play(2)
        assertTrue("a loud podcast must lift the alarm above the rider's quiet preset", alarmVol > 1)
        assertEquals("the pre-lift level is persisted for crash repair", 1, persisted)

        idleMainLooper() // the abandon timer fires end-of-burst
        assertEquals("the rider's own alarm level must be restored", 1, alarmVol)
        assertNull("the crash-repair slot is cleared on restore", persisted)
        b.release()
    }

    @Test fun burst_liftsAndRestoresOnce_notPerPulse() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val b = beeper()

        b.play(2)
        b.play(3) // back-to-back, still inside the burst
        assertTrue(alarmVol > 1)
        // One save of the true baseline (1), not a re-save of the lifted level.
        assertEquals("baseline saved exactly once for the burst", 1, persistWrites)
        assertEquals(1, persisted)

        idleMainLooper()
        assertEquals(1, alarmVol)
        b.release()
    }

    @Test fun noMedia_leavesAlarmUntouched() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) // nothing playing
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val b = beeper()

        b.play(2)
        assertEquals("no media -> the alarm stays exactly as the rider set it", 2, alarmVol)
        assertEquals("no lift means nothing persisted", 0, persistWrites)
        b.release()
    }

    @Test fun inCall_suppressesTheLiftEntirely() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        audioManager.setMode(AudioManager.MODE_IN_CALL)
        val b = beeper()

        b.play(2)
        assertEquals("an in-call cue is fully suppressed, so the alarm is not lifted", 1, alarmVol)
        assertEquals(0, persistWrites)
        b.release()
    }

    @Test fun release_restoresALiftLeftHanging() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val b = beeper()

        b.play(2)
        assertTrue(alarmVol > 1)
        b.release() // destroy before the abandon timer runs
        assertEquals("release must restore the alarm even without the timer", 1, alarmVol)
        assertNull(persisted)
    }

    @Test fun construction_repairsALeakedLiftFromThePersistedSlot() {
        // Simulate a process death mid-lift: the alarm slider is stranded high
        // and the persisted slot still holds the rider's true level.
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
        val b = beeper(loadFloor = { 2 })

        assertEquals("the leaked lift must be repaired to the persisted level", 2, alarmVol)
        assertNull("the repaired slot must be cleared", persisted)
        b.release()
    }
}
