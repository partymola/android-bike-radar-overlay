// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end decide() semantics of the experimental ghost-beep filter
 * (`ghostGateEnabled`). The safety contract pinned here:
 *  - flag off = shipped behaviour, byte-identical;
 *  - suppression is BEEP-ONLY: the all-clear presence gate and the
 *    urgent path never change;
 *  - admission re-delivers the cue at the track's current tier.
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
    fun `flag off - born-close ghost beeps exactly as shipped`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(ghost()), alertMax, c.tick())
        val ev = d.decide(listOf(ghost()), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test
    fun `flag on - born-close ghost is silenced`() {
        val d = AlertDecider()
        val c = Clock()
        repeat(20) {
            val ev = d.decide(listOf(ghost()), alertMax, c.tick(), ghostGateEnabled = true)
            assertEquals(AlertDecider.Event.None, ev)
        }
    }

    @Test
    fun `flag on - born-far car beeps exactly as shipped`() {
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
        d.decide(listOf(car), alertMax, c.tick(), ghostGateEnabled = true)
        val ev = d.decide(listOf(car), alertMax, c.tick(), ghostGateEnabled = true)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test
    fun `flag on - uninformative birth passes through - reacquired follower keeps its re-anchor beep`() {
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
        d.decide(listOf(reborn), alertMax, c.tick(), ghostGateEnabled = true)
        val ev = d.decide(listOf(reborn), alertMax, c.tick(), ghostGateEnabled = true)
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
                d.decide(listOf(ghost(speedMs = -0.5f)), alertMax, c.tick(), ghostGateEnabled = true),
            )
        }
        // ...then it genuinely starts closing (2 clean frames at >= 2.5 m/s):
        d.decide(listOf(ghost(speedMs = -3f)), alertMax, c.tick(), ghostGateEnabled = true)
        val ev = d.decide(listOf(ghost(speedMs = -3f)), alertMax, c.tick(), ghostGateEnabled = true)
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
        d.decide(listOf(car), alertMax, c.tick(), ghostGateEnabled = true)
        assertTrue(
            d.decide(listOf(car), alertMax, c.tick(), ghostGateEnabled = true)
                is AlertDecider.Event.Beep,
        )
        // Real car gone; a suppressed ghost remains physically behind.
        // Clear must NOT fire while it is present, however long we wait.
        repeat(60) {
            val ev = d.decide(listOf(ghost()), alertMax, c.tick(), ghostGateEnabled = true)
            assertEquals(AlertDecider.Event.None, ev)
        }
        // Ghost vanishes -> the deferred Clear fires after the grace.
        var cleared = false
        repeat(40) {
            if (d.decide(emptyList(), alertMax, c.tick(), ghostGateEnabled = true)
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
                ghostGateEnabled = true,
            )
            if (ev is AlertDecider.Event.UrgentApproach) urgent = true
        }
        assertTrue(urgent)
    }

    @Test
    fun `off-axis absurd trigger is vetoed - raw lateral beyond 10 m`() {
        val d = AlertDecider()
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
        repeat(10) {
            val ev = d.decide(listOf(parallelStreet), alertMax, c.tick(), ghostGateEnabled = true)
            assertEquals(AlertDecider.Event.None, ev)
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
        d.decide(listOf(unknownLateral), alertMax, c.tick(), ghostGateEnabled = true)
        val ev = d.decide(listOf(unknownLateral), alertMax, c.tick(), ghostGateEnabled = true)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test
    fun `flag off - off-axis trigger beeps as shipped`() {
        val d = AlertDecider()
        val c = Clock()
        val parallelStreet = Vehicle(
            id = 6,
            distanceM = 18,
            speedMs = -8f,
            rangeXm = 18.4f,
            rangeXmRaw = 17f,
            bornDistanceM = 60,
            bornInformative = true,
            bornAtMs = 1L,
        )
        d.decide(listOf(parallelStreet), alertMax, c.tick())
        val ev = d.decide(listOf(parallelStreet), alertMax, c.tick())
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
                ghostGateEnabled = true,
            )
            assertEquals(AlertDecider.Event.None, ev)
        }
    }

    @Test
    fun `gate log lines fire on suppression and refire`() {
        val lines = mutableListOf<String>()
        val d = AlertDecider(onGateEvent = { lines.add(it) })
        val c = Clock()
        repeat(3) { d.decide(listOf(ghost(speedMs = -0.5f)), alertMax, c.tick(), ghostGateEnabled = true) }
        d.decide(listOf(ghost(speedMs = -3f)), alertMax, c.tick(), ghostGateEnabled = true)
        d.decide(listOf(ghost(speedMs = -3f)), alertMax, c.tick(), ghostGateEnabled = true)
        assertTrue(lines.any { it.startsWith("# gate suppress tid=9") })
        assertTrue(lines.any { it.startsWith("# gate refire tid=9") })
    }

    @Test
    fun `rx veto consumes no cooldown and writes no latch`() {
        val d = AlertDecider()
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
        d.decide(listOf(parallelStreet), alertMax, c.tick(), ghostGateEnabled = true)
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(parallelStreet), alertMax, c.tick(), ghostGateEnabled = true),
        )
        // A different, in-lane car right after the veto: the veto must not
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
        d.decide(listOf(realCar), alertMax, c.tick(), ghostGateEnabled = true)
        val ev = d.decide(listOf(realCar), alertMax, c.tick(), ghostGateEnabled = true)
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
            val ev = d.decide(listOf(farther, closerGhost), alertMax, c.tick(), ghostGateEnabled = true)
            assertEquals(AlertDecider.Event.None, ev)
        }
        // Ghost dies; the admitted track speaks on its next tier edge
        // (de-escalate to its own tier, then raise into the top band).
        repeat(2) { d.decide(listOf(farther), alertMax, c.tick(), ghostGateEnabled = true) }
        val closeNow = farther.copy(distanceM = 6)
        // Escalation bypasses the cooldown, so the edge fires same-frame.
        val ev = d.decide(listOf(closeNow), alertMax, c.tick(), ghostGateEnabled = true)
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test
    fun `raw lateral exactly at the absurdity cap still beeps - veto is strictly beyond`() {
        val d = AlertDecider()
        val c = Clock()
        val edge = Vehicle(
            id = 8,
            distanceM = 18,
            speedMs = -4f,
            rangeXm = AlertDecider.RX_ABSURD_M,
            rangeXmRaw = AlertDecider.RX_ABSURD_M,
            bornDistanceM = 60,
            bornInformative = true,
            bornAtMs = 1L,
        )
        d.decide(listOf(edge), alertMax, c.tick(), ghostGateEnabled = true)
        val ev = d.decide(listOf(edge), alertMax, c.tick(), ghostGateEnabled = true)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }
}
