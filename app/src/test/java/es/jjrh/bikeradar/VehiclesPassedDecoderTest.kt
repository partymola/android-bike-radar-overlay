// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The passed count fed by the real decoder, which keeps a track it has stopped
 * hearing for a while and re-judges it on every frame of that hold. A vehicle
 * lost beside the rider is judged as it was when the radar last heard it, not
 * on the frames the decoder replays after. The radar also goes on reporting a
 * car that stands still with readings that do not change, and those frames are
 * heard, as is a second report in the same millisecond. A stopped rider reads
 * 0.5 m/s, the radar's speed floor.
 */
class VehiclesPassedDecoderTest {

    private var now = 1_000L
    private val decoder = RadarV2Decoder(nowMs = { now })
    private val acc = RideStatsAccumulator({ now }, { now })

    private fun packet(vararg targets: ByteArray): ByteArray = byteArrayOf(0x02, 0x00) + targets.fold(byteArrayOf()) { a, b -> a + b }

    /** [rangeY] and [rangeX] in decimetres, [speedHalf] in half metres per second. */
    private fun target(tid: Int, rangeY: Int, rangeX: Int = 0, speedHalf: Int = 0): ByteArray {
        val packed = ((rangeY and 0x1FFF) shl 11) or (rangeX and 0x7FF)
        return byteArrayOf(
            tid.toByte(),
            RadarV2Decoder.CLASS_MODERATE.toByte(),
            (packed and 0xFF).toByte(),
            ((packed shr 8) and 0xFF).toByte(),
            ((packed shr 16) and 0xFF).toByte(),
            16,
            7,
            speedHalf.toByte(),
            0x80.toByte(),
        )
    }

    /** A full device-status frame, the rider's speed in quarter metres per second. */
    private fun status(speedQuarters: Int) = byteArrayOf(0x04, 0x00, 0x05, 0x00, 0x00, speedQuarters.toByte())

    private fun feed(at: Long, payload: ByteArray): RadarState? {
        now = at
        return decoder.feed(payload)?.also { acc.observeFrame(it, 6f, false, TurnStateDecider.State.IDLE) }
    }

    /** Device-status frames every half second from [from] to [to] inclusive. */
    private fun statusUntil(from: Long, to: Long, speedQuarters: Int) {
        var t = from
        while (t <= to) {
            feed(t, status(speedQuarters))
            t += 500L
        }
    }

    /** The same car standing still 3 m back and 1.5 m to the side, heard every
     *  100 ms from [from] to [to] inclusive; the last frame's vehicle. */
    private fun standingCar(from: Long, to: Long): Vehicle {
        var v: Vehicle? = null
        for (t in from..to step 100L) v = feed(t, packet(target(1, rangeY = 30, rangeX = 15)))!!.vehicles.single()
        return v!!
    }

    private fun passed() = acc.snapshot().vehiclesPassed

    @Test
    fun theDecoderStampsAHeldTrackWithTheFrameItWasLastHeardOn() {
        feed(1_000L, status(20))
        assertEquals(1_000L, standingCar(1_000L, 1_000L).heardAtMs)
        assertEquals("held", 1_000L, feed(1_300L, status(20))!!.vehicles.single().heardAtMs)
        assertEquals("heard again, readings unchanged", 1_400L, standingCar(1_400L, 1_400L).heardAtMs)
    }

    @Test
    fun aHeldTrackCarriesItsLastDistanceAndSpeedUnchanged() {
        feed(1_000L, status(20))
        feed(1_000L, packet(target(7, rangeY = 40, rangeX = 15, speedHalf = -8)))
        val held = feed(1_300L, status(20))!!.vehicles.single()
        assertEquals(4, held.distanceM)
        assertEquals(-4f, held.speedMs, 0f)
        assertEquals(1_000L, held.heardAtMs)
    }

    @Test
    fun aDockedCarIsNotPassedWhenTheRiderSpeedsUpWhileTheDecoderHoldsIt() {
        // Born while the decoder warms up, so the ghost filter has no say. Its
        // readings never change, so the dock arrives with the dwell alone.
        feed(1_000L, status(4))
        assertTrue("the decoder docks it", standingCar(1_000L, 4_500L).isAlongsideStationary)
        val held = feed(5_000L, status(16))!!.vehicles.single()
        assertFalse("the hold re-judges it at the new speed", held.isAlongsideStationary)
        statusUntil(5_500L, 10_000L, 16)
        assertTrue("pruned", decoder.feed(status(16))!!.vehicles.isEmpty())
        assertEquals(1, acc.snapshot().overtakesTotal)
        assertEquals(0, passed())
    }

    @Test
    fun aCarHeardWithFrozenReadingsIsJudgedOnTheRiderHavingStoppedBesideIt() {
        feed(1_000L, status(16))
        assertFalse("not docked while the rider rolls", standingCar(1_000L, 3_000L).isAlongsideStationary)
        assertEquals("the rider stops", 0.5f, feed(3_050L, status(2))!!.bikeSpeedMs)
        assertFalse("not docked at its last report", standingCar(3_100L, 3_900L).isAlongsideStationary)
        statusUntil(5_500L, 10_500L, 2)
        assertTrue("pruned", decoder.feed(status(2))!!.vehicles.isEmpty())
        assertEquals(1, acc.snapshot().overtakesTotal)
        assertEquals(0, passed())
    }

