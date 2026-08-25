// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [TurnStateDecider]: a rotation episode qualifies as a turn only on
 * cumulative signed heading change past the angle threshold, holds for the
 * post-turn window after the rotation quiets, and never accumulates from
 * steering wobble, sub-threshold manoeuvres, or self-cancelling S-wiggles.
 */
class TurnStateDeciderTest {

    /** Feed a constant yaw rate for [durationMs] in [stepMs] steps,
     *  starting after [startMs], returning the timestamp of the last
     *  sample. */
    private fun TurnStateDecider.feed(
        rateRadS: Float,
        durationMs: Long,
        startMs: Long,
        stepMs: Long = 50L,
    ): Long {
        var t = startMs
        while (t <= startMs + durationMs) {
            onYawSample(rateRadS, t)
            t += stepMs
        }
        return t - stepMs
    }

    @Test
    fun idleWithNoRotation() {
        val d = TurnStateDecider()
        val end = d.feed(0.02f, durationMs = 5_000, startMs = 1_000)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(end))
        assertFalse(d.holdActive(end))
    }

    @Test
    fun ninetyDegreeCornerQualifiesAndHolds() {
        val d = TurnStateDecider()
        // 0.5 rad/s (~29 deg/s) for 3.2 s ~= 92 degrees - a normal corner.
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(end))
        // Rotation stops; after the quiet-end the state moves to HOLD.
        val quietEnd = d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(TurnStateDecider.State.HOLD, d.stateAt(quietEnd))
        assertTrue(d.holdActive(quietEnd))
        // Hold expires HOLD_MS after the episode ended.
        assertTrue(d.holdActive(quietEnd + TurnStateDecider.HOLD_MS - 2_000))
        assertFalse(d.holdActive(quietEnd + TurnStateDecider.HOLD_MS + 1_000))
    }

    @Test
    fun negativeRotationQualifiesLikePositive() {
        val d = TurnStateDecider()
        val end = d.feed(-0.5f, durationMs = 3_200, startMs = 1_000)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(end))
    }

    @Test
    fun laneChangeDoesNotQualify() {
        val d = TurnStateDecider()
        // ~0.3 rad/s for 1 s ~= 17 degrees: a brisk lane change.
        val end = d.feed(0.3f, durationMs = 1_000, startMs = 1_000)
        val after = d.feed(0f, durationMs = 1_500, startMs = end + 50)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(after))
        assertFalse(d.holdActive(after))
    }

    @Test
    fun sWiggleCancelsAndDoesNotQualify() {
        val d = TurnStateDecider()
        // +40 degrees then -40 degrees within one episode: nets to ~0.
        val mid = d.feed(0.35f, durationMs = 2_000, startMs = 1_000)
        val end = d.feed(-0.35f, durationMs = 2_000, startMs = mid + 50)
        val after = d.feed(0f, durationMs = 1_500, startMs = end + 50)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(after))
    }

    @Test
    fun twoSubThresholdEpisodesDoNotAccumulate() {
        val d = TurnStateDecider()
        // Two 35-degree turns separated by 3 s of straight riding: each
        // episode resets, so neither qualifies.
        val first = d.feed(0.3f, durationMs = 2_000, startMs = 1_000)
        val quiet = d.feed(0f, durationMs = 3_000, startMs = first + 50)
        val second = d.feed(0.3f, durationMs = 2_000, startMs = quiet + 50)
        val after = d.feed(0f, durationMs = 1_500, startMs = second + 50)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(after))
    }

    @Test
    fun midCornerPauseWithinQuietEndBridgesOneEpisode() {
        val d = TurnStateDecider()
        // Two 35-degree phases of one junction with a 400 ms straighten
        // between them (below QUIET_END_MS): one episode, qualifies.
        val first = d.feed(0.35f, durationMs = 1_750, startMs = 1_000)
        val pause = d.feed(0f, durationMs = 400, startMs = first + 50)
        val second = d.feed(0.35f, durationMs = 1_750, startMs = pause + 50)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(second))
    }

    @Test
    fun sensorStallCannotManufactureAngle() {
        val d = TurnStateDecider()
        // Episode opens, then one sample arrives 5 s late at turn rate.
        // The clamped dt keeps the integrated angle far below threshold.
        d.onYawSample(0.5f, 1_000)
        d.onYawSample(0.5f, 6_000)
        d.onYawSample(0f, 6_050)
        val after = d.feed(0f, durationMs = 1_500, startMs = 6_100)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(after))
    }

    @Test
    fun resetClearsEpisodeAndHold() {
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(end))
        d.reset()
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(end))
        assertFalse(d.holdActive(end))
    }

    @Test
    fun holdActiveDuringTurnItself() {
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        assertTrue(d.holdActive(end))
    }

    @Test
    fun cumulativeDegTracksTheEpisodeAndClearsWhenItCloses() {
        val d = TurnStateDecider()
        // 0.5 rad/s in 50 ms steps: the opening sample only starts the
        // episode, so 64 samples integrate 0.025 rad each = 1.6 rad.
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        assertEquals(91.67f, d.cumulativeDeg, 0.1f)
        d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(0f, d.cumulativeDeg, 1e-4f)
    }

    @Test
    fun lastEpisodeDegKeepsTheSignedTotalAfterTheEpisodeCloses() {
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        // Not knowable until the episode ends - it is still running here.
        assertEquals(0f, d.lastEpisodeDeg, 1e-4f)
        d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(91.67f, d.lastEpisodeDeg, 0.1f)
    }

    @Test
    fun episodeActiveIsLiveBeforeTheAngleQualifies() {
        val d = TurnStateDecider()
        // 17 degrees: above the rate floor, far below the qualifying
        // angle. The episode is running while the state still reads IDLE.
        val end = d.feed(0.3f, durationMs = 1_000, startMs = 1_000)
        assertTrue(d.episodeActive)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(end))
        val after = d.feed(0f, durationMs = 1_500, startMs = end + 50)
        assertFalse(d.episodeActive)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(after))
    }

    @Test
    fun lastEpisodeDegIsNegativeForTheOtherDirection() {
        val d = TurnStateDecider()
        val end = d.feed(-0.5f, durationMs = 3_200, startMs = 1_000)
        d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(-91.67f, d.lastEpisodeDeg, 0.1f)
    }

    @Test
    fun lastEpisodeDegRecordsEpisodesThatNeverQualified() {
        val d = TurnStateDecider()
        // A 17-degree lane change: never TURNING, but its total is still
        // the last rotation the rider made and is recorded as such.
        val end = d.feed(0.3f, durationMs = 1_000, startMs = 1_000)
        val after = d.feed(0f, durationMs = 1_500, startMs = end + 50)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(after))
        assertEquals(17.19f, d.lastEpisodeDeg, 0.1f)
    }

    @Test
    fun resetClearsLastEpisodeDeg() {
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(91.67f, d.lastEpisodeDeg, 0.1f)
        d.reset()
        assertEquals(0f, d.lastEpisodeDeg, 1e-4f)
    }
}
