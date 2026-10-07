// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a new car's closing speed cuts through the urgent episode pacing:
 *  at exactly 2 m/s faster than the episode's peak, not just beyond it. */
class AlertDeciderUrgentBypassTest {

    private val alertMax = 21

    /** An episode opened at 2100 by a car closing at 8 m/s, then at 2900 a
     *  new car closing at [closing], judged on its first qualifying frame. */
    private fun secondCar(closing: Float): AlertDecider.Event {
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        d.decide(emptyList(), alertMax, 0L, bikeSpeedMs = 0f)
        val first = Vehicle(id = 1, distanceM = 15, speedMs = -8f)
        d.decide(listOf(first), alertMax, 2_000L, bikeSpeedMs = 0f)
        assertTrue(d.decide(listOf(first), alertMax, 2_100L, bikeSpeedMs = 0f) is AlertDecider.Event.UrgentApproach)
        val second = Vehicle(id = 2, distanceM = 14, speedMs = -closing)
        d.decide(listOf(second), alertMax, 2_800L, bikeSpeedMs = 0f)
        return d.decide(listOf(second), alertMax, 2_900L, bikeSpeedMs = 0f)
    }

    @Test fun `a car exactly 2 m per s faster than the peak fires through the pacing`() {
        val ev = secondCar(closing = 10f)
        assertTrue("got $ev", ev is AlertDecider.Event.UrgentApproach)
    }

    @Test fun `a car one radar step short of that is paced`() {
        assertEquals(AlertDecider.Event.None, secondCar(closing = 9.5f))
    }
}
