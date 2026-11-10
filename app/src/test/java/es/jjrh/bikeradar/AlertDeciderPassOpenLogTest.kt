// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `# gate urgent-pass-open` capture line: an urgent candidate whose newest
 * measured sample is near centre fails open, and the line says whether a fit
 * over its whole approach existed and was passed over.
 *
 * Times are literal. The rider is stopped from t = 0, so a car first seen at
 * 2000 is stable, and qualifying, from 2100.
 */
class AlertDeciderPassOpenLogTest {

    private val alertMax = 21
    private val lines = mutableListOf<String>()

    private fun decider(): AlertDecider = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L, onGateEvent = { lines += it }).also {
        it.decide(emptyList(), alertMax, 0L, bikeSpeedMs = 0f)
    }

    private fun car(d: Int, rx: Float, speedMs: Float = -8f) = Vehicle(id = 1, distanceM = d, speedMs = speedMs, rangeXm = rx, rangeXmRaw = rx, bornAtMs = 2_000L)

    private fun AlertDecider.at(t: Long, v: Vehicle): AlertDecider.Event = decide(listOf(v), alertMax, t, bikeSpeedMs = 0f)

    private fun openLines() = lines.filter { it.startsWith("# gate urgent-pass-open") }

    @Test fun `a new track near centre is logged as open with no fit to pass over`() {
        val d = decider()
        d.at(2_000L, car(d = 10, rx = 0.3f))
        val ev = d.at(2_100L, car(d = 9, rx = 0.2f))
        assertTrue("expected UrgentApproach, got $ev", ev is AlertDecider.Event.UrgentApproach)
        assertEquals(listOf("# gate urgent-pass-open tid=1 newest_rx=0.2 d=9 fallback=none"), openLines())
        // Still one line while it stays a candidate with no fit.
        d.at(2_200L, car(d = 8, rx = 0.1f))
        assertEquals(1, openLines().size)
    }

    @Test fun `a track with enough samples over too short an approach is logged as open with no fit`() {
        val d = decider()
        // Five samples over 4 m, too slow to qualify, so nothing is logged yet.
        var t = 2_000L
        for (dist in intArrayOf(14, 13, 12, 11, 10)) {
            d.at(t, car(d = dist, rx = 0.3f, speedMs = -2f))
            t += 100L
        }
        assertEquals(emptyList<String>(), openLines())
        // The sixth qualifies: six samples spanning 5 m, one short of a fit.
        val ev = d.at(t, car(d = 9, rx = 0.2f))
        assertTrue("expected UrgentApproach, got $ev", ev is AlertDecider.Event.UrgentApproach)
        assertEquals(listOf("# gate urgent-pass-open tid=1 newest_rx=0.2 d=9 fallback=none"), openLines())
    }

    /** Six samples 3 m to the side over 10 m of approach, then twelve bunched
     *  within a metre, so the newest twelve have no fit and the whole run does. */
    private fun AlertDecider.wideApproachThenBunched(): Long {
        var t = 2_000L
        for (dist in intArrayOf(24, 22, 20, 18, 16, 14)) {
            at(t, car(d = dist, rx = 3f, speedMs = -2f))
            t += 100L
        }
        for (i in 0 until 12) {
            at(t, car(d = 11 - i % 2, rx = 3f + i * 0.01f, speedMs = -2f))
            t += 100L
        }
        return t
    }

    @Test fun `a swing to centre past a wide approach is logged as open over a fit`() {
        val d = decider()
        val t = d.wideApproachThenBunched()
        val ev = d.at(t, car(d = 10, rx = 0.3f))
        assertTrue("expected UrgentApproach, got $ev", ev is AlertDecider.Event.UrgentApproach)
        assertEquals(listOf("# gate urgent-pass-open tid=1 newest_rx=0.3 d=10 fallback=fit"), openLines())
    }

    @Test fun `a track logged with no fit is logged again once a fit is passed over`() {
        val d = decider()
        // Near centre and closing from the start, so open with no fit.
        d.at(2_000L, car(d = 21, rx = 0.3f))
        d.at(2_100L, car(d = 20, rx = 0.2f))
        assertEquals(listOf("# gate urgent-pass-open tid=1 newest_rx=0.2 d=20 fallback=none"), openLines())
        // Twelve more bunched within a metre. The whole run spans 11 m, but the
        // second line comes only once the newest twelve are all bunched and so
        // have no fit of their own.
        var t = 2_200L
        for (i in 0 until 12) {
            d.at(t, car(d = 11 - i % 2, rx = 0.2f + i * 0.01f))
            t += 100L
        }
        assertEquals(
            listOf("fallback=none", "fallback=fit"),
            openLines().map { it.substringAfterLast(' ') },
        )
    }
}
