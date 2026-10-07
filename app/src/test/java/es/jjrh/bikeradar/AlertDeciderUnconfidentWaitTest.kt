// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Experimental wait on an urgent candidate that has no confident fit and
 * is already off to one side ([AlertDecider.URGENT_UNCONFIDENT_WAIT_MS]).
 *
 * Times are literal. Unless a test says otherwise the rider is stopped from
 * t = 0, so a car first seen at 2000 is stable, and qualifying, from 2100.
 */
class AlertDeciderUnconfidentWaitTest {

    private val alertMax = 21
    private val lines = mutableListOf<String>()

    private fun decider(): AlertDecider = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L, onGateEvent = { lines += it }).also {
        it.decide(emptyList(), alertMax, 0L, bikeSpeedMs = 0f)
    }

    private fun car(
        id: Int = 1,
        d: Int,
        speedMs: Float = -8f,
        rx: Float,
        born: Long = 2_000L,
        lateralUnknown: Boolean = false,
    ) = Vehicle(id = id, distanceM = d, speedMs = speedMs, rangeXm = rx, rangeXmRaw = rx, bornAtMs = born, lateralUnknown = lateralUnknown)

    private fun AlertDecider.at(t: Long, vararg vs: Vehicle, speed: Float = 0f, wait: Boolean = true): AlertDecider.Event = decide(vs.toList(), alertMax, t, bikeSpeedMs = speed, urgentUnconfidentWaitEnabled = wait)

    private fun assertUrgent(ev: AlertDecider.Event, why: String = "") {
        assertTrue("expected UrgentApproach $why, got $ev", ev is AlertDecider.Event.UrgentApproach)
    }

    /** A car 15 m back, closing at 8 m/s, 3 m to the side, measured once. */
    private val sideCar = car(d = 15, rx = 3f)

    @Test fun `with the wait off an unconfident candidate fires on its first qualifying frame`() {
        val d = decider()
        d.at(2_000L, sideCar, wait = false)
        assertUrgent(d.at(2_100L, sideCar, wait = false))
        // Nothing is recorded either, or switching it on mid-ride would find
        // this car's wait already spent.
        assertTrue(lines.none { it.startsWith("# gate urgent-pass-wait") })
    }

    @Test fun `switching the wait off mid-wait releases the car at once`() {
        val d = decider()
        d.at(2_000L, sideCar)
        assertEquals(AlertDecider.Event.None, d.at(2_100L, sideCar))
        assertUrgent(d.at(2_200L, sideCar, wait = false))
    }

    @Test fun `a car exactly 2_5 m to the side is held`() {
        val d = decider()
        val edge = car(d = 15, rx = 2.5f)
        d.at(2_000L, edge)
        assertEquals(AlertDecider.Event.None, d.at(2_100L, edge))
        assertUrgent(d.at(2_400L, edge))
    }

    @Test fun `a car to the left is held like one to the right`() {
        val d = decider()
        val left = car(d = 15, rx = -3f)
        d.at(2_000L, left)
        assertEquals(AlertDecider.Event.None, d.at(2_100L, left))
        assertEquals(AlertDecider.Event.None, d.at(2_399L, left))
        assertUrgent(d.at(2_400L, left))
    }

    @Test fun `the wait starts when the car first qualifies, not when it is first seen`() {
        val d = decider()
        var t = 2_000L
        while (t <= 2_500L) {
            assertEquals(AlertDecider.Event.None, d.at(t, car(d = 15, speedMs = -2f, rx = 3f)))
            t += 100L
        }
        assertEquals(AlertDecider.Event.None, d.at(2_600L, sideCar))
        assertEquals(AlertDecider.Event.None, d.at(2_899L, sideCar))
        assertUrgent(d.at(2_900L, sideCar))
    }

    @Test fun `frames vetoed as too far off-axis do not spend the wait`() {
        val d = decider()
        var t = 2_000L
        while (t <= 2_500L) {
            assertEquals(AlertDecider.Event.None, d.at(t, car(d = 15, rx = 7f)))
            t += 100L
        }
        assertEquals(AlertDecider.Event.None, d.at(2_600L, sideCar))
        assertEquals(AlertDecider.Event.None, d.at(2_899L, sideCar))
        assertUrgent(d.at(2_900L, sideCar))
    }

    @Test fun `a confident fit inside the margin fires at once though the car is still 2_5 m out`() {
        val d = decider()
        val approach = listOf(2_000L to (24 to 4.0f), 2_100L to (22 to 3.6f), 2_200L to (20 to 3.2f), 2_300L to (18 to 2.9f))
        for ((t, p) in approach) {
            d.at(t, car(d = p.first, speedMs = -2f, rx = p.second))
        }
        assertUrgent(d.at(2_400L, car(d = 16, rx = 2.6f)), "on its first qualifying frame")
    }

    @Test fun `stopped for under 2 s a waiting candidate still gets its tier beep`() {
        // Intended, as on the moving path: beeps are not yet suppressed, and
        // the wait holds only the urgent cue.
        val d = decider()
        val near = car(d = 5, rx = 3f, born = 600L)
        d.at(600L, near)
        assertEquals(AlertDecider.Event.Beep(3), d.at(700L, near))
        assertEquals(AlertDecider.Event.None, d.at(999L, near))
        val ev = d.at(1_000L, near)
        assertTrue("expected a stationary-path urgent, got $ev", ev is AlertDecider.Event.UrgentApproach && !ev.viaMovingPath)
    }

    @Test fun `with the wait on an unconfident candidate is silent until 300 ms`() {
        val d = decider()
        d.at(2_000L, sideCar)
        assertEquals(AlertDecider.Event.None, d.at(2_100L, sideCar))
        assertEquals(AlertDecider.Event.None, d.at(2_399L, sideCar))
        assertUrgent(d.at(2_400L, sideCar), "once the wait runs out")
    }

    @Test fun `a fit that matures inside the wait and predicts a wide pass vetoes the cue`() {
        val d = decider()
        for ((t, dist) in listOf(2_000L to 24, 2_100L to 22, 2_200L to 20)) {
            d.at(t, car(d = dist, speedMs = -2f, rx = 3f))
        }
        val events = (0..5).map { i -> d.at(2_300L + 100L * i, car(d = 18 - 2 * i, rx = 3f)) }
        assertTrue("no urgent for a car the matured fit puts 3 m to the side: $events", events.none { it is AlertDecider.Event.UrgentApproach })
    }

    @Test fun `a fit that matures inside the wait and predicts a pass inside the margin fires at once`() {
        val d = decider()
        for ((t, pair) in listOf(2_000L to (24 to 3.6f), 2_100L to (22 to 3.2f), 2_200L to (20 to 2.9f))) {
            d.at(t, car(d = pair.first, speedMs = -2f, rx = pair.second))
        }
        assertEquals(AlertDecider.Event.None, d.at(2_300L, car(d = 18, rx = 2.6f)))
        assertUrgent(d.at(2_400L, car(d = 16, rx = 2.3f)), "on the frame the fit matures, before the wait runs out")
    }

    @Test fun `a waiting car that swings inside 2_5 m fires at once`() {
        val d = decider()
        d.at(2_000L, sideCar)
        assertEquals(AlertDecider.Event.None, d.at(2_100L, sideCar))
        assertUrgent(d.at(2_200L, car(d = 15, rx = 2.0f)))
    }

    @Test fun `the wait never covers the near-centre fail-open`() {
        val d = decider()
        val centred = car(d = 15, rx = 0.5f)
        d.at(2_000L, centred)
        assertUrgent(d.at(2_100L, centred))
    }

    @Test fun `a stale side-pass history still cannot hold a car swinging into the rider`() {
        // The fixture of AlertDeciderTest's "stale side-pass history cannot
        // veto a car swinging into the rider", with the wait on.
        val d = decider()
        var t = 2_000L
        for (dist in 30 downTo 12) {
            d.at(t, car(d = dist, speedMs = -2f, rx = 3f))
            t += 100L
        }
        val dwellD = intArrayOf(11, 10, 11, 10, 11, 10, 11, 10, 11, 10, 11, 10)
        val dwellX = floatArrayOf(3f, 3.1f, 3f, 3.2f, 3f, 3.1f, 3.2f, 3f, 3.1f, 3f, 3.2f, 3.1f)
        for (i in dwellD.indices) {
            d.at(t, car(d = dwellD[i], speedMs = -0.5f, rx = dwellX[i]))
            t += 100L
        }
        assertUrgent(d.at(t, car(d = 11, rx = 2.4f)))
    }

    @Test fun `a confident fit inside the margin is not delayed`() {
        val d = decider()
        for ((t, dist) in listOf(2_000L to 24, 2_100L to 22, 2_200L to 20, 2_300L to 18)) {
            d.at(t, car(d = dist, speedMs = -2f, rx = -1f))
        }
        assertUrgent(d.at(2_400L, car(d = 16, rx = -1f)))
    }

    @Test fun `a candidate never measured laterally is not held`() {
        val d = decider()
        val unmeasured = car(d = 15, rx = 3f, lateralUnknown = true)
        d.at(2_000L, unmeasured)
        assertUrgent(d.at(2_100L, unmeasured))
    }

    @Test fun `a lateral-unknown firing frame with a measured unconfident history is held`() {
        val d = decider()
        d.at(2_000L, car(d = 15, rx = 3.7f))
        val unknown = car(d = 15, rx = 3.7f, lateralUnknown = true)
        assertEquals(AlertDecider.Event.None, d.at(2_100L, unknown))
        assertEquals(AlertDecider.Event.None, d.at(2_399L, unknown))
        assertUrgent(d.at(2_400L, unknown))
    }

    @Test fun `a track is waited once in its life`() {
        val d = decider()
        d.at(2_000L, sideCar)
        d.at(2_100L, sideCar)
        assertUrgent(d.at(2_400L, sideCar))
        // Slow, so not qualifying, for longer than an urgent episode lasts.
        var t = 2_500L
        var i = 0
        while (t < 9_000L) {
            d.at(t, car(d = if (i % 2 == 0) 15 else 14, speedMs = -2f, rx = if (i % 2 == 0) 3f else 3.1f))
            t += 100L
            i++
        }
        assertUrgent(d.at(t, car(d = 15, rx = 3f)), "on its first qualifying frame, its wait spent")
    }

    @Test fun `a recycled tid does not inherit the previous car's spent wait`() {
        val d = decider()
        d.at(2_000L, sideCar)
        d.at(2_100L, sideCar)
        assertUrgent(d.at(2_400L, sideCar))
        // Two empty frames: the tid's sustain resets, so the next car is
        // stable from 9100 like the first.
        d.at(8_800L)
        d.at(8_900L)
        val next = car(d = 15, rx = 3f, born = 9_000L)
        d.at(9_000L, next)
        assertEquals(AlertDecider.Event.None, d.at(9_100L, next))
        assertEquals(AlertDecider.Event.None, d.at(9_399L, next))
        assertUrgent(d.at(9_400L, next))
    }

    @Test fun `reset clears the wait`() {
        val d = decider()
        d.at(2_000L, sideCar)
        d.at(2_100L, sideCar)
        assertUrgent(d.at(2_400L, sideCar))
        d.reset()
        d.at(3_000L)
        d.at(5_000L, sideCar)
        assertEquals("the same birth waits again after a reset", AlertDecider.Event.None, d.at(5_100L, sideCar))
        assertUrgent(d.at(5_400L, sideCar))
    }

    @Test fun `the wait and its run-out are each logged once`() {
        val d = decider()
        var t = 2_000L
        repeat(8) {
            d.at(t, sideCar)
            t += 100L
        }
        assertEquals(1, lines.count { it.startsWith("# gate urgent-pass-wait-start tid=1 ") })
        assertEquals(1, lines.count { it.startsWith("# gate urgent-pass-wait-over tid=1 ") })
    }

    @Test fun `a wait that ends in a veto logs the wait and the veto, not a run-out`() {
        val d = decider()
        for ((t, dist) in listOf(2_000L to 24, 2_100L to 22, 2_200L to 20)) {
            d.at(t, car(d = dist, speedMs = -2f, rx = 3f))
        }
        (0..5).forEach { i -> d.at(2_300L + 100L * i, car(d = 18 - 2 * i, rx = 3f)) }
        assertEquals(1, lines.count { it.startsWith("# gate urgent-pass-wait-start tid=1 ") })
        assertEquals(1, lines.count { it.startsWith("# gate urgent-pass-veto tid=1 ") })
        assertEquals(0, lines.count { it.startsWith("# gate urgent-pass-wait-over") })
    }

    @Test fun `on the moving path a waiting candidate still gets its tier beep`() {
        // Intended: the wait holds only the urgent cue, so a slow rider hears
        // the near-tier beep first and the urgent after it.
        val d = AlertDecider(onGateEvent = { lines += it })
        val near = car(d = 5, speedMs = -10.5f, rx = 3f)
        d.at(0L, near, speed = 4f)
        assertEquals(AlertDecider.Event.Beep(3), d.at(100L, near, speed = 4f))
        assertEquals(AlertDecider.Event.None, d.at(399L, near, speed = 4f))
        val ev = d.at(400L, near, speed = 4f)
        assertTrue("expected a moving-path urgent, got $ev", ev is AlertDecider.Event.UrgentApproach && ev.viaMovingPath)
    }

    @Test fun `a waiting candidate does not hold the episode for another car`() {
        // Intended and measured: a car that fires while another waits opens
        // the episode as usual.
        val d = decider()
        val other = car(id = 2, d = 12, rx = 0f, born = 1_500L)
        d.at(2_000L, sideCar)
        assertEquals(AlertDecider.Event.None, d.at(2_100L, sideCar))
        d.at(2_200L, other, sideCar)
        val ev = d.at(2_300L, other, sideCar)
        assertTrue("the other car fires, got $ev", ev is AlertDecider.Event.UrgentApproach && ev.triggerTid == 2)
        assertEquals(AlertDecider.Event.None, d.at(2_400L, sideCar))
        assertEquals(AlertDecider.Event.None, d.at(2_500L, sideCar))
    }

    /** An episode opened at 2100 by a centred car closing at 6 m/s. At 3000 a
     *  side car closing at 9 m/s and a farther centred one closing at 7.5 m/s
     *  arrive together. */
    private fun episodeThenAFasterSideCar(wait: Boolean): Pair<AlertDecider, List<AlertDecider.Event>> {
        val d = decider()
        val opener = car(id = 3, d = 15, speedMs = -6f, rx = 0f, born = 2_000L)
        d.at(2_000L, opener, wait = wait)
        assertUrgent(d.at(2_100L, opener, wait = wait), "to open the episode")
        val side = car(id = 1, d = 15, speedMs = -9f, rx = 3f, born = 3_000L)
        val behind = car(id = 2, d = 18, speedMs = -7.5f, rx = 0f, born = 3_000L)
        d.at(3_000L, side, behind, wait = wait)
        return d to (0..4).map { i -> d.at(3_100L + 100L * i, side, behind, wait = wait) }
    }

    @Test fun `a car released from its wait keeps the new-severity bypass it arrived with`() {
        // Closing 3 m/s faster than the episode's 6 m/s peak, the side car
        // would cut through the pacing on arrival. The paced car behind it
        // raises the peak to 7.5 while it waits; judged against that, it
        // would sit out the pacing until 5100.
        val (_, events) = episodeThenAFasterSideCar(wait = true)
        assertEquals(List(3) { AlertDecider.Event.None }, events.take(3))
        val ev = events[3]
        assertTrue("the side car fires once its wait runs out, got $ev", ev is AlertDecider.Event.UrgentApproach && ev.triggerTid == 1)
    }

    @Test fun `a held car closer than another qualifying car does not shadow it, and is paced after it`() {
        // The decoder sorts by distance, so the held car is evaluated first.
        // Intended: an urgent heard during the wait means the released car is
        // paced like any other.
        val d = decider()
        val near = car(id = 1, d = 10, rx = 3f)
        val centred = car(id = 2, d = 15, rx = 0f, born = 1_500L)
        d.at(2_000L, near, centred)
        val ev = d.at(2_100L, near, centred)
        assertTrue("the centred car fires past the held one, got $ev", ev is AlertDecider.Event.UrgentApproach && ev.triggerTid == 2)
        assertEquals(AlertDecider.Event.None, d.at(2_400L, near, centred))
        assertEquals(1, lines.count { it.startsWith("# gate urgent-pass-wait-over tid=1 ") })
        assertEquals(AlertDecider.Event.None, d.at(2_500L, near, centred))
    }

    @Test fun `a released car that does not clear its own bar by 2 m per s is still paced`() {
        // The peak it is judged against is the one when its wait began, 6 m/s:
        // 7.5 does not clear it by 2.
        val d = decider()
        val opener = car(id = 3, d = 15, speedMs = -6f, rx = 0f, born = 2_000L)
        d.at(2_000L, opener)
        assertUrgent(d.at(2_100L, opener), "to open the episode")
        val side = car(id = 1, d = 15, speedMs = -7.5f, rx = 3f, born = 3_000L)
        d.at(3_000L, side)
        val events = (0..4).map { i -> d.at(3_100L + 100L * i, side) }
        assertEquals(List(5) { AlertDecider.Event.None }, events)
        assertEquals(1, lines.count { it.startsWith("# gate urgent-pass-wait-over tid=1 ") })
    }

    @Test fun `a released car's own speed raises its bar, as the episode peak would`() {
        // Paced at release against 6, its own check raises its bar to 7.5. A
        // car that never waited, creeping on to 8.0, stays paced against 7.5;
        // so must this one.
        val d = decider()
        val opener = car(id = 3, d = 15, speedMs = -6f, rx = 0f, born = 2_000L)
        d.at(2_000L, opener)
        assertUrgent(d.at(2_100L, opener), "to open the episode")
        val side = car(id = 1, d = 15, speedMs = -7.5f, rx = 3f, born = 3_000L)
        d.at(3_000L, side)
        (0..3).forEach { i -> assertEquals(AlertDecider.Event.None, d.at(3_100L + 100L * i, side)) }
        assertEquals(AlertDecider.Event.None, d.at(3_500L, car(id = 1, d = 14, speedMs = -8f, rx = 3f, born = 3_000L)))
    }

    @Test fun `a car paced behind a waiting one does not raise its bar for later either`() {
        // Paced at release against 6, the side car then speeds up to 9. With
        // the wait off its own 7.0 would be the bar and 9 clears it by 2; the
        // 7.5 the car behind set during the hold must not stand in its way.
        val d = decider()
        val opener = car(id = 3, d = 15, speedMs = -6f, rx = 0f, born = 2_000L)
        d.at(2_000L, opener)
        assertUrgent(d.at(2_100L, opener), "to open the episode")
        val side = car(id = 1, d = 15, speedMs = -7f, rx = 3f, born = 3_000L)
        val behind = car(id = 2, d = 18, speedMs = -7.5f, rx = 0f, born = 3_000L)
        d.at(3_000L, side, behind)
        (0..3).forEach { i -> assertEquals(AlertDecider.Event.None, d.at(3_100L + 100L * i, side, behind)) }
        val ev = d.at(3_500L, car(id = 1, d = 14, speedMs = -9f, rx = 3f, born = 3_000L), behind)
        assertTrue("the side car fires once it clears its own 7.0 by 2, got $ev", ev is AlertDecider.Event.UrgentApproach && ev.triggerTid == 1)
    }

    @Test fun `switching the wait off at release drops its own bar too`() {
        // As the bypass test above, but the rider turns the wait off as the
        // car is released: it is judged as if it had never waited, against
        // the 7.5 the paced car set, and 9 does not clear that by 2.
        val d = decider()
        val (side, behind) = sideCarAndPacedCarAfterAnEpisode(d)
        assertEquals(AlertDecider.Event.None, d.at(3_400L, side, behind, wait = false))
    }

    /** The bypass fixture up to the frame the side car's wait runs out. */
    private fun sideCarAndPacedCarAfterAnEpisode(d: AlertDecider): Pair<Vehicle, Vehicle> {
        val opener = car(id = 3, d = 15, speedMs = -6f, rx = 0f, born = 2_000L)
        d.at(2_000L, opener)
        assertUrgent(d.at(2_100L, opener), "to open the episode")
        val side = car(id = 1, d = 15, speedMs = -9f, rx = 3f, born = 3_000L)
        val behind = car(id = 2, d = 18, speedMs = -7.5f, rx = 0f, born = 3_000L)
        d.at(3_000L, side, behind)
        (0..2).forEach { i -> assertEquals(AlertDecider.Event.None, d.at(3_100L + 100L * i, side, behind)) }
        return side to behind
    }

    @Test fun `a recycled tid does not inherit the previous car's bypass`() {
        // tid 1's first car waits from 2300 against a 6 m/s peak and goes. A
        // paced car raises the peak to 7.5; a new car under tid 1 closing at
        // 9 does not clear 7.5 by 2, whatever the old wait recorded.
        val d = decider()
        val opener = car(id = 3, d = 15, speedMs = -6f, rx = 0f, born = 2_000L)
        d.at(2_000L, opener)
        assertUrgent(d.at(2_100L, opener), "to open the episode")
        val gone = car(id = 1, d = 12, speedMs = -9f, rx = 3f, born = 2_200L)
        d.at(2_200L, gone)
        assertEquals(AlertDecider.Event.None, d.at(2_300L, gone))
        val paced = car(id = 2, d = 18, speedMs = -7.5f, rx = 0f, born = 2_500L)
        d.at(2_500L, paced)
        assertEquals(AlertDecider.Event.None, d.at(2_600L, paced))
        val reborn = car(id = 1, d = 15, speedMs = -9f, rx = 0f, born = 2_700L)
        d.at(2_700L, reborn, paced)
        assertEquals(AlertDecider.Event.None, d.at(2_800L, reborn, paced))
    }

    @Test fun `with the wait off the faster side car cuts through the pacing on arrival`() {
        val (_, events) = episodeThenAFasterSideCar(wait = false)
        val ev = events[0]
        assertTrue("got $ev", ev is AlertDecider.Event.UrgentApproach && ev.triggerTid == 1)
    }
}
