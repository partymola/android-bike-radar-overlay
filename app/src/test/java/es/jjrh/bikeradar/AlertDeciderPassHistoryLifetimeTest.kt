// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a track's lateral history outlives its last MEASURED frame.
 *
 * The radar stops reporting lateral for a track it is still reporting (the
 * `rxBits == 0` sentinel), and those frames carry a held value rather than a
 * measurement, so they are correctly kept out of the fit. The history's
 * lifetime must not be measured from them either way: a live track judged on a
 * fit that has silently expired is judged on nothing, and the predicted-pass
 * veto fails OPEN.
 *
 * Two reported false urgent cues came from exactly that, both firing in the
 * first frames past `URGENT_PASS_HISTORY_RESET_MS` after their last measured
 * lateral, at 1537 ms and 1617 ms, each a phantom track the fit had already
 * judged a wide pass.
 */
class AlertDeciderPassHistoryLifetimeTest {

    private val alertMax = 21

    private fun sideCar(distanceM: Int, speedMs: Float, rangeXm: Float, lateralUnknown: Boolean = false, bornAtMs: Long = 0L) = Vehicle(id = 1, distanceM = distanceM, speedMs = speedMs, rangeXm = rangeXm, lateralUnknown = lateralUnknown, bornAtMs = bornAtMs)

    private class Clock(var now: Long = 0L, val dtMs: Long = 100L) {
        fun tick(): Long {
            val t = now
            now += dtMs
            return t
        }
        fun jump(deltaMs: Long) {
            now += deltaMs
        }
    }

    private fun stationaryDecider(c: Clock): AlertDecider {
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        return d
    }

    /** A measured approach holding a steady 3 m offset: a confident, committed
     *  side pass, which is what the veto is for. */
    private fun measuredSidePassApproach(c: Clock, d: AlertDecider) {
        for (dist in intArrayOf(30, 28, 26, 24, 22)) {
            d.decide(listOf(sideCar(dist, -2f, 3f)), alertMax, c.tick(), bikeSpeedMs = 0f)
        }
    }

    @Test fun `a live track stays vetoed through an unknown run longer than the reset window`() {
        // The artefact. The radar keeps reporting the track and it keeps
        // qualifying on distance and closing, but every frame carries the
        // lateral-unknown sentinel. The fit is built from the measured frames
        // and needs nothing from these, so the veto must hold for as long as
        // the track is there - not for 1500 ms and then no longer.
        val c = Clock()
        val d = stationaryDecider(c)
        measuredSidePassApproach(c, d)
        // 20 frames at 100 ms is 2000 ms of unknown, comfortably past
        // URGENT_PASS_HISTORY_RESET_MS, with the track closing all the way.
        var dist = 21
        val fired = mutableListOf<AlertDecider.Event>()
        repeat(20) {
            val ev = d.decide(
                listOf(sideCar(dist, -8f, 3f, lateralUnknown = true)),
                alertMax,
                c.tick(),
                bikeSpeedMs = 0f,
            )
            if (ev is AlertDecider.Event.UrgentApproach) fired += ev
            if (dist > 6) dist -= 1
        }
        assertEquals("a committed side pass must stay vetoed while it is still there, got $fired", 0, fired.size)
    }

    @Test fun `a measured frame after a long unknown run is still judged on the earlier approach`() {
        // The other exit. The recycle clear at the top of updateLateralHistory
        // measures its gap the same way, so a track that goes unknown past the
        // window and then reports a real lateral again would have its history
        // wiped there instead - same fail-open, different line.
        val c = Clock()
        val d = stationaryDecider(c)
        measuredSidePassApproach(c, d)
        repeat(20) {
            d.decide(listOf(sideCar(12, -2f, 3f, lateralUnknown = true)), alertMax, c.tick(), bikeSpeedMs = 0f)
        }
        // Lateral comes back, still out on the same side pass.
        val ev = d.decide(listOf(sideCar(10, -8f, 3f)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `a car swinging in after a long unknown run still fires`() {
        // The over-suppression guard, and the direction that matters: keeping
        // the history alive must not make the veto permanent. The freshest
        // MEASURED sample places this car inside the rider's line, so it
        // refuses to corroborate the stale side-pass fit and the cue fires.
        val c = Clock()
        val d = stationaryDecider(c)
        measuredSidePassApproach(c, d)
        repeat(20) {
            d.decide(listOf(sideCar(12, -2f, 3f, lateralUnknown = true)), alertMax, c.tick(), bikeSpeedMs = 0f)
        }
        val ev = d.decide(listOf(sideCar(10, -8f, 0.1f)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertTrue("a car crossing into the rider's line must warn, got $ev", ev is AlertDecider.Event.UrgentApproach)
    }

    @Test fun `a fit the radar has not refreshed for long enough fails open`() {
        // Keeping the history alive for a present track must not make the veto
        // permanent. A car measured wide, that then loses lateral and changes
        // lane into the rider, would otherwise be vetoed by a frozen fit for
        // the whole approach - a suppressed warning, which is the direction
        // that matters. The radar's own blind runs top out around 2.5 s across
        // the ride corpus, so a fit unrefreshed beyond that is stale by
        // observation rather than by guess.
        val c = Clock()
        val d = stationaryDecider(c)
        measuredSidePassApproach(c, d)
        var dist = 21
        var fired = false
        // 40 frames at 100 ms is 4 s, past the unmeasured cap.
        repeat(40) {
            val ev = d.decide(
                listOf(sideCar(dist, -8f, 3f, lateralUnknown = true)),
                alertMax,
                c.tick(),
                bikeSpeedMs = 0f,
            )
            if (ev is AlertDecider.Event.UrgentApproach) fired = true
            if (dist > 6) dist -= 1
        }
        assertTrue("a fit left unrefreshed past the cap must stop vetoing", fired)
    }

    @Test fun `a recycled tid does not inherit the old car's fit inside the presence window`() {
        // The gap that identifies a recycled tid is short: the decoder prunes a
        // moving track after 800 ms, so the new car can arrive well inside the
        // presence window. Track identity is what separates them, and the
        // decoder already stamps it.
        val c = Clock()
        val d = stationaryDecider(c)
        for (dist in intArrayOf(30, 28, 26, 24, 22)) {
            d.decide(listOf(sideCar(dist, -2f, 3f, bornAtMs = 100L)), alertMax, c.tick(), bikeSpeedMs = 0f)
        }
        // Car A dies; the radar hands the id to a different car 900 ms later,
        // dead centre and closing fast. Its own history is one sample.
        c.jump(900)
        d.decide(listOf(sideCar(15, -8f, 0f, bornAtMs = 9_000L)), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(sideCar(14, -8f, 0f, bornAtMs = 9_000L)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertTrue("the new car must not be judged on the old one's geometry, got $ev", ev is AlertDecider.Event.UrgentApproach)
    }

    // The other half of this contract - a tid ABSENT past the window losing
    // its history to a recycled car - is pinned by `AlertDeciderTest`'s
    // `recycled tid after a dead gap does not inherit the old fit` and the two
    // verdict-memo tests beside it. Those drive a frameless gap, which is what
    // the lifetime now counts, so they are this change's regression witnesses.
}
