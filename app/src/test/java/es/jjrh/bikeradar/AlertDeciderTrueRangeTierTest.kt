// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.AlertDecider.Event
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Awareness tiers are scored on the target's TRUE range, not its along-axis
 * `distanceM`. These pin the consequences: a vehicle drawing abeam no longer
 * climbs the tiers as though it were closing, a car genuinely behind is at
 * most delayed by a boundary notch, and an off-axis track can never mask a
 * real one.
 *
 * `alertMax = 21` throughout, so the tier boundaries sit at 7 m and 14 m.
 */
class AlertDeciderTrueRangeTierTest {

    private val alertMax = 21

    private fun car(id: Int, distanceM: Int, rangeXm: Float = 0f, speedMs: Float = -3f) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        rangeXm = rangeXm,
        lateralPos = (rangeXm / RadarV2Decoder.LATERAL_FULL_M).coerceIn(-1f, 1f),
    )

    private class Clock(val dtMs: Long = 100L) {
        var now: Long = 0L
        fun tick(): Long {
            val t = now
            now += dtMs
            return t
        }
    }

    /** Drives [frames] through the decider and returns every non-None event. */
    private fun run(vararg frames: List<Vehicle>) = runIndexed(TierDistance.TRUE_RANGE, *frames).map { it.second }

    /**
     * As [run], but keeps each cue's frame index and lets the tier rule be
     * chosen. Which FRAME a cue lands on is often the only difference between
     * the two rules - a test that compares event lists alone would pass under
     * either, and pin nothing.
     */
    private fun runIndexed(
        mode: TierDistance,
        vararg frames: List<Vehicle>,
    ): List<Pair<Int, Event>> {
        val d = AlertDecider(tierDistance = mode)
        val c = Clock()
        return frames
            .mapIndexed { i, f -> i to d.decide(f, alertMax, c.tick()) }
            .filter { it.second != Event.None }
    }

    @Test fun `dead-behind car tiers exactly as before`() {
        // rangeXm = 0 is the overwhelmingly common case and hypot is then an
        // identity, so no behind-car cue may move.
        assertEquals(listOf(Event.Beep(1)), run(listOf(car(1, 18)), listOf(car(1, 18))))
        assertEquals(listOf(Event.Beep(2)), run(listOf(car(1, 10)), listOf(car(1, 10))))
        assertEquals(listOf(Event.Beep(3)), run(listOf(car(1, 4)), listOf(car(1, 4))))
    }

    @Test fun `a lane over delays the boundary crossing by a notch, never skips it`() {
        // hypot(7, 2) = 7.28 > 7, so a car 2 m to the side reaches tier 3 at
        // 6 m of rangeY rather than 7. The escalation is delayed, not lost -
        // the step function turns a sub-3% distance shift into a discrete
        // tier flip, which is the accepted cost of the change.
        val atBoundary = run(listOf(car(1, 7, rangeXm = 2f)), listOf(car(1, 7, rangeXm = 2f)))
        assertEquals(listOf(Event.Beep(2)), atBoundary)

        val insideIt = run(listOf(car(1, 6, rangeXm = 2f)), listOf(car(1, 6, rangeXm = 2f)))
        assertEquals(listOf(Event.Beep(3)), insideIt)
    }

    @Test fun `a car drawing abeam does not reach the top tier`() {
        // The ride signature, scaled to this alertMax: a vehicle turning
        // off holds ~9 m of lateral offset while its rangeY collapses from 14
        // to 7. Its true range never comes inside 11.4 m, so it stops at the
        // middle tier - under along-axis tiering the rangeY collapse alone
        // walked it all the way to tier 3.
        val events = run(
            listOf(car(1, 14, rangeXm = 9f)),
            listOf(car(1, 14, rangeXm = 9f)),
            listOf(car(1, 11, rangeXm = 9f)),
            listOf(car(1, 9, rangeXm = 9f)),
            listOf(car(1, 7, rangeXm = 9f)),
        )
        assertEquals(listOf(Event.Beep(1), Event.Beep(2)), events)
    }

    @Test fun `a real tailgater still gets its top tier, one frame later`() {
        // Same track shape as the ride's genuine follower: it comes in almost
        // dead behind and ends up on the rider's wheel. hypot(7, 2) = 7.28
        // holds tier 3 back on the 7 m frame; it fires on the next one.
        //
        // Both rules emit the same two cues here, so the frame index is the
        // only thing that separates them - assert on that, not on the list.
        val frames = arrayOf(
            listOf(car(2, 12, rangeXm = 2f)),
            listOf(car(2, 12, rangeXm = 2f)),
            listOf(car(2, 7, rangeXm = 2f)),
            listOf(car(2, 5, rangeXm = 2f)),
        )
        assertEquals(
            "true range must hold the top tier back to the 5 m frame",
            listOf(1 to Event.Beep(2), 3 to Event.Beep(3)),
            runIndexed(TierDistance.TRUE_RANGE, *frames),
        )
        assertEquals(
            "along-axis fires the top tier a frame earlier - the difference this pins",
            listOf(1 to Event.Beep(2), 2 to Event.Beep(3)),
            runIndexed(TierDistance.ALONG_AXIS, *frames),
        )
    }

    @Test fun `an off-axis track cannot mask a real one behind`() {
        // Selection and scoring must agree. Picking the closest by rangeY
        // would hand the audio to the ghost (5 < 6) and then score the
        // ghost's true range of 10.3 m as tier 2 - silencing the tier-3
        // escalation the car dead behind at 6.08 m has earned. Audio voices
        // the closest vehicle only, so that is a real tailgater going
        // unannounced because clutter sat beside the rider.
        val ghost = car(1, 5, rangeXm = 9f)
        val real = car(2, 6, rangeXm = 1f)
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(ghost, real), alertMax, c.tick())
        val ev = d.decide(listOf(ghost, real), alertMax, c.tick())
        assertEquals(Event.Beep(3), ev)
        // This is the divergence the capture log has to record: the nearest
        // car by along-axis distance is the ghost, the tier came from the real
        // one. Without this assertion the attribution fields could name either
        // and nothing would notice.
        assertEquals(real.id, d.lastTierTrigger?.id)
        assertEquals(6.0827f, d.lastTierDistanceM, 1e-3f)
    }

    @Test fun `an unmeasured lateral frame falls open to today's behaviour`() {
        // The decoder carries a stale lateral value across a sentinel run, so
        // honouring it would hold a real car's escalation back on data flagged
        // as not a measurement. With lateralUnknown set, a held 8 m offset must
        // score exactly as 0 m does.
        val held = car(1, 6, rangeXm = 8f).copy(lateralUnknown = true)
        val centred = car(1, 6)
        assertEquals(
            run(listOf(centred), listOf(centred)),
            run(listOf(held), listOf(held)),
        )
    }

    @Test fun `a lateral spike delays an escalation and then delivers it`() {
        // Real overtaking cars throw brief off-axis readings at close range
        // (corpus: median 1.17 s). The spike must not lose the cue - it fires
        // when the reading recovers, which is why no rangeX smoothing is
        // needed.
        val frames = arrayOf(
            listOf(car(1, 12)),
            listOf(car(1, 12)),
            listOf(car(1, 6, rangeXm = 9f)), // spike: true range 10.8, still tier 2
            listOf(car(1, 6, rangeXm = 9f)),
            listOf(car(1, 6)), // recovered
        )
        assertEquals(
            "the escalation waits for the spike to pass, then fires",
            listOf(1 to Event.Beep(2), 4 to Event.Beep(3)),
            runIndexed(TierDistance.TRUE_RANGE, *frames),
        )
        assertEquals(
            "along-axis never sees the spike, so it escalates two frames earlier",
            listOf(1 to Event.Beep(2), 2 to Event.Beep(3)),
            runIndexed(TierDistance.ALONG_AXIS, *frames),
        )
    }

    @Test fun `a spike after the top tier has fired stays silent on recovery`() {
        // The per-tid latch is cleared only on Clear, so a demote-and-recover
        // cannot re-beep. Pinning it means per-frame lateral noise can never
        // become a new source of chatter. Only the true-range rule can reach
        // this path at all - under along-axis scoring the lateral spike never
        // moves the tier, so there is nothing to recover from.
        val events = run(
            listOf(car(1, 5)),
            listOf(car(1, 5)),
            listOf(car(1, 5, rangeXm = 9f)), // demoted by the spike
            listOf(car(1, 5)), // recovered to tier 3
            listOf(car(1, 5)),
        )
        assertEquals(listOf(Event.Beep(3)), events)
    }
}
