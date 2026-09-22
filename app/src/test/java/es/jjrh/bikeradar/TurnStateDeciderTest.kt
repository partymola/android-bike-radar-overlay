// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [TurnStateDecider]: a turn starts on net signed rotation inside the
 * window, ends when that net falls away, the rotation quiets, or the window
 * stops reaching the entry angle, holds for the post-turn window, and never
 * comes from wobble whose half-swing stays under the entry angle,
 * sub-threshold manoeuvres, or self-cancelling S-wiggles.
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

    /** Steering wobble: a sinusoid of [amplitudeRadS] and [periodMs], well
     *  above the rate floor so it never lets a rotation episode go quiet.
     *  Its integral is bounded (A * T / pi per half-cycle), which is what a
     *  handlebar oscillating about straight-ahead produces. */
    private fun TurnStateDecider.wobble(
        durationMs: Long,
        startMs: Long,
        amplitudeRadS: Float = 0.4f,
        periodMs: Long = 2_000L,
        stepMs: Long = 50L,
        onEach: (Long) -> Unit = {},
    ): Long {
        var t = startMs
        while (t <= startMs + durationMs) {
            val phase = 2 * Math.PI * (t - startMs) / periodMs
            onYawSample((amplitudeRadS * kotlin.math.sin(phase)).toFloat(), t)
            onEach(t)
            t += stepMs
        }
        return t - stepMs
    }

    @Test
    fun wobbleAfterACornerDoesNotHoldTheTurnOpen() {
        // Wobble never drops below the rate floor for the quiet-end, so only
        // the window can end this turn. The corner is 92 degrees; the wobble
        // after it nets at most 14.6 degrees per half-cycle, so the turn must
        // end once the corner leaves the window and must not come back.
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(end))
        var lastTurning = end
        d.wobble(durationMs = 60_000, startMs = end + 50) { t ->
            if (d.stateAt(t) == TurnStateDecider.State.TURNING) lastTurning = t
        }
        // The corner can sit in the 5 s window for at most 5 s after it ends.
        // The last TURNING sample is 4100 ms after the corner: the window net
        // has fallen under the exit and the wobble, averaged over the sustain
        // span, has stayed under its bound for the confirmation. Exact,
        // because the fixture is deterministic.
        assertEquals(4_100L, lastTurning - end)
    }

    @Test
    fun aTurnTheOtherWayEndsAtTheSameTimeOnARealClock() {
        // The same corner and wobble mirrored, on an elapsedRealtime-sized
        // clock. Every other test of the window and stall exits turns
        // positive and starts at 1 s, where the stall stamp or the window
        // exit can lose its abs() and still pass.
        val d = TurnStateDecider()
        val end = d.feed(-0.5f, durationMs = 3_200, startMs = 100_000_000L)
        var lastTurning = end
        d.wobble(durationMs = 60_000, startMs = end + 50, amplitudeRadS = -0.4f) { t ->
            if (d.stateAt(t) == TurnStateDecider.State.TURNING) lastTurning = t
        }
        assertEquals(4_100L, lastTurning - end)
    }

    @Test
    fun largeWobbleAfterACornerCannotHoldTheTurn() {
        // 0.6 rad/s on a 2 s period: each half-swing is 21.9 degrees, under
        // the entry angle, so this is wobble, not a weave. The 3 s sustain
        // span holds a cycle and a half, so its net keeps swinging over the
        // calm bound and calm is never confirmed; only the stall cap ends it.
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        var lastTurning = end
        d.wobble(durationMs = 60_000, startMs = end + 50, amplitudeRadS = 0.6f) { t ->
            if (d.stateAt(t) == TurnStateDecider.State.TURNING) lastTurning = t
        }
        // The window last reached the entry angle 3700 ms after the corner;
        // the stall cap ends the turn 8 s later, at 11700, so the last
        // TURNING sample is 11650. Exact, because the fixture is.
        assertEquals(11_650L, lastTurning - end)
    }

    @Test
    fun wobbleAloneNeverTurns() {
        val d = TurnStateDecider()
        var turned = false
        d.wobble(durationMs = 180_000, startMs = 1_000) { t ->
            if (d.stateAt(t) == TurnStateDecider.State.TURNING) turned = true
        }
        assertFalse(turned)
    }

    /** Every sample of a second leg at [rate] for [durationMs], straight
     *  after a 92-degree corner the other way, must read TURNING: the rider
     *  is still rotating, so the rear cone is still sweeping. */
    private fun assertTurningThroughAReversal(rate: Float, durationMs: Long) {
        val d = TurnStateDecider()
        val first = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        var t = first + 50
        while (t <= first + durationMs) {
            d.onYawSample(rate, t)
            assertEquals("at ${t - first} ms into the second leg", TurnStateDecider.State.TURNING, d.stateAt(t))
            t += 50
        }
    }

    @Test
    fun aReversalAtCorneringRateStaysTurning() {
        // Left 92 then right 92 at the same rate: a chicane. The two legs
        // cancel in the window, so the net passes under the exit angle about
        // two thirds of the way through the second leg while the bike is still
        // rotating hard.
        assertTurningThroughAReversal(-0.5f, 3_200)
    }

    @Test
    fun aReversalWithWobbleStaysTurning() {
        // A second leg of -0.3 rad/s under 0.5 rad/s of 2 s wobble: a wobble
        // half-cycle cancels the leg over any one-second span it fills, so the
        // sustain span has to be longer than that for the turn to hold while
        // the rider is still carving. The rate never dwells under the floor
        // for the quiet-end, so only the window exit could end it.
        val d = TurnStateDecider()
        val first = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        var t = first + 50
        while (t <= first + 5_000) {
            val phase = 2 * Math.PI * (t - first) / 2_000
            d.onYawSample((-0.3 + 0.5 * kotlin.math.sin(phase)).toFloat(), t)
            assertEquals("at ${t - first} ms into the second leg", TurnStateDecider.State.TURNING, d.stateAt(t))
            t += 50
        }
    }

    @Test
    fun aSlowReversalStaysTurning() {
        // The second leg at 9.2 deg/s, just above the 8.6 deg/s rate floor,
        // held for 6 s. It nets 27.5 degrees per 3 s sustain span: over the
        // 15-degree bound, so the turn holds; a bound as loose as 30 would
        // call this leg calm and end the turn inside it.
        assertTurningThroughAReversal(-0.16f, 6_000)
    }

    @Test
    fun aSlowWobblingReversalStaysTurning() {
        // A second leg at 0.12 rad/s (6.9 deg/s) under 0.4 rad/s of 1 s
        // wobble. The wobble keeps the episode alive and cancels over any whole
        // number of seconds, so the leg nets 20.6 degrees per 3 s span, over
        // the sustain bound, and 13.75 per 2 s span, under it. The window net
        // falls under the exit between 3.3 and 4.7 s into the leg, so a 2 s
        // span would end the turn there while the rider is still turning.
        val d = TurnStateDecider()
        val first = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        var t = first + 50
        while (t <= first + 6_000) {
            val phase = 2 * Math.PI * (t - first) / 1_000
            d.onYawSample((-0.12 + 0.4 * kotlin.math.sin(phase)).toFloat(), t)
            assertEquals("at ${t - first} ms into the second leg", TurnStateDecider.State.TURNING, d.stateAt(t))
            t += 50
        }
    }

    @Test
    fun aMomentaryLullInAWobblingLegDoesNotEndTheTurn() {
        // -0.18 rad/s under 0.5 rad/s of 2 s wobble: once per cycle the
        // wobble's swing drags the 3 s net under the sustain bound for a
        // fraction of a second while the leg carries on. The confirmation
        // is what rides through it.
        val d = TurnStateDecider()
        val first = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        var t = first + 50
        while (t <= first + 6_000) {
            val phase = 2 * Math.PI * (t - first) / 2_000 + Math.PI
            d.onYawSample((-0.18 + 0.5 * kotlin.math.sin(phase)).toFloat(), t)
            assertEquals("at ${t - first} ms into the second leg", TurnStateDecider.State.TURNING, d.stateAt(t))
            t += 50
        }
    }

    @Test
    fun aSlowCornerQualifiesInsideTheWindow() {
        // 0.175 rad/s (10 deg/s) for 5 s = 50 degrees: a wide, slow corner.
        // The window must be long enough to hold 42 degrees of it; a 3 s
        // window never holds more than 30 and would miss the corner outright.
        val d = TurnStateDecider()
        var turned = false
        var t = 1_000L
        while (t <= 6_000L) {
            d.onYawSample(0.175f, t)
            if (d.stateAt(t) == TurnStateDecider.State.TURNING) turned = true
            t += 50
        }
        assertTrue(turned)
    }

    @Test
    fun aGentleBendAfterACornerHoldsTheTurnUntilTheStallCap() {
        // Wobble riding on a steady 0.12 rad/s (6.9 deg/s) of heading change.
        // The wobble's 1.25 s period fits the 5 s window exactly four times,
        // so the window nets a constant 34.4 degrees, above the exit. The 3 s
        // sustain span nets 20.6 plus up to 10.8 of wobble either way, so it
        // dips under its 15-degree bound for about 0.4 s a cycle, short of the
        // 0.5 s confirmation. The below-floor dips last 125 ms, far inside the
        // quiet-end. The window never reaches the entry angle again, so the
        // stall cap ends the turn; until then it must hold.
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        var lastTurning = end
        var t = end + 50
        while (t <= end + 20_000) {
            val phase = 2 * Math.PI * (t - end) / 1_250
            d.onYawSample((0.12 + 0.5 * kotlin.math.sin(phase)).toFloat(), t)
            if (t - end <= 7_000) {
                assertEquals("at ${t - end} ms", TurnStateDecider.State.TURNING, d.stateAt(t))
            }
            if (d.stateAt(t) == TurnStateDecider.State.TURNING) lastTurning = t
            t += 50
        }
        // The window last reaches the entry angle 4750 ms after the corner;
        // the stall cap ends the turn 8 s later, so the last TURNING sample
        // is 12700.
        assertEquals(12_700L, lastTurning - end)
    }

    @Test
    fun aBendBelowTheFloorEndsTheTurnAtTheQuietExit() {
        // A corner, then a steady 0.12 rad/s (6.9 deg/s) bend with no wobble:
        // below the rate floor, so the episode goes quiet 700 ms after the
        // last sample above it, and the turn ends there although the bend
        // carries on.
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        assertEquals(4_200L, end)
        var t = end + 50
        while (t <= end + 3_000) {
            d.onYawSample(0.12f, t)
            val expected = if (t < 4_900) TurnStateDecider.State.TURNING else TurnStateDecider.State.HOLD
            assertEquals("at $t ms", expected, d.stateAt(t))
            t += 50
        }
    }

    @Test
    fun rotationUnderTheFloorInsideAnEpisodeCountsTowardsATurn() {
        // Three legs at 0.3 rad/s net 41.25 degrees, just under the entry
        // angle; two 600 ms stretches at 0.12 rad/s between them, below the
        // floor but inside the quiet-end, add 8.25. Only counting both
        // reaches 42.
        val d = TurnStateDecider()
        d.feed(0.3f, durationMs = 1_000, startMs = 1_000)
        d.feed(0.12f, durationMs = 550, startMs = 2_050)
        d.feed(0.3f, durationMs = 800, startMs = 2_650)
        d.feed(0.12f, durationMs = 550, startMs = 3_500)
        val last = d.feed(0.3f, durationMs = 500, startMs = 4_100)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(last))
    }

    @Test
    fun aSampleExactlyOneWindowOldHasLeftTheWindow() {
        // The step integrated at 1050 ms is 5000 ms old at 6050 and must be
        // out of the window then: 1.43 degrees if it were still counted.
        val d = TurnStateDecider()
        d.onYawSample(0.5f, 1_000)
        d.onYawSample(0.5f, 1_050)
        assertEquals(1.43f, d.windowDeg, 0.01f)
        d.onYawSample(0f, 6_050)
        assertEquals(0f, d.windowDeg, 1e-4f)
    }

    @Test
    fun twoBendsOneSecondApartAreOneTurn() {
        // 30.1 degrees, a 1 s straight (long enough to close the first
        // episode), then 17.2 degrees the same way: 47.3 inside one window.
        // The window deliberately outlives a quiet episode, so bends in quick
        // succession count as the corner they are.
        val d = TurnStateDecider()
        val first = d.feed(0.3f, durationMs = 1_750, startMs = 1_000)
        val straight = d.feed(0f, durationMs = 1_000, startMs = first + 50)
        assertFalse(d.episodeActive)
        val second = d.feed(0.3f, durationMs = 1_000, startMs = straight + 50)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(second))
    }

    @Test
    fun lastTurnDegCountsFromTheQualifyingWindow() {
        // Wobble opens the episode 20 s before the corner, so the episode
        // integral and the turn's own total differ; every other fixture
        // starts its corner from rest, where the two are equal.
        val d = TurnStateDecider()
        val wobbleEnd = d.wobble(durationMs = 20_000, startMs = 1_000, periodMs = 3_000)
        val corner = d.feed(0.5f, durationMs = 3_200, startMs = wobbleEnd + 50)
        val episodeDeg = d.cumulativeDeg
        d.feed(0f, durationMs = 1_000, startMs = corner + 50)
        // The episode carries 16 degrees of wobble residue on top of the
        // corner; the turn counts only what was in the window at entry, and
        // the window by then held some of the wobble's last swing back.
        assertEquals(107.83f, episodeDeg, 0.05f)
        assertEquals(87.86f, d.lastTurnDeg, 0.05f)
    }

    @Test
    fun aCornerQualifiesAtTheEntryAngleOfNetRotation() {
        // 0.5 rad/s in 50 ms steps integrates 0.025 rad per sample after the
        // opener: 29 samples are 41.55 degrees, 30 are 42.97. Asserted on both
        // sides of the boundary so the entry angle is pinned, not bounded.
        val d = TurnStateDecider()
        d.onYawSample(0.5f, 1_000)
        repeat(29) { d.onYawSample(0.5f, 1_050L + it * 50) }
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(2_450))
        d.onYawSample(0.5f, 2_500)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(2_500))
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
        // 0.5 rad/s (~29 deg/s) for 3.2 s ~= 92 degrees, a normal corner.
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        assertEquals(4_200L, end)
        assertEquals(TurnStateDecider.State.TURNING, d.stateAt(end))
        // Rotation stops; after the quiet-end the state moves to HOLD.
        val quietEnd = d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(TurnStateDecider.State.HOLD, d.stateAt(quietEnd))
        assertTrue(d.holdActive(quietEnd))
        // The turn ends at 4900, 700 ms after the last sample above the
        // floor, and the hold runs 10 s from there.
        assertTrue(d.holdActive(14_899))
        assertFalse(d.holdActive(14_900))
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
        // Two 34.4-degree bends separated by 3 s of straight riding. The
        // window carries rotation across the gap, but no 5 s span holds more
        // than one bend's worth, 34.4 degrees, so neither qualifies.
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
        // between them: 70 degrees inside the window, so it qualifies.
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
    fun lastTurnDegKeepsTheSignedTotalAfterTheTurnEnds() {
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        // Not knowable until the turn ends: it is still running here.
        assertEquals(0f, d.lastTurnDeg, 1e-4f)
        d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(91.67f, d.lastTurnDeg, 0.1f)
    }

    @Test
    fun aTurnEndedByWobbleStillReportsTheCornersDirection() {
        // The HOLD log line is the only record of which way a corner went.
        // When wobble, not quiet, ends the turn, the episode is still running
        // and has no total yet. The turn's own total is the corner, 91.67,
        // plus the wobble up to the exit sample: 92.21.
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        d.wobble(durationMs = 10_000, startMs = end + 50)
        assertEquals(TurnStateDecider.State.HOLD, d.stateAt(end + 10_050))
        assertEquals(92.21f, d.lastTurnDeg, 0.05f)
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
    fun lastTurnDegIsNegativeForTheOtherDirection() {
        val d = TurnStateDecider()
        val end = d.feed(-0.5f, durationMs = 3_200, startMs = 1_000)
        d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(-91.67f, d.lastTurnDeg, 0.1f)
    }

    @Test
    fun rotationThatNeverTurnsLeavesTheLastTurnAlone() {
        val d = TurnStateDecider()
        val corner = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        val afterCorner = d.feed(0f, durationMs = 1_000, startMs = corner + 50)
        assertEquals(91.67f, d.lastTurnDeg, 0.1f)
        // A 17-degree lane change in the post-turn window is not a turn and
        // must not replace the corner's direction.
        val laneChange = d.feed(0.3f, durationMs = 1_000, startMs = afterCorner + 50)
        d.feed(0f, durationMs = 1_500, startMs = laneChange + 50)
        assertEquals(91.67f, d.lastTurnDeg, 0.1f)
    }

    @Test
    fun eachCompletedTurnOverwritesTheLastTurnAngle() {
        // Every other test starts from a fresh decider, where a write-once
        // guard still takes the first value and stays green; under one this
        // field would report the ride's first corner forever after.
        val d = TurnStateDecider()
        val first = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        val afterFirst = d.feed(0f, durationMs = 1_000, startMs = first + 50)
        assertEquals(91.67f, d.lastTurnDeg, 0.1f)
        val second = d.feed(-0.5f, durationMs = 3_200, startMs = afterFirst + 50)
        d.feed(0f, durationMs = 1_000, startMs = second + 50)
        assertEquals(-91.67f, d.lastTurnDeg, 0.1f)
    }

    @Test
    fun resetClearsLastTurnDeg() {
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        d.feed(0f, durationMs = 1_000, startMs = end + 50)
        assertEquals(91.67f, d.lastTurnDeg, 0.1f)
        d.reset()
        assertEquals(0f, d.lastTurnDeg, 1e-4f)
    }

    @Test
    fun resetEndsATurnInProgress() {
        val d = TurnStateDecider()
        val end = d.feed(0.5f, durationMs = 3_200, startMs = 1_000)
        d.reset()
        // After reset a 17-degree lane change must not complete the old turn.
        val after = d.feed(0.3f, durationMs = 1_000, startMs = end + 50)
        assertEquals(TurnStateDecider.State.IDLE, d.stateAt(after))
    }
}
