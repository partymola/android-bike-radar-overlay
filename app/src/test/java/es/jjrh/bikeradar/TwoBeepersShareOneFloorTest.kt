// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.Executor

/**
 * The ride's beeper and the sound demo's lift the same alarm stream and keep
 * the rider's level in one shared slot. Whichever lifts second must take the
 * rider's level from that slot, not the first one's raised stream, or the two
 * restores leave the alarm stuck at the first lift.
 */
@RunWith(RobolectricTestRunner::class)
class TwoBeepersShareOneFloorTest {

    private lateinit var audio: AudioManager
    private val direct = Executor { it.run() }
    private var slot: Int? = null

    private val alarm get() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

    @Before fun setup() {
        audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun ride() = AlertBeeper(
        audioManager = audio,
        executor = direct,
        playTrackOverride = { true },
        saveAlarmFloor = { slot = it },
        loadAlarmFloor = { slot },
    )

    private fun demo() = AlertBeeper(
        audioManager = audio,
        executor = direct,
        playTrackOverride = { true },
        saveAlarmFloor = { slot = it },
        loadAlarmFloor = { null },
        sharedFloorBaseline = { slot },
    )

    private fun media(level: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)

    private fun bothRestores() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private fun liftTwiceWithMediaRaisedBetween(first: AlertBeeper, second: AlertBeeper) {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        media(8)
        first.play(1)
        assertEquals("the first cue lifts the alarm", 6, alarm)
        media(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        second.play(1)
        assertEquals("the second cue lifts it further", 7, alarm)
        bothRestores()
        assertEquals("both restores land on the rider's own level", 1, alarm)
        assertNull(slot)
    }

    @Test
    fun aDemoLiftDuringARideLiftRestoresTheRidersLevel() {
        val ride = ride()
        val demo = demo()
        liftTwiceWithMediaRaisedBetween(first = ride, second = demo)
        ride.release()
        demo.release()
    }

    @Test
    fun aRideLiftDuringADemoLiftRestoresTheRidersLevel() {
        val ride = ride()
        val demo = demo()
        liftTwiceWithMediaRaisedBetween(first = demo, second = ride)
        ride.release()
        demo.release()
    }
}
