// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A gap of more than 800 ms between radar frames reported nothing, so the
 * floor's lapse counts again from the frame that ends it. With no movement
 * signal (no radar speed, no eBike) a lower count for another car then needs
 * 2 s of frames without the car heard. The stationary dwells count across a
 * gap, deliberately.
 */
class AlertDeciderFrameGapTest {

    private val alertMax = 21 // bands: 3 within 7 m, 2 within 14 m, 1 within 21 m

    private fun car(id: Int, d: Int, isBehind: Boolean = false) = Vehicle(id = id, distanceM = d, speedMs = -2f, isBehind = isBehind, bornAtMs = id.toLong())

    private val none = AlertDecider.Event.None

    /** Car 1 heard at 3, car 2 at band 2 behind it, frames every 100 ms to t=400. */
    private fun heardCarWithOneBehind(): AlertDecider {
        val d = AlertDecider()
        val events = (0L..400L step 100L).map { d.decide(listOf(car(1, 5), car(2, 12)), alertMax, it) }
        assertEquals(listOf(none, AlertDecider.Event.Beep(3), none, none, none), events)
        return d
    }

    private fun AlertDecider.carBehindAlone(times: Iterable<Long>) = times.map { decide(listOf(car(2, 12)), alertMax, it) }

    @Test fun aTwoSecondGapDoesNotLetTheCarBehindSoundLower() {
        assertEquals(listOf(none), heardCarWithOneBehind().carBehindAlone(listOf(2_400L)))
    }

    @Test fun aFiveSecondGapDoesNotLetTheCarBehindSoundLower() {
        assertEquals(listOf(none), heardCarWithOneBehind().carBehindAlone(listOf(5_400L)))
    }

    @Test fun aGapJustShortOfTheLapseHoldsTheCarBehindToo() {
        assertEquals(listOf(none), heardCarWithOneBehind().carBehindAlone(listOf(2_399L)))
    }

    @Test fun afterAGapTheCarBehindSoundsLowerOnceTwoSecondsOfFramesHavePassed() {
        // The gap ends at 2400: silent to 4300, Beep(2) at 4400.
        val events = heardCarWithOneBehind().carBehindAlone(2_400L..4_400L step 100L)
        assertEquals(List(20) { none } + AlertDecider.Event.Beep(2), events)
    }

    @Test fun aGapOfExactlyTheBoundDoesNotRestartTheLapse() {
        // Car 1 last seen at 400; frames to 1600, then 800 ms with none. The
        // lapse runs out at 2400 as it would on frames.
        val events = heardCarWithOneBehind().carBehindAlone((500L..1_600L step 100L) + 2_400L)
        assertEquals(List(12) { none } + AlertDecider.Event.Beep(2), events)
    }

    @Test fun aGapOneMillisecondOverTheBoundRestartsTheLapse() {
        val d = heardCarWithOneBehind()
        assertEquals(List(12) { none }, d.carBehindAlone(500L..1_600L step 100L))
        // Restarted at 2401, so Beep(2) waits for 4401.
        val events = d.carBehindAlone(listOf(2_401L) + (2_501L..4_401L step 100L))
        assertEquals(List(20) { none } + AlertDecider.Event.Beep(2), events)
    }

