// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approximate count of vehicles that passed the rider, a figure over the
 * vehicles the accumulator already counts as detected behind. Two ways a
 * counted vehicle is taken as passed: seen ahead of the bike within the
 * road's width, or lost within 3 m of the rider's side while the rider moves,
 * unless it was docked (queued or parked traffic) or moving away when last
 * heard, or a ghost on its last frame. Each vehicle counts once. Each frame
 * hears its vehicles unless a test passes the time of an earlier frame and its
 * readings, as the decoder does while it holds a track. Expected values are
 * literals, never the constants the rule reads.
 */
class VehiclesPassedTest {

    /** Starts late enough that every birth below has the dwell the decoder
     *  needs before it docks a track. */
    private class FakeClock(var now: Long = 10_000L) {
        val provider: () -> Long = { now }
        fun advance(ms: Long) {
            now += ms
        }
    }

    private val clock = FakeClock()
    private val acc = RideStatsAccumulator(clock.provider, clock.provider)

    private fun veh(
        id: Int,
        distanceM: Int = 20,
        isBehind: Boolean = false,
        rangeXmRaw: Float = 1f,
        lateralUnknown: Boolean = false,
        bornAtMs: Long = 100L,
        speedMs: Float = -8f,
        docked: Boolean = false,
        bornDistanceM: Int = Int.MAX_VALUE,
        bornInformative: Boolean = false,
        heardAtMs: Long = clock.now,
    ) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        isBehind = isBehind,
        isAlongsideStationary = docked,
        rangeXmRaw = rangeXmRaw,
        lateralUnknown = lateralUnknown,
        bornAtMs = bornAtMs,
        bornDistanceM = bornDistanceM,
        bornInformative = bornInformative,
        heardAtMs = heardAtMs,
    )

    /** A track the ghost filter applies to: first seen close, on a birth that
     *  tells something about the road. */
    private fun bornClose(distanceM: Int, speedMs: Float, heardAtMs: Long = clock.now, isBehind: Boolean = false) = veh(1, distanceM = distanceM, speedMs = speedMs, bornDistanceM = 5, bornInformative = true, heardAtMs = heardAtMs, isBehind = isBehind)

    /** A frame from the full stream, `speed` m/s for the rider. */
    private fun frame(
        vararg vehicles: Vehicle,
        speed: Float? = 5f,
        counting: Boolean = false,
        turn: TurnStateDecider.State = TurnStateDecider.State.IDLE,
    ) {
        acc.observeFrame(RadarState(vehicles.toList(), source = DataSource.V2, bikeSpeedMs = speed), 6f, counting, turn)
        clock.advance(100L)
    }

    private fun legacyFrame(vararg vehicles: Vehicle, speed: Float? = null) {
        acc.observeFrame(RadarState(vehicles.toList(), source = DataSource.V1, bikeSpeedMs = speed), 6f, counting = false, turnState = TurnStateDecider.State.IDLE)
        clock.advance(100L)
    }

    private fun passed() = acc.snapshot().vehiclesPassed

    // ── seen ahead ───────────────────────────────────────────────────────────

    @Test
    fun aVehicleSeenAheadIsPassed() {
        frame(veh(1), speed = null)
        frame(veh(1, distanceM = 2, isBehind = true), speed = null)
        assertEquals(1, passed())
        assertEquals(1, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aVehicleSeenAheadButFarToTheSideIsNotPassed() {
        // Ahead of the bike on another street is not a vehicle that went by.
        frame(veh(1))
        frame(veh(1, distanceM = 2, isBehind = true, rangeXmRaw = 10.5f))
        assertEquals(0, passed())
        frame(veh(2, bornAtMs = 200L))
        frame(veh(2, distanceM = 2, isBehind = true, rangeXmRaw = -12f, bornAtMs = 200L))
        assertEquals(0, passed())
    }

    @Test
    fun aVehicleSeenAheadFarToTheSideIsNotPassedByHavingBeenBesideTheRiderJustBefore() {
        // Seen ahead is judged by where it is ahead; the fade rule is for a
        // track that left the list, not for one the radar reported ahead.
        frame(veh(1, distanceM = 20))
        frame(veh(1, distanceM = 2))
        frame(veh(1, distanceM = 2, isBehind = true, rangeXmRaw = 12f))
        assertEquals(0, passed())
    }

    @Test
    fun aVehicleSeenAheadWithinTenMetresEitherSideIsPassed() {
        frame(veh(1), veh(2, bornAtMs = 200L))
        frame(
            veh(1, distanceM = 2, isBehind = true, rangeXmRaw = 10f),
            veh(2, distanceM = 2, isBehind = true, rangeXmRaw = -9f, bornAtMs = 200L),
        )
        assertEquals(2, passed())
    }

    @Test
    fun anAheadVehicleWithNoLateralReadingIsStillPassed() {
        // The sentinel frame holds no measurement to call it off the road, so
        // it is taken as on it, as the alert decider takes it.
        frame(veh(1))
        frame(veh(1, distanceM = 2, isBehind = true, rangeXmRaw = 30f, lateralUnknown = true))
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleSeenAheadTwiceIsPassedOnce() {
        frame(veh(1))
        frame(veh(1, distanceM = 2, isBehind = true))
        frame(veh(1, distanceM = 9))
        frame(veh(1, distanceM = 2, isBehind = true))
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleSeenAheadOnFirstSightingIsNotPassedBecauseItWasNeverDetectedBehind() {
        frame(veh(1, distanceM = 5, isBehind = true))
        assertEquals(0, passed())
        assertEquals(0, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aVehicleSeenAheadUnderAnotherBirthIsNotTheVehicleDetectedBehind() {
        frame(veh(1, bornAtMs = 100L))
        frame(veh(1, distanceM = 2, isBehind = true, bornAtMs = 999L))
        assertEquals(0, passed())
    }

    // ── lost beside the rider ────────────────────────────────────────────────

    @Test
    fun aVehicleLostBesideAMovingRiderIsPassed() {
        frame(veh(1, distanceM = 20))
        frame(veh(1, distanceM = 3))
        frame()
        assertEquals(1, passed())
        assertEquals(1, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aVehicleLostFurtherBackIsNotPassed() {
        // The last distance is what it was when the radar lost it: one metre
        // past the 3 m allowance, it may have turned off or dropped out.
        frame(veh(1, distanceM = 20))
        frame(veh(1, distanceM = 4))
        frame()
        assertEquals(0, passed())
    }

    @Test
    fun aVehicleLostBesideAStoppedRiderIsNotPassed() {
        frame(veh(1, distanceM = 20), speed = 0.5f)
        frame(veh(1, distanceM = 2), speed = 0.5f)
        frame(speed = 0.5f)
        assertEquals(0, passed())
    }

    @Test
    fun aVehicleLostWithNoSpeedReadingIsNotPassed() {
        frame(veh(1, distanceM = 20), speed = null)
        frame(veh(1, distanceM = 2), speed = null)
        frame(speed = null)
        assertEquals(0, passed())
    }

    @Test
    fun aVehicleLostBesideARiderAtWalkingPaceIsPassed() {
        frame(veh(1, distanceM = 20), speed = 0.75f)
        frame(veh(1, distanceM = 2), speed = 0.75f)
        frame(speed = 0.75f)
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleLostIsJudgedOnTheRidersSpeedWhenItWasLastHeard() {
        // The decoder can hold a track it no longer hears for seconds, so the
        // frame it leaves the list on is not when the radar last saw it.
        frame(veh(1, distanceM = 20))
        frame(veh(1, distanceM = 2), speed = 0f)
        frame(speed = 5f)
        assertEquals(0, passed())
    }

    @Test
    fun aNewBirthOnTheIdEndsTheOldTrackAsLost() {
        frame(veh(1, distanceM = 20, bornAtMs = 100L))
        frame(veh(1, distanceM = 2, bornAtMs = 100L))
        frame(veh(1, distanceM = 30, bornAtMs = 900L))
        assertEquals(1, passed())
        assertEquals(2, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aNewBirthOnTheIdBesideAStoppedRiderLeavesNothingPassed() {
        frame(veh(1, distanceM = 20, bornAtMs = 100L), speed = 0f)
        frame(veh(1, distanceM = 2, bornAtMs = 100L), speed = 0f)
        frame(veh(1, distanceM = 30, bornAtMs = 900L), speed = 0f)
        assertEquals(0, passed())
        assertEquals(2, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aFollowerLostBesideAMovingRiderIsPassedSinceTheRuleHasNoClosingGate() {
        // Intended: the ride figure has no closing gate, so a car holding the
        // rider's speed and fading beside them is counted like any other.
        frame(veh(1, distanceM = 20, speedMs = 0f))
        frame(veh(1, distanceM = 2, speedMs = 0f))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleHeardAgainWithTheSameReadingsIsJudgedOnTheLaterFrame() {
        // The radar keeps reporting a car that sits still beside the rider, and
        // each such frame is a report although no reading changes.
        frame(veh(1, distanceM = 2, speedMs = 0f), speed = 0f)
        frame(veh(1, distanceM = 2, speedMs = 0f), speed = 5f)
        frame(speed = 0f)
        assertEquals(1, passed())
    }

    @Test
    fun aFrameTheDecoderHoldsIsNotAReport() {
        val heard = clock.now
        frame(veh(1, distanceM = 2, speedMs = 0f), speed = 0f)
        frame(veh(1, distanceM = 2, speedMs = 0f, heardAtMs = heard), speed = 5f)
        frame(speed = 5f)
        assertEquals(0, passed())
    }

    @Test
    fun aSecondReportInTheSameMillisecondIsHeard() {
        val heard = clock.now
        frame(veh(1, distanceM = 6, heardAtMs = heard))
        frame(veh(1, distanceM = 2, heardAtMs = heard))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aSecondReportInTheSameMillisecondIsJudgedOnItsSpeed() {
        val heard = clock.now
        frame(veh(1, distanceM = 2, speedMs = 0.5f, heardAtMs = heard))
        frame(veh(1, distanceM = 2, speedMs = -1f, heardAtMs = heard))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aDockOnlyTheHoldShowsIsNotReadAtAConstantRiderSpeed() {
        // Heard for under the decoder's 3 s dwell beside a crawling rider, then
        // docked on held frames once the dwell is reached there: 2,950 ms at the
        // last report, 3,050 ms on the first held frame.
        val born = clock.now - 2_850L
        val heard = clock.now + 100L
        frame(veh(1, distanceM = 3, speedMs = 0f, bornAtMs = born), speed = 1f)
        frame(veh(1, distanceM = 3, speedMs = 0f, bornAtMs = born), speed = 1f)
        frame(veh(1, distanceM = 3, speedMs = 0f, docked = true, bornAtMs = born, heardAtMs = heard), speed = 1f)
        frame(veh(1, distanceM = 3, speedMs = 0f, docked = true, bornAtMs = born, heardAtMs = heard), speed = 1f)
        frame(speed = 1f)
        assertEquals(1, passed())
    }

    @Test
    fun aTrackHeardAtTimeZeroIsHeard() {
        // Level with the rider at the rider's speed, on a source whose clock
        // starts at zero.
        frame(veh(1, distanceM = 0, speedMs = 0f, heardAtMs = 0L))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleMovingAwayWhenLostIsNotPassed() {
        // The rider is riding past it. Half a metre per second is the radar's
        // smallest step away; a follower at zero is counted (above).
        frame(veh(1, distanceM = 20))
        frame(veh(1, distanceM = 2, speedMs = 0.5f))
        frame()
        assertEquals(0, passed())
        assertEquals(1, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aGhostLostBesideAMovingRiderIsNotPassed() {
        // First seen close and never closing, which the beeps also refuse as
        // a pass: it may be no car at all.
        frame(bornClose(distanceM = 2, speedMs = 0f))
        frame(bornClose(distanceM = 3, speedMs = 0f))
        frame()
        assertEquals(0, passed())
        assertEquals(1, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aVehicleBornFurtherBackIsPassedWhenLost() {
        frame(veh(1, distanceM = 20, speedMs = 0f, bornDistanceM = 20, bornInformative = true))
        frame(veh(1, distanceM = 2, speedMs = 0f, bornDistanceM = 20, bornInformative = true))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleFirstSeenCloseThatClosedIsPassedWhenLost() {
        frame(bornClose(distanceM = 5, speedMs = -3f))
        frame(bornClose(distanceM = 3, speedMs = -3f))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleFirstSeenCloseThatClosedOnlyInATurnIsNotPassed() {
        // A turn fakes that much closing, so the beeps do not admit it.
        repeat(4) { frame(bornClose(distanceM = 3 - it % 2, speedMs = -3f), turn = TurnStateDecider.State.TURNING) }
        frame()
        assertEquals(0, passed())
    }

    @Test
    fun aVehicleFirstSeenCloseIsJudgedAsAGhostOnEveryFrame() {
        // Its closing earns admission on the frame the decoder holds, as it
        // does for the beeps, which are fed every frame.
        val heard = clock.now
        frame(bornClose(distanceM = 3, speedMs = -3f))
        frame(bornClose(distanceM = 3, speedMs = -3f, heardAtMs = heard))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aTurnThatEndsWhileTheDecoderHoldsTheTrackIsJudgedAsTheBeepsJudgeIt() {
        // Four frames heard in a turn, then two the decoder holds after the
        // turn has ended. The beeps' filter, fed the same frames, admits the
        // car on the second held frame.
        val gate = BornCloseGate()
        var t = 0L
        fun both(v: Vehicle, turn: TurnStateDecider.State) {
            gate.update(listOf(v), turn, t)
            t += 100L
            frame(v, turn = turn)
        }
        repeat(4) { both(bornClose(distanceM = 3 - it % 2, speedMs = -3f), TurnStateDecider.State.TURNING) }
        val last = bornClose(distanceM = 2, speedMs = -3f, heardAtMs = clock.now - 100L)
        both(last, TurnStateDecider.State.IDLE)
        assertTrue("one clean frame does not admit it", gate.isGated(last))
        both(last, TurnStateDecider.State.IDLE)
        assertFalse("two do", gate.isGated(last))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aGhostLaterSeenAheadIsPassed() {
        // The exclusions are for a track lost beside the rider; one the radar
        // reports ahead has gone by.
        frame(bornClose(distanceM = 3, speedMs = 0f))
        frame(bornClose(distanceM = 2, speedMs = 0f))
        frame(bornClose(distanceM = 2, speedMs = 0f, isBehind = true))
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleSeenAheadMovingAwayIsPassed() {
        frame(veh(1, distanceM = 20))
        frame(veh(1, distanceM = 2, speedMs = 0.5f))
        frame(veh(1, distanceM = 2, isBehind = true, speedMs = 0.5f))
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleTheHoldDocksBecauseTheRiderSlowedIsJudgedAsLastHeard() {
        val heard = clock.now
        frame(veh(1, distanceM = 3, speedMs = 0f), speed = 4f)
        frame(veh(1, distanceM = 3, speedMs = 0f, docked = true, heardAtMs = heard), speed = 1f)
        frame(speed = 1f)
        assertEquals(1, passed())
    }

    @Test
    fun aVehicleDockedWhenLastHeardIsNotPassedThoughTheRiderSpeedsUpDuringTheHold() {
        val heard = clock.now
        frame(veh(1, distanceM = 3, speedMs = 0f, docked = true), speed = 1f)
        frame(veh(1, distanceM = 3, speedMs = 0f, heardAtMs = heard), speed = 5f)
        frame(speed = 5f)
        assertEquals(0, passed())
    }

    @Test
    fun aDockedVehicleLostBesideAMovingRiderIsNotPassed() {
        // Queued or parked traffic the rider crawls past did not pass them.
        frame(veh(1, distanceM = 5, speedMs = 0f, docked = true), speed = 1f)
        frame(veh(1, distanceM = 3, speedMs = 0f, docked = true), speed = 1f)
        frame(speed = 1f)
        assertEquals(0, passed())
        assertEquals(1, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aVehicleDockedEarlierButNotOnItsLastFrameIsPassedWhenLostBesideTheRider() {
        // Docked is judged on the frame the track was last seen: a car that woke
        // up and drove past is a pass.
        frame(veh(1, distanceM = 6, speedMs = 0f, docked = true), speed = 1f)
        frame(veh(1, distanceM = 5, speedMs = 0f, docked = true), speed = 1f)
        frame(veh(1, distanceM = 2, speedMs = -3f), speed = 1f)
        frame(speed = 1f)
        assertEquals(1, passed())
    }

    @Test
    fun aDockedVehicleLaterSeenAheadIsPassed() {
        // Only the lost-beside rule leaves docked cars out; a car the radar
        // reports ahead has gone by, and a docked track is never one.
        frame(veh(1, distanceM = 5, speedMs = 0f, docked = true), speed = 1f)
        frame(veh(1, distanceM = 3, speedMs = 0f, docked = true), speed = 1f)
        frame(veh(1, distanceM = 2, isBehind = true), speed = 1f)
        assertEquals(1, passed())
    }

    @Test
    fun aDockedVehicleOnANewBirthOfTheIdLeavesTheOldTrackNotPassed() {
        frame(veh(1, distanceM = 3, speedMs = 0f, docked = true, bornAtMs = 100L), speed = 1f)
        frame(veh(1, distanceM = 30, bornAtMs = 900L), speed = 1f)
        assertEquals(0, passed())
        assertEquals(2, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aTrackSeenBehindOnlyOnLateralUnknownFramesIsPassedWhenLostBesideAMovingRider() {
        frame(veh(1, distanceM = 20, lateralUnknown = true))
        frame(veh(1, distanceM = 2, lateralUnknown = true))
        frame()
        assertEquals(1, passed())
    }

    @Test
    fun aDifferentBirthSeenAheadLeavesTheOpenTrackAsLost() {
        frame(veh(1, distanceM = 20, bornAtMs = 100L))
        frame(veh(1, distanceM = 2, bornAtMs = 100L))
        frame(veh(1, distanceM = 5, isBehind = true, bornAtMs = 999L))
        assertEquals(1, passed())
    }

    @Test
    fun aDifferentBirthSeenAheadBesideAStoppedRiderLeavesNothingPassed() {
        frame(veh(1, distanceM = 20, bornAtMs = 100L), speed = 0f)
        frame(veh(1, distanceM = 2, bornAtMs = 100L), speed = 0f)
        frame(veh(1, distanceM = 5, isBehind = true, bornAtMs = 999L), speed = 0f)
        assertEquals(0, passed())
    }

    @Test
    fun aVehicleCutOffByARadarDropIsNotPassed() {
        frame(veh(1, distanceM = 20))
        frame(veh(1, distanceM = 2))
        acc.dropOpenTracks()
        frame()
        assertEquals(0, passed())
    }

    @Test
    fun aVehicleNeverCountedAsDetectedIsNotPassed() {
        // The decoder emits no negative distance; whatever does is never one of
        // the vehicles detected behind, so it cannot be passed either.
        frame(veh(1, distanceM = -2))
        frame()
        assertEquals(0, passed())
        assertEquals(0, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aVehicleCountedByBothRulesIsPassedOnce() {
        frame(veh(1))
        frame(veh(1, distanceM = 2, isBehind = true))
        frame(veh(1, distanceM = 2))
        frame()
        assertEquals(1, passed())
    }

    // ── the figure as a whole ────────────────────────────────────────────────

    @Test
    fun eachPassedVehicleIsCountedOnceAndOthersAreNot() {
        frame(veh(1, bornAtMs = 100L), veh(2, bornAtMs = 200L), veh(3, bornAtMs = 300L))
        frame(
            veh(1, distanceM = 2, isBehind = true, bornAtMs = 100L),
            veh(2, distanceM = 3, bornAtMs = 200L),
            veh(3, bornAtMs = 300L),
        )
        frame(veh(3, bornAtMs = 300L))
        assertEquals(2, passed())
        assertEquals(3, acc.snapshot().overtakesTotal)
    }

    @Test
    fun thePassedCountDoesNotDependOnClosePassCounting() {
        frame(veh(1), counting = true)
        frame(veh(1, distanceM = 2, isBehind = true), counting = true)
        frame(veh(2, bornAtMs = 200L), counting = false)
        frame(veh(2, distanceM = 2, isBehind = true, bornAtMs = 200L), counting = false)
        assertEquals(2, passed())
    }

    @Test
    fun aRangeOnlyRideHasNoPassedFigure() {
        legacyFrame(veh(1, lateralUnknown = true), speed = 5f)
        legacyFrame(veh(1, distanceM = 2, lateralUnknown = true), speed = 5f)
        legacyFrame(speed = 5f)
        assertNull(passed())
        assertEquals(1, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aRideOnTheFullStreamWithNoPassIsZeroNotUnknown() {
        frame(veh(1))
        frame()
        assertEquals(0, passed())
    }

    @Test
    fun aRideBeforeItsFirstFrameHasNoPassedFigure() {
        assertNull(passed())
    }

    @Test
    fun aRideThatSawBothStreamsHasAFigureOnceTheFullStreamHasAnswered() {
        legacyFrame()
        assertNull(passed())
        frame()
        assertEquals(0, passed())
    }

    // ── publishing the change ────────────────────────────────────────────────

    @Test
    fun aVehicleSeenAheadMarksTheStatsChanged() {
        frame(veh(1), speed = null)
        acc.markPublished()
        frame(veh(1, distanceM = 2, isBehind = true), speed = null)
        assertTrue(acc.changedSinceLast())
    }

    @Test
    fun aVehicleFoundLostAfterALongGapMarksTheStatsChanged() {
        // The long gap only isolates the change flag: no distance is integrated
        // across it, so the pass is the only change on the frame. (In production
        // a gap over the 5 s stall tears the link down and the reattach drops
        // the open tracks.) A closing speed under the close-pass floor, so the
        // track records no clearance.
        frame(veh(1, distanceM = 20, speedMs = -2f))
        frame(veh(1, distanceM = 2, speedMs = -2f))
        clock.advance(10_000L)
        acc.markPublished()
        frame()
        assertTrue(acc.changedSinceLast())
    }

    @Test
    fun aVehicleSeenAheadAgainDoesNotMarkTheStatsChanged() {
        frame(veh(1), speed = null)
        frame(veh(1, distanceM = 2, isBehind = true), speed = null)
        acc.markPublished()
        frame(veh(1, distanceM = 2, isBehind = true), speed = null)
        assertFalse(acc.changedSinceLast())
    }

    @Test
    fun theFigureAppearingMarksTheStatsChanged() {
        legacyFrame()
        acc.markPublished()
        frame(speed = null)
        assertTrue(acc.changedSinceLast())
    }
}
