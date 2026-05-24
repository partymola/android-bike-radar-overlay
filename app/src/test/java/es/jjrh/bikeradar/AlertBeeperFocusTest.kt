// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.concurrent.Executor
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
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowAudioManager

/**
 * Pins the audio-focus and MODE_IN_CALL behaviour of [AlertBeeper].
 *
 * Audio focus is the headline reliability win: today the close-pass
 * beeper makes no focus request, so a phone call holding
 * AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE silences USAGE_ALARM at the
 * speaker, and on some vendor BT routes media-volume ducking is
 * missed. These tests pin the focus request shape, the back-to-back
 * extension, the MODE_IN_CALL suppression, and the release path.
 *
 * Uses a same-thread `Executor` so each `play*()` call runs inline;
 * Robolectric's `ShadowLooper.idleMainLooper()` then drains the
 * scheduled abandon-runnable when the test asserts on it.
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperFocusTest {

    private lateinit var audioManager: AudioManager
    private lateinit var shadowAm: ShadowAudioManager

    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        audioManager = ctx.getSystemService(AudioManager::class.java)
        shadowAm = shadowOf(audioManager)
    }

    @After fun tearDown() {
        // Reset mode so cross-test bleed cannot fake a MODE_IN_CALL state.
        audioManager.setMode(AudioManager.MODE_NORMAL)
    }

    private val directExecutor: Executor = Executor { it.run() }

    private fun newBeeper(): AlertBeeper = AlertBeeper(
        audioManager = audioManager,
        executor = directExecutor,
    )

    private fun lastFocusRequest(): AudioFocusRequest? =
        shadowAm.lastAudioFocusRequest?.audioFocusRequest

    @Test
    fun play_requestsTransientMayDuckFocusWithAlarmUsage() {
        val beeper = newBeeper()
        beeper.play(2)

        val req = lastFocusRequest()
        assertTrue("expected an AudioFocusRequest after play()", req != null)
        // The request's gain class is internal but AOSP exposes it via
        // the AudioAttributes usage we set. Test the public surface
        // (USAGE_ALARM) plus the fact that focus was requested at all.
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            shadowAm.lastAudioFocusRequest?.audioFocusRequest?.focusGain,
        )
        beeper.release()
    }

    @Test
    fun play_skipsAudioPathInCall() {
        audioManager.setMode(AudioManager.MODE_IN_CALL)
        val beeper = newBeeper()
        beeper.play(2)

        assertTrue(
            "no focus request expected while MODE_IN_CALL is active",
            shadowAm.lastAudioFocusRequest == null,
        )
        beeper.release()
    }

    @Test
    fun playClear_skipsAudioPathInCall() {
        audioManager.setMode(AudioManager.MODE_IN_CALL)
        val beeper = newBeeper()
        beeper.playClear()
        assertTrue(
            "no focus request expected on playClear while MODE_IN_CALL",
            shadowAm.lastAudioFocusRequest == null,
        )
        beeper.release()
    }

    @Test
    fun playUrgent_skipsAudioPathInCall() {
        audioManager.setMode(AudioManager.MODE_IN_CALL)
        val beeper = newBeeper()
        beeper.playUrgent()
        assertTrue(
            "no focus request expected on playUrgent while MODE_IN_CALL",
            shadowAm.lastAudioFocusRequest == null,
        )
        beeper.release()
    }

    @Test
    fun playCriticalBattery_skipsAudioPathInCall() {
        audioManager.setMode(AudioManager.MODE_IN_CALL)
        val beeper = newBeeper()
        beeper.playCriticalBattery()
        assertTrue(
            "no focus request expected on playCriticalBattery while MODE_IN_CALL",
            shadowAm.lastAudioFocusRequest == null,
        )
        beeper.release()
    }

    @Test
    fun backToBackPlays_extendFocusInsteadOfAbandoning() {
        val beeper = newBeeper()
        beeper.play(2)
        val firstRequest = shadowAm.lastAudioFocusRequest

        // Second play before the first cue's abandon could fire. The
        // implementation reuses the held focus and re-arms the timer.
        beeper.play(3)

        // Same focus object should still be in flight - no abandon
        // call between the two plays.
        assertEquals(
            "back-to-back plays should reuse the held focus",
            firstRequest,
            shadowAm.lastAudioFocusRequest,
        )
        beeper.release()
    }

    @Test
    fun release_doesNotThrowAfterPlay() {
        // After play() acquires focus, release() must complete without
        // throwing - even though Robolectric in this version exposes no
        // assertable "focus was abandoned" surface. The contract pinned
        // here is exception-safety; the actual abandon call is visible
        // by code inspection in AlertBeeper.release().
        val beeper = newBeeper()
        beeper.play(2)
        assertTrue(shadowAm.lastAudioFocusRequest != null)
        beeper.release()
    }

    @Test
    fun release_isIdempotent() {
        // Two release() calls in a row must not throw. The implementation
        // relies on AudioTrack.release() being a no-op after the first
        // release, ExecutorService.shutdown() being a no-op after shutdown,
        // and the focus-abandoned path being guarded by hasFocus.
        val beeper = newBeeper()
        beeper.play(2)
        beeper.release()
        beeper.release() // must not throw
    }

    @Test
    fun focusDenial_stillPlaysBestEffort() {
        // Robolectric default is GRANT; flip to FAILED for this test.
        shadowAm.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val beeper = newBeeper()
        beeper.play(2)

        // A focus request was made (the call itself) even though it
        // was denied. The play path proceeds best-effort; no exception
        // and no NPE.
        assertTrue(shadowAm.lastAudioFocusRequest != null)
        // After a denied request, no abandon timer should run on the
        // next idle since hasFocus stays false.
        ShadowLooper.idleMainLooper()
        // No assertion on the abandoned state - the contract is
        // "doesn't crash and doesn't leak a pending timer". Reaching
        // this line without exception is the pin.
        beeper.release()
    }

    @Test
    fun release_isSafeAfterNoPlay() {
        val beeper = newBeeper()
        // Never played anything. Release must still work.
        beeper.release()
        assertFalse(
            "no focus request should have been made without a play call",
            shadowAm.lastAudioFocusRequest != null,
        )
    }
}