    @Test fun aGapWithTheFloorNotRaisedChangesNothing() {
        val d = heardCarWithOneBehind()
        // Car 1 draws level, which lifts the floor, and car 2 is announced.
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 1, isBehind = true), car(2, 12)), alertMax, 900L))
        // Car 2 passes too, and after a gap car 3 is heard at its own band.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(2, 1, isBehind = true)), alertMax, 1_000L))
        val events = (4_000L..4_100L step 100L).map { d.decide(listOf(car(3, 18)), alertMax, it) }
        assertEquals(listOf(none, AlertDecider.Event.Beep(1)), events)
    }

    @Test fun aPassAfterAGapStillReleasesTheFloorAtOnce() {
        val d = heardCarWithOneBehind()
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 1, isBehind = true), car(2, 12)), alertMax, 2_400L))
    }

    @Test fun theSameCarCloserAfterAGapStillBeepsHigher() {
        val d = AlertDecider()
        val events = (0L..400L step 100L).map { d.decide(listOf(car(1, 12)), alertMax, it) }
        assertEquals(listOf(none, AlertDecider.Event.Beep(2), none, none, none), events)
        assertEquals(AlertDecider.Event.Beep(3), d.decide(listOf(car(1, 5)), alertMax, 2_400L))
    }

    // ── through the real decoders ────────────────────────────────────────

    /** One V2 target struct: rangeY [d] m dead behind, closing [speedMs]. */
    private fun v2Target(tid: Int, d: Int, speedMs: Float): ByteArray {
        val packed = ((d * 10) and 0x1FFF) shl 11
        return byteArrayOf(
            tid.toByte(),
            RadarV2Decoder.CLASS_MODERATE.toByte(),
            (packed and 0xFF).toByte(),
            ((packed shr 8) and 0xFF).toByte(),
            ((packed shr 16) and 0xFF).toByte(),
            0,
            0,
            (speedMs / 0.5f).toInt().toByte(),
            RadarV2Decoder.LATERAL_VELOCITY_SENTINEL.toByte(),
        )
    }

    private fun v2Frame(vararg targets: ByteArray): ByteArray = byteArrayOf(0, 0) + targets.fold(ByteArray(0)) { a, b -> a + b }

    @Test fun aGapOnAV2StreamWithNoSpeedHoldsTheCarBehindBelowTheFloor() {
        // No device-status frame, so no rider speed, and no eBike. Both cars
        // are moving, so the decoder drops them across a 2 s stall; car 1
        // returns as a new track and is heard again at 3.
        var now = 0L
        val decoder = RadarV2Decoder(nowMs = { now })
        val d = AlertDecider()
        val beeps = mutableListOf<Int>()
        fun feed(t: Long, packet: ByteArray) {
            now = t
            val state = decoder.feed(packet) ?: return
            val ev = d.decide(state.vehicles, alertMax, t, bikeSpeedMs = state.bikeSpeedMs)
            if (ev is AlertDecider.Event.Beep) beeps += ev.count
        }
        for (t in 0L..500L step 100L) feed(t, v2Frame(v2Target(1, 5, -2f), v2Target(2, 12, -2f)))
        feed(2_500L, v2Frame(v2Target(2, 12, -2f)))
        feed(2_600L, v2Frame(v2Target(1, 5, -2f), v2Target(2, 12, -2f)))
        assertEquals(listOf(3, 3), beeps)
    }

    /** One legacy threat packet: each pair is (track id, metres). */
    private fun v1Frame(vararg tracks: Pair<Int, Int>): ByteArray = byteArrayOf(0x02) +
        tracks.fold(ByteArray(0)) { a, (id, d) -> a + byteArrayOf((id or 0x80).toByte(), d.toByte(), 0) }

    private fun legacyBeepsAcrossAGap(gapMs: Long): List<Int> {
        var now = 0L
        val decoder = RadarV1Decoder(nowMs = { now })
        val d = AlertDecider()
        val beeps = mutableListOf<Int>()
        fun feed(t: Long, packet: ByteArray) {
            now = t
            val state = decoder.feed(packet) ?: return
            val ev = d.decide(state.vehicles, alertMax, t, bikeSpeedMs = state.bikeSpeedMs)
            if (ev is AlertDecider.Event.Beep) beeps += ev.count
        }
        for (t in 0L..500L step 100L) feed(t, v1Frame(1 to 5, 2 to 12))
        feed(500L + gapMs, v1Frame(2 to 12))
        return beeps
    }

    @Test fun aGapOnTheLegacyStreamLongerThanItsTrackLifetimeHoldsTheCarBehindBelowTheFloor() {
        // The decoder keeps a track 2 s, so a longer stall drops car 1 first.
        assertEquals(listOf(3), legacyBeepsAcrossAGap(2_001L))
    }

    @Test fun aGapOnTheLegacyStreamOfExactlyItsTrackLifetimeHoldsTheFloor() {
        assertEquals(listOf(3), legacyBeepsAcrossAGap(2_000L))
    }

    // ── the stationary dwells count across a gap, by design ─────────────

    @Test fun theStationaryDwellCountsAcrossAGapByDesign() {
        val d = AlertDecider()
        d.decide(emptyList(), alertMax, 0L, bikeSpeedMs = 3f)
        d.decide(listOf(car(1, 12)), alertMax, 100L, bikeSpeedMs = 0f)
        // Stopped either side of a 2 s stall: the dwell is complete and the beep held.
        assertEquals(none, d.decide(listOf(car(1, 12)), alertMax, 2_100L, bikeSpeedMs = 0f))
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 12)), alertMax, 2_200L, bikeSpeedMs = 3f))
    }

    @Test fun theUrgentMiniDwellCountsAcrossAGapByDesign() {
        val d = AlertDecider()
        d.decide(emptyList(), alertMax, 0L, bikeSpeedMs = 3f)
        val fast = Vehicle(id = 1, distanceM = 12, speedMs = -7f)
        d.decide(listOf(fast), alertMax, 100L, bikeSpeedMs = 0f)
        // Stopped either side of a 900 ms stall, past the 800 ms bound: the
        // stationary path (6 m/s floor) is open.
        val ev = d.decide(listOf(fast), alertMax, 1_000L, bikeSpeedMs = 0f)
        assertTrue("got $ev", ev is AlertDecider.Event.UrgentApproach && !ev.viaMovingPath)
    }
}
