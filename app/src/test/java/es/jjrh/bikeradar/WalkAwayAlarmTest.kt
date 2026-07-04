// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibratorManager
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowVibrator

/**
 * Pins the [WalkAwayAlarm] orchestration: the audio-focus request shape, the
 * "force the alarm stream to max then restore it" safety contract (a low
 * bedside-alarm slider must not swallow a forgotten-dashcam alert, and a
 * partial failure must never leave the rider's morning alarm stuck at max),
 * the self-capping job, and the vibrate fan-out.
 *
 * The looping sound is injected as a fake [AlarmTone] so play-failure,
 * setup-failure, and the already-playing short-circuit are all drivable -
 * Robolectric ships no Ringtone shadow, so a real tone never reports
 * `isPlaying` and can't be made to throw. The real ringtone playback is
 * exercised on-bike.
 */
@RunWith(RobolectricTestRunner::class)
class WalkAwayAlarmTest {

    private lateinit var context: android.content.Context
    private lateinit var audioManager: AudioManager
    private lateinit var shadowAm: ShadowAudioManager
    private lateinit var shadowVibrator: ShadowVibrator

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))

    private val maxAlarm get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    /** The raw AudioFocusRequest start() last asked for. */
    private fun requestedFocus() = shadowAm.lastAudioFocusRequest!!.audioFocusRequest

    /** The raw AudioFocusRequest stop()/rollback last abandoned, or null if none. */
    private fun abandonedFocus() = shadowAm.lastAbandonedAudioFocusRequest

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        audioManager = context.getSystemService(AudioManager::class.java)
        shadowAm = shadowOf(audioManager)
        // A vibrator that reports present so the vibrate fan-out runs its
        // effect path; the no-vibrator case flips this in its own test.
        val vm = context.getSystemService(VibratorManager::class.java)
        shadowVibrator = shadowOf(vm.defaultVibrator)
        shadowVibrator.setHasVibrator(true)
        // Start below max so "force to max" and "restore" are both observable.
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, LOW_ALARM_VOLUME, 0)
    }

    @After fun tearDown() {
        scope.cancel()
    }

    private fun alarm(toneFactory: (AudioAttributes) -> AlarmTone): WalkAwayAlarm = WalkAwayAlarm(context, scope, toneFactory)

    /** Test double for the looping alarm sound. */
    private class FakeTone(private val failOnPlay: Boolean = false) : AlarmTone {
        var playing = false
            private set
        var stops = 0
            private set

        override val isPlaying: Boolean get() = playing

        override fun play() {
            if (failOnPlay) throw RuntimeException("play boom")
            playing = true
        }

        override fun stop() {
            playing = false
            stops++
        }

        /** Simulate the tone dying WITHOUT stop() running - the audioserver
         *  restarting mid-alarm kills the Ringtone exactly like this. */
        fun die() {
            playing = false
        }
    }

    @Test
    fun start_requestsExclusiveAlarmFocus_forcesMaxVolume_andPlays() {
        val fake = FakeTone()
        alarm { fake }.start()

        val req = shadowAm.lastAudioFocusRequest
        assertNotNull("start() must request audio focus", req)
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            req!!.audioFocusRequest.focusGain,
        )
        assertEquals("alarm stream must be forced to max", maxAlarm, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        assertTrue("the tone must be playing", fake.isPlaying)
        assertTrue("the alarm must also vibrate (DND-bypassing cue)", shadowVibrator.isVibrating)
    }

    @Test
    fun stop_restoresSavedVolume_andStopsTone() {
        val fake = FakeTone()
        val a = alarm { fake }
        a.start()
        assertEquals(maxAlarm, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))

        a.stop()
        assertEquals(
            "the pre-alert alarm volume must be restored",
            LOW_ALARM_VOLUME,
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        )
        assertFalse(fake.isPlaying)
        assertEquals(1, fake.stops)
        assertSame("stop() must abandon the focus it held", requestedFocus(), abandonedFocus())
    }

    @Test
    fun stop_whenNothingPlaying_isSafeNoOp() {
        // The dismiss / auto-dismiss / onDestroy handlers all call stop()
        // unconditionally; with no alarm active it must not touch the alarm
        // slider, abandon a focus it never held, or throw.
        val a = alarm { FakeTone() }
        a.stop()

        assertEquals(LOW_ALARM_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull("stop() with no active alarm must not abandon any focus", abandonedFocus())
    }

    @Test
    fun start_whenAlreadyPlaying_doesNotRebuildOrReRequestFocus() {
        var built = 0
        val a = alarm {
            built++
            FakeTone()
        }
        a.start()
        val firstRequest = shadowAm.lastAudioFocusRequest
        a.start()

        assertEquals("second start() must short-circuit, not build a second tone", 1, built)
        assertSame(
            "an already-playing alarm must not re-request focus",
            firstRequest,
            shadowAm.lastAudioFocusRequest,
        )
    }

    @Test
    fun start_playFailure_rollsBackVolume_abandonsFocus_andDoesNotCommit() {
        var built = 0
        val a = alarm {
            built++
            FakeTone(failOnPlay = true)
        }
        a.start()

        // The forced max-out is rolled back so a play() failure can't leave the
        // rider's morning alarm pinned at max.
        assertEquals(
            "a play() failure must restore the saved alarm volume",
            LOW_ALARM_VOLUME,
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        )
        // ...and the exclusive focus grant must be released, or every failed
        // alert would mute the rider's music / nav until the next success.
        assertSame("a play() failure must abandon the focus it requested", requestedFocus(), abandonedFocus())
        // Nothing was committed, so the next FIRE rebuilds rather than
        // short-circuiting on a half-initialised tone.
        a.start()
        assertEquals("a failed start must not commit a tone", 2, built)
        assertEquals(LOW_ALARM_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun start_toneSetupFailure_leavesVolumeUntouched_andAbandonsFocus() {
        val a = alarm { throw RuntimeException("setup boom") }
        a.start()

        // Setup throws before the volume is forced, so the alarm slider is
        // untouched, and the focus request that was issued must be abandoned.
        assertEquals(LOW_ALARM_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        assertSame("a setup failure must abandon the focus it requested", requestedFocus(), abandonedFocus())
    }

    @Test
    fun start_focusDenied_stillPlaysBestEffort() {
        shadowAm.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val fake = FakeTone()
        alarm { fake }.start()

        assertNotNull("a focus request was still made", shadowAm.lastAudioFocusRequest)
        assertTrue("a denied focus request must not stop the alert", fake.isPlaying)
    }

    @Test
    fun capJob_autoStopsToneAfterTimeout() {
        val fake = FakeTone()
        alarm { fake }.start()
        assertTrue(fake.isPlaying)

        scheduler.advanceUntilIdle()

        assertFalse("the self-capping job must stop a forgotten alert", fake.isPlaying)
        assertEquals(1, fake.stops)
        assertEquals(LOW_ALARM_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun start_persistsSavedVolume_stopClearsIt() {
        // The saved level must hit disk while the override is live: a process
        // death between force-max and restore is the one window stop() cannot
        // cover, and the persisted slot is what the next instance repairs from.
        val prefs = Prefs(context)
        val a = alarm { FakeTone() }
        a.start()
        assertEquals(
            "the pre-force level must be persisted while the override is live",
            LOW_ALARM_VOLUME,
            prefs.walkAwaySavedAlarmVolume,
        )

        a.stop()
        assertNull(
            "a clean stop must clear the persisted slot - nothing left to repair",
            prefs.walkAwaySavedAlarmVolume,
        )
    }

    @Test
    fun start_playFailure_clearsPersistedSavedVolume() {
        val prefs = Prefs(context)
        alarm { FakeTone(failOnPlay = true) }.start()
        assertNull(
            "the play-failure rollback restores the volume, so the slot must clear too",
            prefs.walkAwaySavedAlarmVolume,
        )
    }

    @Test
    fun capJob_clearsPersistedSavedVolume() {
        val prefs = Prefs(context)
        alarm { FakeTone() }.start()
        scheduler.advanceUntilIdle()
        assertNull(
            "the self-cap stops via stop(), which must clear the persisted slot",
            prefs.walkAwaySavedAlarmVolume,
        )
    }

    @Test
    fun construction_restoresVolumeLeakedByProcessDeath() {
        // Simulate a death mid-alarm: the persisted slot holds the rider's
        // level, the stream is still pinned at max, and no stop() ever ran.
        val prefs = Prefs(context)
        prefs.walkAwaySavedAlarmVolume = LOW_ALARM_VOLUME
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)

        alarm { FakeTone() }

        assertEquals(
            "a new instance must repair the leaked max-volume override",
            LOW_ALARM_VOLUME,
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        )
        assertNull("the repaired slot must be cleared", prefs.walkAwaySavedAlarmVolume)
    }

    @Test
    fun construction_withNoLeakedOverride_leavesVolumeUntouched() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, LOW_ALARM_VOLUME, 0)
        alarm { FakeTone() }
        assertEquals(
            "no persisted slot means no repair - the rider's level stays put",
            LOW_ALARM_VOLUME,
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        )
    }

    @Test
    fun restartAfterStaleTone_keepsTheOriginalSavedVolume() {
        // The tone died without stop() (audioserver restart mid-alarm), so
        // the stream is still forced to max when the decider re-fires. The
        // restart must NOT capture "current" - that would save the forced
        // maximum as the rider's level and restore max forever.
        val prefs = Prefs(context)
        var current = FakeTone()
        val a = alarm { current }
        a.start()
        assertEquals(maxAlarm, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))

        current.die()
        current = FakeTone()
        a.start()

        assertEquals(
            "the pre-alert level must survive a stale-tone restart",
            LOW_ALARM_VOLUME,
            prefs.walkAwaySavedAlarmVolume,
        )
        a.stop()
        assertEquals(
            "stop() must restore the rider's true level, not the forced max",
            LOW_ALARM_VOLUME,
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        )
    }

    @Test
    fun construction_coercesCorruptLeakedValueIntoStreamRange() {
        // A corrupt or cross-device value must not crash construction or set
        // an out-of-range volume; it clamps into the stream's valid range.
        val prefs = Prefs(context)
        prefs.walkAwaySavedAlarmVolume = 999
        alarm { FakeTone() }
        assertEquals(maxAlarm, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(prefs.walkAwaySavedAlarmVolume)
    }

    @Test
    fun start_withoutVibrator_skipsVibration_butStillPlays() {
        shadowVibrator.setHasVibrator(false)
        val fake = FakeTone()
        alarm { fake }.start()

        assertTrue("a missing vibrator must not block the audio alert", fake.isPlaying)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun start_belowTiramisu_vibratesViaAudioAttributes_andPlays() {
        val fake = FakeTone()
        alarm { fake }.start()

        assertTrue(fake.isPlaying)
    }

    private companion object {
        const val LOW_ALARM_VOLUME = 2
    }
}
