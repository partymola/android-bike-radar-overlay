// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end decide() semantics of the ghost-beep filter and the
 * lateral-absurdity veto. The safety contract pinned here:
 *  - born-close suppression is BEEP-ONLY: the all-clear presence gate and
 *    the urgent path never change;
 *  - admission re-delivers the cue at the track's current tier;
 *  - an off-road target leaves the close set, so it can neither cue nor
 *    stand in front of a real car, but it still holds back the all-clear.
 */
class AlertDeciderGhostGateTest {

    private val alertMax = 21

    private class Clock(start: Long = 0L, val dtMs: Long = 100L) {
        var now: Long = start
        fun tick(): Long {
            val t = now
            now += dtMs
            return t
        }
        fun jump(deltaMs: Long) {
            now += deltaMs
        }
    }

    /** A ghost: born at close range, informative birth, ~zero closing. */
    private fun ghost(id: Int = 9, distanceM: Int = 6, speedMs: Float = -0.5f) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        bornDistanceM = 6,
        bornInformative = true,
        bornAtMs = 1L,
    )

    @Test
    fun `born-close ghost is silenced`() {
        val d = AlertDecider()
        val c = Clock()
        repeat(20) {
            val ev = d.decide(listOf(ghost()), alertMax, c.tick())
            assertEquals(AlertDecider.Event.None, ev)
        }
    }

    @Test
    fun `born-far car beeps exactly as shipped`() {
        val d = AlertDecider()
        val c = Clock()
        val car = Vehicle(
            id = 3,
            distanceM = 18,
            speedMs = -4f,
            bornDistanceM = 45,
            bornInformative = true,
            bornAtMs = 1L,
        )
        d.decide(listOf(car), alertMax, c.tick())
        val ev = d.decide(listOf(car), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test
    fun `uninformative birth passes through - reacquired follower keeps its re-anchor beep`() {
        val d = AlertDecider()
        val c = Clock()
        val reborn = Vehicle(
            id = 5,
            distanceM = 8,
            speedMs = 0f,
            bornDistanceM = 8,
            bornInformative = false,
            bornAtMs = 1L,
        )
        d.decide(listOf(reborn), alertMax, c.tick())
        val ev = d.decide(listOf(reborn), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), ev)
    }

    @Test
    fun `admission delivers the cue at the current tier`() {
        val d = AlertDecider()
        val c = Clock()
        // Ghost-profile car: suppressed while not closing...
        repeat(5) {
            assertEquals(
                AlertDecider.Event.None,
                d.decide(listOf(ghost(speedMs = -0.5f)), alertMax, c.tick()),
            )
        }
        // ...then it genuinely starts closing (2 clean frames at >= 2.5 m/s):
        d.decide(listOf(ghost(speedMs = -3f)), alertMax, c.tick())
        val ev = d.decide(listOf(ghost(speedMs = -3f)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test
    fun `suppressed ghost still blocks the all-clear - presence gate untouched`() {
        val d = AlertDecider()
        val c = Clock()
        // Open a real episode, then let the real car overtake.
        val car = Vehicle(
            id = 2,
            distanceM = 15,
            speedMs = -4f,
            bornDistanceM = 40,
            bornInformative = true,
            bornAtMs = 1L,
        )
        d.decide(listOf(car), alertMax, c.tick())
        assertTrue(
            d.decide(listOf(car), alertMax, c.tick())
                is AlertDecider.Event.Beep,
        )
        // Real car gone; a suppressed ghost remains physically behind.
        // Clear must NOT fire while it is present, however long we wait.
        repeat(60) {
            val ev = d.decide(listOf(ghost()), alertMax, c.tick())
            assertEquals(AlertDecider.Event.None, ev)
        }
        // Ghost vanishes -> the deferred Clear fires after the grace.
        var cleared = false
        repeat(40) {
            if (d.decide(emptyList(), alertMax, c.tick())
                == AlertDecider.Event.Clear
            ) {
                cleared = true
            }
        }
        assertTrue(cleared)
    }

    @Test
    fun `urgent path bypasses the gate - born-close fast closer still fires urgent`() {
        val d = AlertDecider()
        val c = Clock()
        // Rider stationary (speed 0 beyond the dwell) with a born-close
        // track closing at urgent grade.
        val threat = Vehicle(
            id = 4,
            distanceM = 6,
            speedMs = -6.5f,
            bornDistanceM = 7,
            bornInformative = true,
            bornAtMs = 1L,
        )
        var urgent = false
        repeat(30) {
            val ev = d.decide(
                listOf(threat),
                alertMax,
                c.tick(),
                bikeSpeedMs = 0f,
            )
            if (ev is AlertDecider.Event.UrgentApproach) urgent = true
        }
        assertTrue(urgent)
    }

    @Test
    fun `off-axis absurd trigger is vetoed - raw lateral beyond 10 m`() {
        for (side in floatArrayOf(1f, -1f)) {
            val lines = mutableListOf<String>()
            val d = AlertDecider(onGateEvent = { lines.add(it) })
            val c = Clock()
            val parallelStreet = Vehicle(
                id = 6,
                distanceM = 10,
                speedMs = -8f,
                rangeXm = side * 18.4f,
                rangeXmRaw = side * 17f,
                bornDistanceM = 60,
                bornInformative = true,
                bornAtMs = 1L,
            )
            repeat(10) {
                val ev = d.decide(listOf(parallelStreet), alertMax, c.tick())
                assertEquals("side $side", AlertDecider.Event.None, ev)
            }
            assertTrue("the veto must reach the capture log, got $lines", lines.any { it.startsWith("# gate rx-veto tid=6") })
        }
    }

    @Test
    fun `off-axis veto fails open on lateral-unknown frames`() {
        val d = AlertDecider()
        val c = Clock()
        val unknownLateral = Vehicle(
            id = 6, distanceM = 18, speedMs = -8f,
            rangeXm = 18.4f, rangeXmRaw = 17f, lateralUnknown = true,
            bornDistanceM = 60, bornInformative = true, bornAtMs = 1L,
        )
        d.decide(listOf(unknownLateral), alertMax, c.tick())
        val ev = d.decide(listOf(unknownLateral), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test
    fun `turn-manufactured closing does not admit - suppressed through the turn`() {
        val d = AlertDecider()
        val c = Clock()
        // Mid-turn ghost showing 3 m/s of geometric closing: below the
        // urgent-grade any-state bar, and TURNING frames are tainted.
        repeat(30) {
            val ev = d.decide(
                listOf(ghost(speedMs = -3f)),
                alertMax,
                c.tick(),
                turnState = TurnStateDecider.State.TURNING,
            )
            assertEquals(AlertDecider.Event.None, ev)
        }
    }

    @Test
    fun `gate log lines fire on suppression and refire`() {
        val lines = mutableListOf<String>()
        val d = AlertDecider(onGateEvent = { lines.add(it) })
        val c = Clock()
        repeat(3) { d.decide(listOf(ghost(speedMs = -0.5f)), alertMax, c.tick()) }
        d.decide(listOf(ghost(speedMs = -3f)), alertMax, c.tick())
        d.decide(listOf(ghost(speedMs = -3f)), alertMax, c.tick())
        assertTrue(lines.any { it.startsWith("# gate suppress tid=9") })
        assertTrue(lines.any { it.startsWith("# gate refire tid=9") })
    }

    @Test
    fun `an off-road target does not delay the next car's cue`() {
        // No tier-raise bypass, so a cooldown the off-road target started
        // would hold the next car's first cue back.
        val d = AlertDecider(escalationBypass = EscalationCooldownBypass.NONE)
        val c = Clock()
        val parallelStreet = Vehicle(
            id = 6,
            distanceM = 10,
            speedMs = -8f,
            rangeXm = 18.4f,
            rangeXmRaw = 17f,
            bornDistanceM = 60,
            bornInformative = true,
            bornAtMs = 1L,
        )
        d.decide(listOf(parallelStreet), alertMax, c.tick())
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(parallelStreet), alertMax, c.tick()),
        )
        // A different, in-lane car right after: the off-road target must not
        // have advanced the beep cooldown, so this cue lands the moment
        // its own sustain is met.
        val realCar = Vehicle(
            id = 7,
            distanceM = 10,
            speedMs = -4f,
            bornDistanceM = 45,
            bornInformative = true,
            bornAtMs = 1L,
        )
        d.decide(listOf(realCar), alertMax, c.tick())
        val ev = d.decide(listOf(realCar), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), ev)
    }

    @Test
    fun `still-gated closer ghost delays a farther admitted track's cue - never a false cue`() {
        val d = AlertDecider()
        val c = Clock()
        val farther = Vehicle(
            id = 11,
            distanceM = 9,
            speedMs = -3f,
            bornDistanceM = 10,
            bornInformative = true,
            bornAtMs = 1L,
        )
        val closerGhost = ghost(id = 12, distanceM = 4, speedMs = -0.5f)
        // Both present from the start: the ghost is the closest track, so
        // everything stays silent - the admitted track's cue is delayed,
        // never misattributed to the ghost.
        repeat(10) {
            val ev = d.decide(listOf(farther, closerGhost), alertMax, c.tick())
            assertEquals(AlertDecider.Event.None, ev)
        }
        // Ghost dies; the admitted track speaks on its next tier edge
        // (de-escalate to its own tier, then raise into the top band).
        repeat(2) { d.decide(listOf(farther), alertMax, c.tick()) }
        val closeNow = farther.copy(distanceM = 6)
        // Escalation bypasses the cooldown, so the edge fires same-frame.
        val ev = d.decide(listOf(closeNow), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test
    fun `traffic in the lanes either side still beeps - the veto only rejects the impossible`() {
        // 3.7 m is one UK lane over, 7 m the far side of a two-lane road, on
        // both sides of the bike.
        for (rawRx in floatArrayOf(3.7f, 7f, -3.7f, -7f)) {
            val d = AlertDecider()
            val c = Clock()
            val otherLane = Vehicle(
                id = 8,
                distanceM = 18,
                speedMs = -4f,
                rangeXm = rawRx,
                rangeXmRaw = rawRx,
                bornDistanceM = 60,
                bornInformative = true,
                bornAtMs = 1L,
            )
            d.decide(listOf(otherLane), alertMax, c.tick())
            assertEquals(
                "raw lateral $rawRx m",
                AlertDecider.Event.Beep(1),
                d.decide(listOf(otherLane), alertMax, c.tick()),
            )
        }
    }

    /** The second-frame event for a born-far car at [rawRx] of raw lateral
     *  and [correctedRx] after the mount offset. */
    private fun tierBeepAt(rawRx: Float, correctedRx: Float = rawRx): AlertDecider.Event {
        val d = AlertDecider()
        val c = Clock()
        val v = Vehicle(
            id = 8,
            distanceM = 18,
            speedMs = -4f,
            rangeXm = correctedRx,
            rangeXmRaw = rawRx,
            bornDistanceM = 60,
            bornInformative = true,
            bornAtMs = 1L,
        )
        d.decide(listOf(v), alertMax, c.tick())
        return d.decide(listOf(v), alertMax, c.tick())
    }

    @Test
    fun `the absurdity cap sits at 10 m of raw lateral - exactly 10 beeps, just past it does not`() {
        assertEquals(AlertDecider.Event.Beep(1), tierBeepAt(10f))
        assertEquals(AlertDecider.Event.Beep(1), tierBeepAt(-10f))
        assertEquals(AlertDecider.Event.None, tierBeepAt(10.1f))
        assertEquals(AlertDecider.Event.None, tierBeepAt(-10.1f))
    }

    @Test
    fun `the cap reads the sensor's own lateral, not the mount-corrected one`() {
        // A mount-offset setting must not move a car on or off the road.
        assertEquals(AlertDecider.Event.Beep(1), tierBeepAt(rawRx = 9.5f, correctedRx = 10.5f))
        assertEquals(AlertDecider.Event.None, tierBeepAt(rawRx = 10.5f, correctedRx = 9.5f))
    }

    /** Just past the absurdity cap; at 2 m back, 10.7 m of true range, so it
     *  stands in front of a real car for most of an approach. Shaped to
     *  exercise the mechanism, not to reproduce field geometry. */
    private fun offRoad(distanceM: Int = 2, bornAtMs: Long = 1L) = Vehicle(
        id = 85,
        distanceM = distanceM,
        speedMs = -1f,
        rangeXm = 10.5f,
        rangeXmRaw = 10.5f,
        bornAtMs = bornAtMs,
    )

    private fun car(distanceM: Int, rawRx: Float = 0f) = Vehicle(id = 7, distanceM = distanceM, speedMs = -5f, rangeXm = rawRx, rangeXmRaw = rawRx)

    /** Walks the whole ladder: tier 1 from 21 m, tier 2 from 14 m, tier 3 from 7 m. */
    private val approach: List<List<Vehicle>> = (0..22).map { listOf(car(25 - it)) } + List(30) { emptyList() }

    private fun events(
        frames: List<List<Vehicle>>,
        gateLines: MutableList<String> = mutableListOf(),
        closingCeilingMs: Float? = AlertDecider.DEFAULT_CLOSING_CEILING_MS,
    ): List<AlertDecider.Event> {
        val d = AlertDecider(onGateEvent = { gateLines.add(it) })
        val c = Clock()
        // Nearest first, as the decoder's snapshot orders them.
        return frames.map { d.decide(it.sortedBy { v -> v.distanceM }, alertMax, c.tick(), closingCeilingMs = closingCeilingMs) }
    }

    @Test
    fun `an off-road target closer than a real car changes nothing about the real car's beeps`() {
        // The audio voices only the closest target, and the off-road one is
        // nearer by true range until the car is inside 10.7 m. The veto must
        // not depend on the closing-speed ceiling, which a rider can turn off.
        val withOffRoad = approach.mapIndexed { i, vs -> if (i <= 22) vs + offRoad() else vs }
        for (ceiling in listOf(AlertDecider.DEFAULT_CLOSING_CEILING_MS, null)) {
            val alone = events(approach, closingCeilingMs = ceiling)
            assertEquals(
                "the real car must walk the whole ladder on its own, ceiling $ceiling",
                listOf(AlertDecider.Event.Beep(1), AlertDecider.Event.Beep(2), AlertDecider.Event.Beep(3)),
                alone.filterIsInstance<AlertDecider.Event.Beep>(),
            )
            assertEquals("ceiling $ceiling", alone, events(withOffRoad, closingCeilingMs = ceiling))
        }
    }

    @Test
    fun `one off-road reading does not silence a real car for the rest of its approach`() {
        assertTrue("the real car must beep on its own", events(approach).count { it is AlertDecider.Event.Beep } >= 2)
        // Frame 8 sits at 17 m, mid tier 1. Frame 10 sits at 15 m, the frame
        // before the tier-2 edge: the car keeps its sustain through one
        // off-road frame, so its tier-2 cue is not pushed a frame later.
        for (frame in listOf(8, 10)) {
            val spiked = approach.mapIndexed { i, vs -> if (i == frame) listOf(car(25 - i, rawRx = 12f)) else vs }
            assertEquals("spike at frame $frame", events(approach), events(spiked))
        }
    }

    @Test
    fun `an off-road fast closer does not change the urgent cue for a real one behind a stopped rider`() {
        fun run(withOffRoad: Boolean): List<AlertDecider.Event> {
            val d = AlertDecider()
            val c = Clock()
            val stopped = List(10) { emptyList<Vehicle>() }
            val closing = listOf(20, 19, 17, 16, 15, 13, 12, 11, 10, 8, 7, 6).map { at ->
                val real = Vehicle(id = 7, distanceM = at, speedMs = -12f)
                val off = Vehicle(id = 85, distanceM = 5, speedMs = -12f, rangeXm = 10.5f, rangeXmRaw = 10.5f)
                (if (withOffRoad) listOf(real, off) else listOf(real)).sortedBy { it.distanceM }
            }
            return (stopped + closing).map { d.decide(it, alertMax, c.tick(), bikeSpeedMs = 0f) }
        }
        val alone = run(withOffRoad = false)
        val urgent = alone.filterIsInstance<AlertDecider.Event.UrgentApproach>()
        assertTrue("the real car must fire the urgent cue on its own", urgent.isNotEmpty())
        assertTrue(urgent.all { it.triggerTid == 7 })
        assertEquals(alone, run(withOffRoad = true))
    }

    @Test
    fun `a target coming onto the road from off it is cued`() {
        val frames = List(5) { listOf(car(18, rawRx = 12f)) } + List(5) { listOf(car(18, rawRx = 3f)) }
        val all = events(frames)
        assertEquals(AlertDecider.Event.Beep(1), all.firstOrNull { it != AlertDecider.Event.None })
        assertEquals("cued once its own sustain is met on the road", 6, all.indexOfFirst { it is AlertDecider.Event.Beep })
    }

    @Test
    fun `an off-road target passing the rider does not re-sound a real car's tier`() {
        // A real car already told at tier 3; the off-road target was never voiced.
        val together = List(12) { listOf(car(5), offRoad()) }
        val passed = List(12) { listOf(car(5), offRoad(distanceM = 1).copy(isBehind = true)) }
        assertEquals(listOf(AlertDecider.Event.Beep(3)), events(together + passed).filterIsInstance<AlertDecider.Event.Beep>())
    }

    @Test
    fun `an off-road target alone leaves no all-clear`() {
        val frames = List(10) { listOf(offRoad()) } + List(30) { emptyList() }
        assertEquals(emptyList<AlertDecider.Event>(), events(frames).filter { it != AlertDecider.Event.None })
    }

    @Test
    fun `an off-road target still behind the rider holds back the all-clear`() {
        val real = (0..18).map { listOf(Vehicle(id = 7, distanceM = 20 - it, speedMs = -5f)) }
        val offRoadStays = List(40) { listOf(offRoad()) }
        val all = events(real + offRoadStays + List(30) { emptyList() })
        val clearAt = all.indexOfFirst { it == AlertDecider.Event.Clear }
        assertTrue("expected a beep for the real car", all.any { it is AlertDecider.Event.Beep })
        assertTrue("the all-clear must wait for the off-road target to go, fired at frame $clearAt", clearAt >= real.size + offRoadStays.size)
    }

    @Test
    fun `each off-road track is logged once at its first frame in range, and a recycled id again`() {
        val lines = mutableListOf<String>()
        fun run(bornAtMs: Long) = listOf(20, 18, 16, 14).map { listOf(offRoad(distanceM = it, bornAtMs = bornAtMs)) }
        events(run(bornAtMs = 1_000L) + run(bornAtMs = 9_000L), lines)
        assertEquals(
            List(2) { "# gate rx-veto tid=85 d=20 raw_rx=10.5" },
            lines.filter { it.startsWith("# gate rx-veto ") },
        )
    }
}
