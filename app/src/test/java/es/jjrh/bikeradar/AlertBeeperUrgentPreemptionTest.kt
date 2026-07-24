// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executor

/**
 * The urgent cue pre-empts the awareness beeps in the audio layer, not just in
 * the decider.
 *
 * Each cue owns its own [AudioTrack] and the play path stops only the track it
 * is about to start, so two cues overlapping in time would mix. Since the
 * urgent no longer waits out the awareness cooldown it can land inside a beep's
 * pattern, and the action channel has to arrive clean rather than smeared into
 * the channel it outranks.
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperUrgentPreemptionTest {

    private lateinit var audioManager: AudioManager
    private val directExecutor: Executor = Executor { it.run() }
    private val played = mutableListOf<AudioTrack>()
    private val stopped = mutableListOf<AudioTrack>()

    /** Stops and plays in one sequence, so ORDER between them is assertable.
     *  Membership alone would pass even if the silencing ran after the play,
     *  which is the one thing these tests exist to rule out. */
    private val calls = mutableListOf<String>()

    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        audioManager = ctx.getSystemService(AudioManager::class.java)
        audioManager.setMode(AudioManager.MODE_NORMAL)
    }

    private fun beeper(): AlertBeeper = AlertBeeper(
        audioManager = audioManager,
        executor = directExecutor,
        clock = { 0L },
        onCue = {},
        playTrackOverride = {
            played.add(it)
            calls.add("play")
            true
        },
        stopTrackOverride = {
            stopped.add(it)
            calls.add("stop")
        },
    )

    @Test fun `the urgent cue silences the beep tracks before it sounds`() {
        val b = beeper()
        b.play(2)
        val beepTrack = played.single()
        stopped.clear()
        calls.clear()

        b.playUrgent()

        assertTrue(
            "the beep track that was sounding must be stopped, got $stopped",
            stopped.contains(beepTrack),
        )
        assertTrue("the urgent cue must still sound", played.size == 2)
        // The name of this test is a claim about ORDER. Assert it: every stop
        // must land before the urgent play, or the cue is mixed over a beep
        // that is still sounding rather than replacing it.
        assertEquals("stops must precede the urgent play, got $calls", "stop", calls.first())
        assertEquals("and the urgent play must be last, got $calls", "play", calls.last())
        assertEquals("exactly one cue should have played, got $calls", 1, calls.count { it == "play" })
    }

    @Test fun `after release the urgent cue touches no track`() {
        // Teardown and cue playback share one executor, so a cue submitted
        // around release() can run after it. The tracks are released but not
        // nulled, so without the guard this would call stop() on freed native
        // objects. Sibling of AlertBeeperResilienceTest's release-then-play.
        val b = beeper()
        b.release()
        stopped.clear()
        played.clear()
        calls.clear()

        b.playUrgent()

        assertEquals("released beeper must stop nothing", emptyList<AudioTrack>(), stopped)
        assertEquals("and play nothing", emptyList<AudioTrack>(), played)
    }

    @Test fun `an awareness beep does not silence anything`() {
        // Pre-emption is one-directional: the action channel interrupts the
        // awareness channel, never the reverse.
        val b = beeper()
        b.play(2)
        assertEquals(emptyList<AudioTrack>(), stopped)
    }
}
