// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioManager
import java.util.concurrent.Executor

/**
 * Pins [AlertBeeper]'s failure honesty and self-healing: a cue that could
 * not sound must be reported as `cue_failed <tag>` (never as the bare tag -
 * the capture log and the ride-stats tally must not claim a silent cue was
 * heard), and a play failure must trigger a throttled track rebuild plus one
 * retry so an audioserver death heals without a service restart.
 *
 * Play outcomes are driven through the injected track-player seam and the
 * throttle through an injected clock - Robolectric cannot drive MODE_STATIC
 * AudioTracks (no shadow static-write), so the real play path is exercised
 * on-bike, like WalkAwayAlarm's real Ringtone.
 */
@RunWith(RobolectricTestRunner::class)
class AlertBeeperResilienceTest {

    private lateinit var audioManager: AudioManager
    private lateinit var shadowAm: ShadowAudioManager

    private val directExecutor: Executor = Executor { it.run() }
    private var nowMs = 0L

    /** Scripted play outcomes, consumed one per attempt; empty = succeed. */
    private val playScript = ArrayDeque<Boolean>()

    /** Attempts that must THROW rather than return false, consumed one per
     *  attempt; empty = do not throw. A dead audioserver surfaces as a throw
     *  from the media layer, not as a false, and that path has its own
     *  handling in [AlertBeeper.attemptOrFailed]. */
    private val throwScript = ArrayDeque<Boolean>()
    private var playAttempts = 0
    private val rebuildGens = mutableListOf<Int>()

    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        audioManager = ctx.getSystemService(AudioManager::class.java)
        shadowAm = shadowOf(audioManager)
        audioManager.setMode(AudioManager.MODE_NORMAL)
    }

    private fun beeper(cues: MutableList<String>): AlertBeeper = AlertBeeper(
        audioManager = audioManager,
        executor = directExecutor,
        clock = { nowMs },
        onCue = { cues.add(it) },
        playTrackOverride = {
            playAttempts++
            if (throwScript.removeFirstOrNull() == true) {
                throw IllegalStateException("audioserver down")
            }
            playScript.removeFirstOrNull() ?: true
        },
        onTracksRebuilt = { rebuildGens.add(it) },
    )

    @Test
    fun deadTracks_healViaRebuildAndRetry_cueStillSounds() {
        // The audioserver-death scenario: the first attempt fails on a dead
        // track, the failure triggers a rebuild, and the retry sounds on the
        // fresh set - the rider hears the cue and the log records a bare tag.
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        playScript.add(false) // dead track; the retry (fresh track) succeeds

        b.play(2)

        assertEquals(
            "the retry on rebuilt tracks must sound and log the bare tag",
            listOf("beep count=2"),
            cues,
        )
        assertEquals("the heal must have rebuilt the track set", 1, b.trackGeneration)
        assertEquals("exactly one failed attempt + one retry", 2, playAttempts)
        assertEquals(
            "each successful rebuild must fire the journal hook with its generation",
            listOf(1),
            rebuildGens,
        )
        b.release()
    }

    @Test
    fun persistentFailure_reportsCueFailed_andThrottlesRebuilds() {
        // Server still down: every attempt fails. The first failure spends
        // the one throttled rebuild; the second cue inside the window must
        // not rebuild again and must report cue_failed.
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        playScript.addAll(listOf(false, false, false)) // fail, rebuild, retry-fail; next cue fail
        b.play(2)
        b.play(3)

        assertEquals(
            listOf("cue_failed beep count=2", "cue_failed beep count=3"),
            cues,
        )
        assertEquals("no second rebuild inside the throttle window", 1, b.trackGeneration)
        assertEquals("second cue must not retry without a rebuild", 3, playAttempts)
        b.release()
    }

    @Test
    fun rebuildThrottle_liftsAfterTheInterval() {
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        playScript.addAll(listOf(false, false)) // first cue: fail, rebuild, retry-fail

        b.play(2) // cue_failed at t=0, throttle armed
        nowMs += AlertBeeper.REBUILD_MIN_INTERVAL_MS
        playScript.add(false) // second cue: fail once, rebuilt retry succeeds
        b.play(3)

        assertEquals(
            "past the throttle window the failure must heal again",
            listOf("cue_failed beep count=2", "beep count=3"),
            cues,
        )
        assertEquals(2, b.trackGeneration)
        b.release()
    }

    @Test
    fun statusCues_alsoHealAndReportTruthfully() {
        // The same contract for the status cues.
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        playScript.add(false) // radar_drop: fail once, heal, retry sounds
        b.playRadarDropped()
        playScript.addAll(listOf(false)) // clear: fail; rebuild throttled; no retry
        b.playClear()

        assertEquals(
            listOf("radar_drop", "cue_failed clear"),
            cues,
        )
        b.release()
    }

    @Test
    fun aThrowingPlay_isReportedAsAFailedCue_notLost() {
        // A throw out of the media layer must become an ordinary failed cue.
        // Letting it escape would take the cue out of the report entirely:
        // the capture log would show no cue at all rather than a silent one,
        // which is the one thing the failure-honesty contract forbids.
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        throwScript.addAll(listOf(true, true)) // first attempt and the retry both throw

        b.play(2)

        assertEquals(listOf("cue_failed beep count=2"), cues)
        assertEquals("a throw must still spend the rebuild + retry", 2, playAttempts)
        assertEquals(1, b.trackGeneration)
        b.release()
    }

    @Test
    fun aThrowingPlay_stillHealsOnTheRetry() {
        // Same entry point, opposite outcome: the throw triggers the rebuild
        // and the retry sounds, so the rider hears the cue and the log
        // records the bare tag rather than cue_failed.
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        throwScript.add(true) // only the first attempt throws

        b.play(2)

        assertEquals(listOf("beep count=2"), cues)
        assertEquals(2, playAttempts)
        assertEquals(1, b.trackGeneration)
        b.release()
    }

    @Test
    fun healthyPath_logsBareTags_andNeverRebuilds() {
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        b.play(1)
        b.playUrgent()
        assertEquals(listOf("beep count=1", "urgent"), cues)
        assertEquals("healthy plays must never trigger a rebuild", 0, b.trackGeneration)
        b.release()
    }

    @Test
    fun suppressedCue_isNotReportedAtAll() {
        // In-call suppression is deliberate silence, not a failure: the cue
        // was withheld by design, so neither the bare tag nor cue_failed
        // belongs in the log. Pins the suppress-before-report ordering.
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        audioManager.setMode(AudioManager.MODE_IN_CALL)
        b.play(2)
        assertTrue("suppressed cues must not be reported", cues.isEmpty())
        assertEquals("suppression must skip the play attempt entirely", 0, playAttempts)
        b.release()
    }

    @Test
    fun release_thenPlay_reportsNothingAndNeverRebuilds() {
        // A cue racing service destroy must not report, must not rebuild
        // tracks (nothing would ever release them again), and must not beep.
        val cues = mutableListOf<String>()
        val b = beeper(cues)
        b.release()

        b.play(2)

        assertTrue("no cue may be reported after release", cues.isEmpty())
        assertEquals("no play may be attempted after release", 0, playAttempts)
        assertEquals("no rebuild may follow release", 0, b.trackGeneration)
    }
}