    @Test
    fun aCarThatDocksWhileTheRiderShiftsOneSpeedStepIsNotPassed() {
        feed(1_000L, status(4))
        assertFalse("not docked before its dwell", standingCar(1_000L, 3_500L).isAlongsideStationary)
        feed(3_550L, status(5))
        assertTrue("docked on frames the radar sent", standingCar(3_600L, 5_000L).isAlongsideStationary)
        statusUntil(5_500L, 10_500L, 5)
        assertTrue("pruned", decoder.feed(status(5))!!.vehicles.isEmpty())
        assertEquals(0, passed())
    }

    @Test
    fun aDockOnlyTheHoldShowsIsNotReadSoTheCarIsJudgedAsLastHeard() {
        // Heard beside a rider at 4 m/s, then held while the rider slows: the
        // decoder docks the held track, which the radar never reported docked.
        feed(1_000L, status(16))
        assertFalse(standingCar(1_000L, 4_500L).isAlongsideStationary)
        assertTrue("the hold docks it", feed(5_000L, status(2))!!.vehicles.single().isAlongsideStationary)
        statusUntil(5_500L, 10_000L, 2)
        assertTrue("pruned", decoder.feed(status(2))!!.vehicles.isEmpty())
        assertEquals(1, passed())
    }

    @Test
    fun aCarStoppedBehindAStoppedRiderIsNotPassedWhenTheRiderSetsOffWhileTheDecoderHoldsIt() {
        feed(1_000L, status(2))
        for ((i, ry) in listOf(200, 160, 120, 80, 40).withIndex()) {
            feed(1_000L + i * 100L, packet(target(2, rangeY = ry, speedHalf = -8)))
        }
        for (t in 1_500L..3_000L step 100L) feed(t, packet(target(2, rangeY = 20)))
        statusUntil(3_500L, 8_500L, 12)
        assertTrue("pruned", decoder.feed(status(12))!!.vehicles.isEmpty())
        assertEquals(1, acc.snapshot().overtakesTotal)
        assertEquals(0, passed())
    }

    @Test
    fun aCarLostBesideAMovingRiderIsPassed() {
        feed(1_000L, status(20))
        for ((i, ry) in listOf(200, 160, 120, 80, 40, 20).withIndex()) {
            feed(1_000L + i * 100L, packet(target(3, rangeY = ry, speedHalf = -8)))
        }
        for (t in 1_600L..2_600L step 100L) feed(t, packet())
        assertEquals(1, passed())
    }

    @Test
    fun aCarHeardTwiceInOneMillisecondIsJudgedOnTheLaterReading() {
        feed(1_000L, status(20))
        for ((i, ry) in listOf(200, 160, 120, 80, 40).withIndex()) {
            feed(1_000L + i * 100L, packet(target(3, rangeY = ry, speedHalf = -8)))
        }
        feed(1_400L, packet(target(3, rangeY = 20, speedHalf = -8)))
        for (t in 1_500L..2_400L step 100L) feed(t, packet())
        assertTrue("pruned", decoder.feed(packet())!!.vehicles.isEmpty())
        assertEquals(1, passed())
    }

    @Test
    fun aCarLostBesideAMovingRiderIsPassedThoughTheRiderStopsWhileTheDecoderHoldsIt() {
        feed(1_000L, status(20))
        for ((i, ry) in listOf(200, 160, 120, 80, 40).withIndex()) {
            feed(1_000L + i * 100L, packet(target(4, rangeY = ry, rangeX = 15, speedHalf = -8)))
        }
        for (t in 1_500L..2_000L step 100L) feed(t, packet(target(4, rangeY = 20, rangeX = 15)))
        statusUntil(2_500L, 3_500L, 2)
        assertTrue("the hold docks it", feed(4_000L, status(2))!!.vehicles.single().isAlongsideStationary)
        statusUntil(4_500L, 7_500L, 2)
        assertTrue("pruned", decoder.feed(status(2))!!.vehicles.isEmpty())
        assertEquals(1, passed())
    }

    /** A car first seen 5 m back, 2.5 s after the decoder started, heard at 5,
     *  4 and 3 m closing at [speedHalf] half metres per second, then held
     *  until pruned beside a rider at 5 m/s. */
    private fun bornCloseThenLost(speedHalf: Int, pruneBy: Long) {
        feed(1_000L, status(20))
        for ((i, ry) in listOf(50, 40, 30).withIndex()) {
            val v = feed(3_500L + i * 100L, packet(target(5, rangeY = ry, rangeX = 15, speedHalf = speedHalf)))!!.vehicles.single()
            assertTrue("a birth the ghost filter reads", v.bornInformative && v.bornDistanceM == 5)
        }
        statusUntil(4_200L, pruneBy, 20)
        assertTrue("pruned", decoder.feed(status(20))!!.vehicles.isEmpty())
        assertEquals(1, acc.snapshot().overtakesTotal)
    }

    @Test
    fun aCarFirstSeenCloseThatNeverClosedIsNotPassed() {
        bornCloseThenLost(speedHalf = 0, pruneBy = 9_200L)
        assertEquals(0, passed())
    }

    @Test
    fun aCarFirstSeenCloseThatClosedIsPassed() {
        bornCloseThenLost(speedHalf = -6, pruneBy = 4_700L)
        assertEquals(1, passed())
    }
}
