// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real [TurnStateDecider] driving [AlertDecider], where every other alert
 * test hands the decider a turn-state enum. A vehicle first seen close behind
 * and closing at 3 m/s is held silent while the rider is turning, because a
 * turn can fake that much closing, and gets its beep once the turn ends.
 */
class AlertDeciderTurnDetectorTest {

    @Test
    fun aCloseVehicleWaitsForTheTurnToEndThenBeeps() {
        val turn = TurnStateDecider()
        val alerts = AlertDecider()
        val car = Vehicle(
            id = 1,
            distanceM = 8,
            speedMs = -3f,
            bornDistanceM = 8,
            bornInformative = true,
            bornAtMs = 2_600L,
        )
        val beeps = mutableListOf<Pair<Long, AlertDecider.Event>>()
        var t = 1_000L
        while (t <= 6_000L) {
            // A 92-degree corner at 0.5 rad/s until 4200, then straight.
            turn.onYawSample(if (t <= 4_200L) 0.5f else 0f, t)
            if (t >= 2_600L && t % 100L == 0L) {
                val ev = alerts.decide(
                    vehicles = listOf(car),
                    alertMaxM = 30,
                    nowMs = t,
                    bikeSpeedMs = 5f,
                    turnState = turn.stateAt(t),
                )
                if (ev is AlertDecider.Event.Beep) beeps += t to ev
            }
            t += 50L
        }
        // The corner qualifies at 2500 and goes quiet at 4900.
        assertTrue(beeps.none { it.first < 4_900L })
        assertEquals(listOf(5_000L to AlertDecider.Event.Beep(3)), beeps)
    }
}
