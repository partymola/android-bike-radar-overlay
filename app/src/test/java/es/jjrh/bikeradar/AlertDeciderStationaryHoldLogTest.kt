// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A beep the stationary suppress holds back at a stop writes a gate line, once
 * per car and band at each stop, so a car muted at a light reads differently
 * from one the decider never considered.
 */
class AlertDeciderStationaryHoldLogTest {

    private fun car(
        id: Int,
        distanceM: Int,
        bornAtMs: Long = id.toLong(),
        rangeXm: Float = 0f,
        speedMs: Float = -2f,
        bornCloseAtM: Int? = null,
    ) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        rangeXm = rangeXm,
        isAlongsideStationary = false,
        isBehind = false,
        bornAtMs = bornAtMs,
        bornDistanceM = bornCloseAtM ?: Int.MAX_VALUE,
        bornInformative = bornCloseAtM != null,
    )

    private class Scene {
        val gateLines = mutableListOf<String>()
        val d = AlertDecider(onGateEvent = { gateLines += it })
        var now = 0L
        val events = mutableListOf<AlertDecider.Event>()

        /** [eBike] false leaves the stop to the radar's speed; [radarSpeed] false sends none. */
        fun frames(
            n: Int,
            stopped: Boolean,
            eBike: Boolean = true,
            radarSpeed: Boolean = true,
            vehicles: () -> List<Vehicle>,
        ) {
            repeat(n) {
                val speed = when {
                    !radarSpeed -> null
                    stopped -> 0f
                    else -> 5f
                }
                val ev = d.decide(vehicles(), 30, now, bikeSpeedMs = speed, bikeNotDriving = if (eBike) stopped else null)
                now += 100
                if (ev != AlertDecider.Event.None) events += ev
            }
        }

        fun stationaryLines() = gateLines.filter { it.startsWith("# gate stationary") }
    }

    /** Stopped long enough for the suppress to engage, with nothing behind. */
    private fun Scene.settleAtAStop() = frames(25, stopped = true) { emptyList() }

    @Test
    fun `a beep held at a stop is logged once however long it is held`() {
        val s = Scene()
        s.settleAtAStop()
        s.frames(10, stopped = true) { listOf(car(1, 5)) }
        // Creeping closer inside the same band is the same hold.
        s.frames(10, stopped = true) { listOf(car(1, 3)) }
        assertEquals(emptyList<AlertDecider.Event>(), s.events)
        assertEquals(listOf("# gate stationary tid=1 tier=3 d=5 eff=5.0 floor=0"), s.stationaryLines())
    }

    @Test
    fun `a car already heard before the stop logs nothing while the rider waits`() {
        val s = Scene()
        s.frames(5, stopped = false) { listOf(car(1, 5)) }
        s.frames(30, stopped = true) { listOf(car(1, 5)) }
        assertEquals(listOf<AlertDecider.Event>(AlertDecider.Event.Beep(3)), s.events)
        assertEquals(emptyList<String>(), s.stationaryLines())
    }

    @Test
    fun `the line carries the true range the band is scored on`() {
        val s = Scene()
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 8, rangeXm = 6f)) }
        assertEquals(listOf("# gate stationary tid=1 tier=3 d=8 eff=10.0 floor=0"), s.stationaryLines())
    }

    @Test
    fun `a held car climbing a band is logged again at the new band`() {
        val s = Scene()
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 15)) }
        s.frames(5, stopped = true) { listOf(car(1, 5)) }
        assertEquals(
            listOf(
                "# gate stationary tid=1 tier=2 d=15 eff=15.0 floor=0",
                "# gate stationary tid=1 tier=3 d=5 eff=5.0 floor=0",
            ),
            s.stationaryLines(),
        )
    }

    @Test
    fun `a band flickering at a stop is logged once per band`() {
        val s = Scene()
        s.settleAtAStop()
        repeat(4) {
            s.frames(3, stopped = true) { listOf(car(1, 9)) }
            s.frames(3, stopped = true) { listOf(car(1, 11)) }
        }
        assertEquals(
            listOf(
                "# gate stationary tid=1 tier=3 d=9 eff=9.0 floor=0",
                "# gate stationary tid=1 tier=2 d=11 eff=11.0 floor=0",
            ),
            s.stationaryLines(),
        )
    }

    @Test
    fun `a hold that moves to another car and back is logged once per car`() {
        val s = Scene()
        s.settleAtAStop()
        // Born on the same frame, so only the track id tells them apart.
        s.frames(5, stopped = true) { listOf(car(1, 8, bornAtMs = 500L)) }
        s.frames(5, stopped = true) { listOf(car(1, 8, bornAtMs = 500L), car(2, 4, bornAtMs = 500L)) }
        s.frames(5, stopped = true) { listOf(car(1, 8, bornAtMs = 500L)) }
        assertEquals(
            listOf(
                "# gate stationary tid=1 tier=3 d=8 eff=8.0 floor=0",
                "# gate stationary tid=2 tier=3 d=4 eff=4.0 floor=0",
            ),
            s.stationaryLines(),
        )
    }

    @Test
    fun `a recycled track id held at the same band is logged again`() {
        val s = Scene()
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 5, bornAtMs = 1L)) }
        s.frames(5, stopped = true) { listOf(car(1, 5, bornAtMs = 9_000L)) }
        assertEquals(2, s.stationaryLines().size)
    }

    @Test
    fun `the same car held at a later stop is logged again`() {
        val s = Scene()
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 5)) }
        // A moment of riding with the car out of view, then a second stop
        // long enough for the all-clear before the car is back.
        s.frames(1, stopped = false) { emptyList() }
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 5)) }
        assertEquals(listOf(AlertDecider.Event.Clear), s.events)
        assertEquals(2, s.stationaryLines().size)
    }

    @Test
    fun `moving off clears the memo even when no all-clear came between the stops`() {
        val s = Scene()
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 5)) }
        s.frames(1, stopped = false) { emptyList() }
        // Back before the all-clear could sound.
        s.frames(19, stopped = true) { emptyList() }
        s.frames(5, stopped = true) { listOf(car(1, 5)) }
        assertEquals(emptyList<AlertDecider.Event>(), s.events)
        assertEquals(2, s.stationaryLines().size)
    }

    @Test
    fun `with no eBike the radar's speed alone marks the stop and the hold is logged`() {
        val s = Scene()
        s.frames(25, stopped = true, eBike = false) { emptyList() }
        s.frames(5, stopped = true, eBike = false) { listOf(car(1, 5)) }
        assertEquals(emptyList<AlertDecider.Event>(), s.events)
        assertEquals(listOf("# gate stationary tid=1 tier=3 d=5 eff=5.0 floor=0"), s.stationaryLines())
    }

    @Test
    fun `with no speed reading at all nothing is held and nothing is logged`() {
        val s = Scene()
        s.frames(25, stopped = true, eBike = false, radarSpeed = false) { emptyList() }
        s.frames(5, stopped = true, eBike = false, radarSpeed = false) { listOf(car(1, 5)) }
        assertEquals(listOf<AlertDecider.Event>(AlertDecider.Event.Beep(3)), s.events)
        assertEquals(emptyList<String>(), s.stationaryLines())
    }

    @Test
    fun `a track the ghost filter holds is logged at the stop and suppressed on moving off`() {
        val s = Scene()
        s.settleAtAStop()
        // First seen close and not closing, so it never earns its beep.
        val ghost = { car(1, 5, speedMs = 0f, bornCloseAtM = 5) }
        s.frames(5, stopped = true) { listOf(ghost()) }
        s.frames(3, stopped = false) { listOf(ghost()) }
        assertEquals(emptyList<AlertDecider.Event>(), s.events)
        assertEquals(listOf("# gate stationary tid=1 tier=3 d=5 eff=5.0 floor=0"), s.stationaryLines())
        assertEquals(1, s.gateLines.count { it.startsWith("# gate suppress tid=1 ") })
    }

    @Test
    fun `the line carries the floor a later beep would also face`() {
        val s = Scene()
        s.frames(5, stopped = false) { listOf(car(1, 9)) }
        s.frames(25, stopped = true) { listOf(car(1, 9)) }
        s.frames(5, stopped = true) { listOf(car(2, 15)) }
        assertEquals(listOf<AlertDecider.Event>(AlertDecider.Event.Beep(3)), s.events)
        assertEquals(listOf("# gate stationary tid=2 tier=2 d=15 eff=15.0 floor=3"), s.stationaryLines())
    }

    @Test
    fun `the held beep sounds on moving off and moving logs no hold`() {
        val s = Scene()
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 5)) }
        s.frames(3, stopped = false) { listOf(car(1, 5)) }
        assertEquals(listOf<AlertDecider.Event>(AlertDecider.Event.Beep(3)), s.events)
        assertEquals(1, s.stationaryLines().size)
    }

    @Test
    fun `a reset forgets the logged hold`() {
        val s = Scene()
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 5)) }
        s.d.reset()
        s.settleAtAStop()
        s.frames(5, stopped = true) { listOf(car(1, 5)) }
        assertEquals(2, s.stationaryLines().size)
    }
}
