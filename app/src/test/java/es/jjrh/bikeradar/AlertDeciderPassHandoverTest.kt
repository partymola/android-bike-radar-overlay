// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Beeps describe the closest car still behind the rider. When that car passes
 * the rider, drawing level where they can see it, the next car behind is the
 * one the beeps describe, at its own band. Without a pass, a lower count than
 * the last one heard is never sounded for another car, since it would read as
 * the close car dropping back, except with no movement signal at all.
 */
class AlertDeciderPassHandoverTest {

    private class Clock(val dtMs: Long = 100L) {
        var now = 0L
        fun tick(): Long = now.also { now += dtMs }
    }

    private fun car(
        id: Int,
        distanceM: Int,
        speedMs: Float = -2f,
        bornAtMs: Long = id.toLong(),
        isBehind: Boolean = false,
        bornDistanceM: Int = Int.MAX_VALUE,
        rangeXm: Float = 0f,
        alongside: Boolean = false,
    ) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        rangeXm = rangeXm,
        isAlongsideStationary = alongside,
        isBehind = isBehind,
        bornAtMs = bornAtMs,
        bornDistanceM = bornDistanceM,
        // A born-close track is held by the ghost filter only when its birth
        // says something about the road.
        bornInformative = bornDistanceM != Int.MAX_VALUE,
    )

    /** An audible event and the car the decider scored it on. */
    private data class Heard(val event: AlertDecider.Event, val tid: Int?)

    private fun beep(count: Int, tid: Int) = Heard(AlertDecider.Event.Beep(count), tid)

    private class Scene {
        val gateLines = mutableListOf<String>()
        val d = AlertDecider(onGateEvent = { gateLines += it })
        val c = Clock()
        val heard = mutableListOf<Heard>()

        /** The rule each beep in [heard] was armed by, in order. */
        val rules = mutableListOf<String?>()

        fun frames(n: Int, bikeSpeedMs: Float? = RIDING, bikeNotDriving: Boolean? = null, vehicles: () -> List<Vehicle>) {
            repeat(n) {
                val ev = d.decide(vehicles(), ALERT_MAX, c.tick(), bikeSpeedMs = bikeSpeedMs, bikeNotDriving = bikeNotDriving)
                if (ev != AlertDecider.Event.None) {
                    val tid = when (ev) {
                        is AlertDecider.Event.UrgentApproach -> ev.triggerTid
                        is AlertDecider.Event.Beep -> d.lastTierTrigger?.id
                        else -> null
                    }
                    heard += Heard(ev, tid)
                    if (ev is AlertDecider.Event.Beep) rules += d.lastBeepRule
                }
            }
        }
    }

    @Test
    fun `the next car in the same band beeps when the closest passes`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5)) }
        s.frames(5) { listOf(car(1, 4), car(2, 6)) }
        s.frames(10) { listOf(car(1, 1, isBehind = true), car(2, 6)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `the next car further back beeps at its own band when the closest passes`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5), car(2, 12)) }
        s.frames(10) { listOf(car(1, 1, isBehind = true), car(2, 12)) }
        assertEquals(listOf(beep(3, 1), beep(2, 2)), s.heard)
    }

    @Test
    fun `a next car in the far band beeps once on the pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5), car(2, 18)) }
        s.frames(10) { listOf(car(1, 1, isBehind = true), car(2, 18)) }
        assertEquals(listOf(beep(3, 1), beep(1, 2)), s.heard)
    }

    @Test
    fun `with no pass, a car arriving after the road emptied is silent below the band last heard`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5)) }
        // Car 1 vanishes 5 m back: a turn-off or a dropout, not a pass.
        s.frames(5) { emptyList() }
        s.frames(10) { listOf(car(2, 12)) }
        s.frames(10) { listOf(car(2, 6)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `with no pass, the next car stays silent until it reaches the band last heard`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5), car(2, 12)) }
        s.frames(10) { listOf(car(2, 12)) }
        s.frames(10) { listOf(car(2, 6)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `the closest fading right beside a moving rider counts as a pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5), car(2, 12)) }
        s.frames(3) { listOf(car(1, 2), car(2, 12)) }
        s.frames(10) { listOf(car(2, 12)) }
        assertEquals(listOf(beep(3, 1), beep(2, 2)), s.heard)
    }

    @Test
    fun `the closest fading beside a stopped rider is not a pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5), car(2, 12, speedMs = 0f)) }
        s.frames(3) { listOf(car(1, 2, speedMs = 0f), car(2, 12, speedMs = 0f)) }
        s.frames(30, bikeSpeedMs = 0f) { listOf(car(1, 2, speedMs = 0f), car(2, 12, speedMs = 0f)) }
        // At the light the car behind fades out.
        s.frames(10, bikeSpeedMs = 0f) { listOf(car(2, 12, speedMs = 0f)) }
        // Moving off: car 2 is further back than the band last heard.
        s.frames(10) { listOf(car(2, 12)) }
        s.frames(10) { listOf(car(2, 6)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `a car at the band last heard beeps on moving off after the closest faded at a light`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 4), car(2, 6, speedMs = 0f)) }
        s.frames(30, bikeSpeedMs = 0f) { listOf(car(1, 2, speedMs = 0f), car(2, 6, speedMs = 0f)) }
        s.frames(10, bikeSpeedMs = 0f) { listOf(car(2, 6, speedMs = 0f)) }
        s.frames(10) { listOf(car(2, 6)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `a pass while stopped is heard on moving off, at the next car's band`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 4), car(2, 12)) }
        s.frames(30, bikeSpeedMs = 0f) { listOf(car(1, 3), car(2, 12, speedMs = 0f)) }
        s.frames(5, bikeSpeedMs = 0f) { listOf(car(1, 1, isBehind = true), car(2, 12, speedMs = 0f)) }
        assertEquals(listOf(beep(3, 1)), s.heard)
        s.frames(10) { listOf(car(2, 12)) }
        assertEquals(listOf(beep(3, 1), beep(2, 2)), s.heard)
    }

    @Test
    fun `after a pass the car behind is announced again though heard before`() {
        val s = Scene()
        s.frames(3) { listOf(car(2, 6)) }
        // Car 1 overtakes car 2 behind the rider and is heard at its band.
        s.frames(10) { listOf(car(1, 4), car(2, 6)) }
        // Car 1 passes: car 2 is still behind, so the rider hears it again.
        s.frames(10) { listOf(car(1, 1, isBehind = true), car(2, 6)) }
        assertEquals(listOf(beep(3, 2), beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `another car passing while the heard car is still behind is not a pass`() {
        val s = Scene()
        // Car 2 is the closest and heard; car 1 is a lane over, further by
        // true range.
        s.frames(3) { listOf(car(2, 4), car(1, 6, rangeXm = 4f)) }
        s.frames(3) { listOf(car(2, 4), car(1, 1, rangeXm = 4f, isBehind = true)) }
        s.frames(3) { listOf(car(2, 4), car(3, 12)) }
        // Car 2 is lost 4 m back: no pass, so car 3 waits for band 3.
        s.frames(10) { listOf(car(3, 12)) }
        s.frames(10) { listOf(car(3, 6)) }
        assertEquals(listOf(beep(3, 2), beep(3, 3)), s.heard)
    }

    @Test
    fun `with no speed reading a fade is not a pass`() {
        for (birth in listOf(null, 0L)) {
            val s = Scene()
            fun car(id: Int, d: Int) = car(id, d, bornAtMs = birth ?: id.toLong())
            s.frames(3, bikeSpeedMs = null) { listOf(car(1, 5), car(2, 12)) }
            s.frames(3, bikeSpeedMs = null) { listOf(car(1, 2), car(2, 12)) }
            s.frames(10, bikeSpeedMs = null) { listOf(car(2, 12)) }
            s.frames(10, bikeSpeedMs = null) { listOf(car(2, 6)) }
            assertEquals("birth=$birth", listOf(beep(3, 1), beep(3, 2)), s.heard)
        }
    }

    @Test
    fun `the eBike decides whether the rider is moving for a fade`() {
        fun heard(radarSpeedMs: Float?, eBikeNotDriving: Boolean): List<Heard> {
            val s = Scene()
            s.frames(3, radarSpeedMs, eBikeNotDriving) { listOf(car(1, 5), car(2, 12)) }
            s.frames(3, radarSpeedMs, eBikeNotDriving) { listOf(car(1, 2), car(2, 12)) }
            s.frames(10, radarSpeedMs, eBikeNotDriving) { listOf(car(2, 12)) }
            return s.heard
        }
        // The eBike says riding and the radar gives no speed: a pass.
        assertEquals(listOf(beep(3, 1), beep(2, 2)), heard(radarSpeedMs = null, eBikeNotDriving = false))
        // The eBike says stopped though the radar still reads a speed: no pass.
        assertEquals(listOf(beep(3, 1)), heard(radarSpeedMs = RIDING, eBikeNotDriving = true))
    }

    @Test
    fun `a closest fading as its track id goes straight to a new closest car is a new car`() {
        val s = Scene()
        s.frames(3) { listOf(car(7, 5, bornAtMs = 100L)) }
        s.frames(3) { listOf(car(7, 2, bornAtMs = 100L)) }
        // Car 7 fades beside the rider and its id goes at once to a new car
        // close behind.
        s.frames(10) { listOf(car(7, 6, bornAtMs = 200L)) }
        assertEquals(listOf(beep(3, 7), beep(3, 7)), s.heard)
    }

    @Test
    fun `a fade just after the rider stops is not a pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5), car(2, 12)) }
        // Braking to a light: half a second after stopping the closest fades.
        s.frames(5, bikeSpeedMs = 0f) { listOf(car(1, 2), car(2, 12)) }
        s.frames(1, bikeSpeedMs = 0f) { listOf(car(2, 12)) }
        s.frames(10) { listOf(car(2, 12)) }
        s.frames(10) { listOf(car(2, 6)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `a fade is judged on the distance along the road`() {
        val s = Scene()
        // A lane over: 3 m back along the road, 3.6 m away in a straight line.
        s.frames(3) { listOf(car(1, 5, rangeXm = 2f), car(2, 12)) }
        s.frames(3) { listOf(car(1, 3, rangeXm = 2f), car(2, 12)) }
        s.frames(10) { listOf(car(2, 12)) }
        assertEquals(listOf(beep(3, 1), beep(2, 2)), s.heard)
    }

    @Test
    fun `the closest docked alongside hands over without lifting the band`() {
        fun heard(nextDistanceM: Int): List<Heard> {
            val s = Scene()
            s.frames(3) { listOf(car(1, 5), car(2, nextDistanceM)) }
            s.frames(10) { listOf(car(1, 5, alongside = true), car(2, nextDistanceM)) }
            return s.heard
        }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), heard(6))
        assertEquals(listOf(beep(3, 1)), heard(12))
    }

    @Test
    fun `a car overtaking the closest one behind the rider is heard at its band`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 6)) }
        // Car 2 comes up from further back and takes car 1's place.
        s.frames(3) { listOf(car(1, 6), car(2, 9)) }
        s.frames(10) { listOf(car(1, 6), car(2, 5)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `a car taking the closest place further back is still held below the floor`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 6)) }
        // Car 1 drops back behind car 2 into the middle band: car 2 is the
        // closest now, but a 2 after a 3 with no pass is never sounded.
        s.frames(10) { listOf(car(1, 13), car(2, 12)) }
        assertEquals(listOf(beep(3, 1)), s.heard)
    }

    @Test
    fun `two cars swapping places behind the rider are each heard once`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5), car(2, 6)) }
        repeat(4) {
            s.frames(10) { listOf(car(1, 6), car(2, 5)) }
            s.frames(10) { listOf(car(1, 5), car(2, 6)) }
        }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
    }

    @Test
    fun `a recycled track id is a new car`() {
        val s = Scene()
        s.frames(3) { listOf(car(7, 5, bornAtMs = 100L)) }
        s.frames(1) { listOf(car(7, 1, bornAtMs = 100L, isBehind = true)) }
        s.frames(10) { listOf(car(7, 6, bornAtMs = 200L)) }
        assertEquals(listOf(beep(3, 7), beep(3, 7)), s.heard)
    }

    @Test
    fun `no beep is ever about a car that is not the closest`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5)) }
        s.frames(30) { listOf(car(1, 5), car(2, 12)) }
        assertEquals(listOf(beep(3, 1)), s.heard)
    }

    @Test
    fun `a queue of cars passing one after another gives one beep per pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 4), car(2, 5), car(3, 6)) }
        s.frames(10) { listOf(car(1, 1, isBehind = true), car(2, 5), car(3, 6)) }
        s.frames(10) { listOf(car(2, 1, isBehind = true), car(3, 6)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2), beep(3, 3)), s.heard)
    }

    @Test
    fun `after an urgent warning, a car arriving with no pass is silent below band 3`() {
        val s = Scene()
        // A middle-band car closing fast on a stopped rider: Beep 2, then the
        // urgent once the stop is confirmed. The urgent counts as a 3.
        s.frames(10, bikeSpeedMs = 0f) { listOf(car(1, 12, speedMs = -7f)) }
        assertEquals(listOf(beep(2, 1)), s.heard.filter { it.event is AlertDecider.Event.Beep })
        assertEquals(1, s.heard.count { it.event is AlertDecider.Event.UrgentApproach })
        s.frames(3, bikeSpeedMs = 0f) { emptyList() }
        s.frames(10) { listOf(car(2, 12)) }
        s.frames(10) { listOf(car(2, 6)) }
        assertEquals(beep(3, 2), s.heard.last())
        assertEquals(0, s.heard.count { it == beep(2, 2) })
    }

    @Test
    fun `a reset starts the band afresh, so a far car beeps its own band`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5)) }
        s.d.reset()
        s.frames(10) { listOf(car(2, 18)) }
        assertEquals(listOf(beep(3, 1), beep(1, 2)), s.heard)
    }

    @Test
    fun `the all-clear resets the band, so a far car beeps its own band`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5)) }
        s.frames(30) { emptyList() }
        s.frames(10) { listOf(car(2, 18)) }
        assertEquals(listOf(beep(3, 1), Heard(AlertDecider.Event.Clear, null), beep(1, 2)), s.heard)
    }

    @Test
    fun `a car first seen close behind waits for the ghost filter after a pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 4)) }
        s.frames(3) { listOf(car(1, 3), car(2, 6, speedMs = 0f, bornDistanceM = 6)) }
        // Car 1 passes; car 2 holds the rider's speed, so it shows no closing.
        s.frames(15) { listOf(car(1, 1, isBehind = true), car(2, 6, speedMs = 0f, bornDistanceM = 6)) }
        assertEquals(listOf(beep(3, 1)), s.heard)
        // It starts closing, and the filter lets it through.
        s.frames(10) { listOf(car(2, 5, speedMs = -3f, bornDistanceM = 6)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
        assertEquals(listOf("new_entry", "refire"), s.rules)
    }

    @Test
    fun `each beep names the rule that armed it, and each pass is logged`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 12)) }
        s.frames(3) { listOf(car(1, 6), car(2, 7)) }
        s.frames(10) { listOf(car(1, 1, isBehind = true), car(2, 7)) }
        s.frames(3) { listOf(car(2, 2)) }
        // Car 2 fades beside the rider; car 3 is first seen after it.
        s.frames(10) { listOf(car(3, 12)) }
        assertEquals(listOf(beep(2, 1), beep(3, 1), beep(3, 2), beep(2, 3)), s.heard)
        assertEquals(listOf("new_entry", "escalation", "handover", "new_entry"), s.rules)
        assertEquals(
            listOf(
                "# gate pass tid=1 via=behind d=6 floor_was=3",
                "# gate pass tid=2 via=fade d=2 floor_was=3",
            ),
            s.gateLines.filter { it.startsWith("# gate pass") },
        )
    }

    @Test
    fun `a ghost the filter is holding back fading beside the rider is not a pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 6)) }
        // First seen 2 m behind and holding the rider's speed: the closest,
        // but held back by the ghost filter, so never heard.
        s.frames(10) { listOf(car(1, 6), car(2, 2, speedMs = 0f, bornDistanceM = 2)) }
        s.frames(10) { listOf(car(1, 6)) }
        assertEquals(listOf(beep(3, 1)), s.heard)
        assertEquals(listOf("# gate pass-refused tid=2 via=fade d=2 moving=true ghost=true"), s.gateLines.filter { it.startsWith("# gate pass") })
    }

    @Test
    fun `a ghost the filter is holding back drawing level is not a pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 6)) }
        s.frames(10) { listOf(car(1, 6), car(2, 2, speedMs = 0f, bornDistanceM = 2)) }
        s.frames(10) { listOf(car(1, 6), car(2, 1, speedMs = 0f, bornDistanceM = 2, isBehind = true)) }
        assertEquals(listOf(beep(3, 1)), s.heard)
        assertEquals(listOf("# gate pass-refused tid=2 via=behind d=2 moving=true ghost=true"), s.gateLines.filter { it.startsWith("# gate pass") })
    }

    @Test
    fun `a fade not taken as a pass is logged with why`() {
        fun refusedLines(d: Int, bikeSpeedMs: Float?): List<String> {
            val s = Scene()
            s.frames(3, bikeSpeedMs) { listOf(car(1, 5), car(2, 12)) }
            s.frames(3, bikeSpeedMs) { listOf(car(1, d), car(2, 12)) }
            s.frames(1, bikeSpeedMs) { listOf(car(2, 12)) }
            return s.gateLines.filter { it.startsWith("# gate pass") }
        }
        assertEquals(listOf("# gate pass-refused tid=1 via=fade d=4 moving=true ghost=false"), refusedLines(4, RIDING))
        assertEquals(listOf("# gate pass-refused tid=1 via=fade d=2 moving=unknown ghost=false"), refusedLines(2, null))
        assertEquals(listOf("# gate pass-refused tid=1 via=fade d=2 moving=false ghost=false"), refusedLines(2, 0f))
    }

    @Test
    fun `with no movement signal the band lapses 2 s after the heard car was last in the list`() {
        val s = Scene()

        // Legacy stream, no eBike: no birth stamp and no speed.
        fun car(id: Int, d: Int) = car(id, d, bornAtMs = 0L)
        // Car 1 is heard on the frame it becomes stable, and lost on the next.
        s.frames(2, bikeSpeedMs = null) { listOf(car(1, 5), car(2, 12)) }
        s.frames(19, bikeSpeedMs = null) { listOf(car(2, 12)) }
        assertEquals(listOf(beep(3, 1)), s.heard)
        s.frames(1, bikeSpeedMs = null) { listOf(car(2, 12)) }
        assertEquals(listOf(beep(3, 1), beep(2, 2)), s.heard)
        assertEquals(listOf("new_entry", "floor_lapse"), s.rules)
        assertEquals(
            listOf("# gate floor-lapse floor=3 after_ms=2000 armed=true"),
            s.gateLines.filter { it.startsWith("# gate floor-lapse") },
        )
    }

    @Test
    fun `with no movement signal the band holds while the heard car is still tracked`() {
        // Car 1 drops back behind car 2, into the middle band or out of range
        // altogether, and stays in the track list. On the legacy stream every
        // birth is 0.
        for (droppedToM in listOf(13, 30)) {
            for (birth in listOf(null, 0L)) {
                val s = Scene()
                fun car(id: Int, d: Int) = car(id, d, bornAtMs = birth ?: id.toLong())
                s.frames(3, bikeSpeedMs = null) { listOf(car(1, 6)) }
                s.frames(60, bikeSpeedMs = null) { listOf(car(1, droppedToM), car(2, 12)) }
                assertEquals("d=$droppedToM birth=$birth", listOf(beep(3, 1)), s.heard)
            }
        }
    }

    @Test
    fun `with no movement signal a recycled track id does not hold the band`() {
        val s = Scene()
        s.frames(3, bikeSpeedMs = null) { listOf(car(7, 5, bornAtMs = 100L)) }
        // Track id 7 now belongs to a new car in the middle band.
        s.frames(25, bikeSpeedMs = null) { listOf(car(7, 12, bornAtMs = 200L)) }
        assertEquals(listOf(beep(3, 7), beep(2, 7)), s.heard)
    }

    @Test
    fun `when the band lapses a car already heard at its band is not heard again`() {
        val s = Scene()
        s.frames(3, bikeSpeedMs = null) { listOf(car(2, 12)) }
        // Car 1 overtakes car 2 behind the rider, then is lost.
        s.frames(10, bikeSpeedMs = null) { listOf(car(1, 5), car(2, 12)) }
        s.frames(40, bikeSpeedMs = null) { listOf(car(2, 12)) }
        assertEquals(listOf(beep(2, 2), beep(3, 1)), s.heard)
        assertEquals(listOf("armed=false"), s.gateLines.filter { it.startsWith("# gate floor-lapse") }.map { it.split(" ").last() })
    }

    @Test
    fun `with no movement signal the band holds while any car heard at it is still tracked`() {
        val s = Scene()
        s.frames(3, bikeSpeedMs = null) { listOf(car(1, 6)) }
        // Car 2 overtakes car 1 behind the rider and is heard at 3 too.
        s.frames(10, bikeSpeedMs = null) { listOf(car(1, 6), car(2, 5)) }
        // Car 2 is lost; car 1, heard at 3, is still behind.
        s.frames(30, bikeSpeedMs = null) { listOf(car(1, 6)) }
        // Car 1 is lost too: the band holds for 2 s from then.
        s.frames(19, bikeSpeedMs = null) { listOf(car(3, 12)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2)), s.heard)
        s.frames(1, bikeSpeedMs = null) { listOf(car(3, 12)) }
        assertEquals(listOf(beep(3, 1), beep(3, 2), beep(2, 3)), s.heard)
    }

    @Test
    fun `on the legacy stream a later car under the same track id is taken for the one heard`() {
        val s = Scene()
        s.frames(3, bikeSpeedMs = null) { listOf(car(7, 5, bornAtMs = 0L)) }
        // Track id 7 now belongs to a car in the middle band, with no birth
        // stamp to tell them apart.
        s.frames(40, bikeSpeedMs = null) { listOf(car(7, 12, bornAtMs = 0L)) }
        assertEquals(listOf(beep(3, 7)), s.heard)
        assertEquals(emptyList<String>(), s.gateLines.filter { it.startsWith("# gate floor-lapse") })
    }

    @Test
    fun `with the rider known to be stopped the band never lapses`() {
        fun heard(stoppedSpeedMs: Float?, eBikeNotDriving: Boolean?): List<Heard> {
            val s = Scene()
            s.frames(3) { listOf(car(1, 5), car(2, 12)) }
            s.frames(40, stoppedSpeedMs, eBikeNotDriving) { listOf(car(2, 12, speedMs = 0f)) }
            s.frames(10) { listOf(car(2, 12)) }
            return s.heard
        }
        assertEquals(listOf(beep(3, 1)), heard(stoppedSpeedMs = 0f, eBikeNotDriving = null))
        assertEquals(listOf(beep(3, 1)), heard(stoppedSpeedMs = null, eBikeNotDriving = true))
    }

    @Test
    fun `with the rider's movement known the band never lapses`() {
        fun heard(bikeSpeedMs: Float?, eBikeNotDriving: Boolean?): List<Heard> {
            val s = Scene()
            s.frames(3, bikeSpeedMs, eBikeNotDriving) { listOf(car(1, 5), car(2, 12)) }
            s.frames(60, bikeSpeedMs, eBikeNotDriving) { listOf(car(2, 12)) }
            return s.heard
        }
        assertEquals(listOf(beep(3, 1)), heard(bikeSpeedMs = RIDING, eBikeNotDriving = null))
        assertEquals(listOf(beep(3, 1)), heard(bikeSpeedMs = null, eBikeNotDriving = false))
    }

    @Test
    fun `with no movement signal the band an urgent set holds while its car is tracked`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5, speedMs = 0f)) }
        // Stopped: car 2, a lane over and so not the closest, closes fast
        // enough for the urgent warning.
        s.frames(10, bikeSpeedMs = 0f) { listOf(car(2, 6, speedMs = -7f, rangeXm = 4f), car(1, 5, speedMs = 0f)) }
        assertEquals(1, s.heard.count { it.event is AlertDecider.Event.UrgentApproach && it.tid == 2 })
        // The speed reading is lost and car 1 leaves: car 2 is still tracked.
        s.frames(60, bikeSpeedMs = null) { listOf(car(2, 12, speedMs = -7f, rangeXm = 4f)) }
        assertEquals(listOf(beep(3, 1)), s.heard.filter { it.event is AlertDecider.Event.Beep })
    }

    @Test
    fun `with no movement signal a beep raising the band restarts its 2 s`() {
        val s = Scene()
        s.frames(25, bikeSpeedMs = null) { emptyList() }
        s.frames(3, bikeSpeedMs = null) { listOf(car(1, 12)) }
        // Car 1 is lost; car 3, far back, is held below the band.
        s.frames(15, bikeSpeedMs = null) { listOf(car(3, 18)) }
        // Car 2 is heard at 3 on the frame it becomes stable, and lost on the
        // next.
        s.frames(2, bikeSpeedMs = null) { listOf(car(2, 5), car(3, 18)) }
        assertEquals(listOf(beep(2, 1), beep(3, 2)), s.heard)
        s.frames(19, bikeSpeedMs = null) { listOf(car(3, 18)) }
        assertEquals(listOf(beep(2, 1), beep(3, 2)), s.heard)
        s.frames(1, bikeSpeedMs = null) { listOf(car(3, 18)) }
        assertEquals(listOf(beep(2, 1), beep(3, 2), beep(1, 3)), s.heard)
    }

    /** Car 1 heard at 3, then car 2 closing fast on the stopped rider sets off
     *  the urgent warning; its own beep never sounds. Returns once the urgent
     *  has sounded. */
    private fun Scene.urgentFromCar2(bornAtMs: Long) {
        frames(3) { listOf(car(1, 5)) }
        repeat(20) {
            if (heard.none { it.event is AlertDecider.Event.UrgentApproach }) {
                frames(1, bikeSpeedMs = 0f) { listOf(car(2, 12, speedMs = -7f, bornAtMs = bornAtMs)) }
            }
        }
        val urgent = AlertDecider.Event.UrgentApproach(
            viaMovingPath = false,
            triggerTid = 2,
            triggerDistanceM = 12,
            triggerClosingMs = 7f,
            triggerRangeXm = 0f,
        )
        assertEquals(listOf(beep(3, 1), Heard(urgent, 2)), heard)
    }

    @Test
    fun `with no movement signal an urgent restarts the band's 2 s`() {
        val s = Scene()
        s.urgentFromCar2(bornAtMs = 2L)
        // Car 2 is lost on the next frame and the speed reading with it.
        s.frames(19, bikeSpeedMs = null) { listOf(car(3, 18)) }
        assertEquals(2, s.heard.size)
        s.frames(1, bikeSpeedMs = null) { listOf(car(3, 18)) }
        assertEquals(beep(1, 3), s.heard.last())
    }

    @Test
    fun `with no movement signal a recycled track id does not hold an urgent's band`() {
        val s = Scene()
        s.urgentFromCar2(bornAtMs = 100L)
        // Track id 2 now belongs to a new car far back.
        s.frames(25, bikeSpeedMs = null) { listOf(car(2, 18, bornAtMs = 200L)) }
        assertEquals(beep(1, 2), s.heard.last())
    }

    @Test
    fun `after the all-clear or a reset an urgent's car no longer holds the band`() {
        for (byReset in listOf(false, true)) {
            val s = Scene()
            s.urgentFromCar2(bornAtMs = 2L)
            if (byReset) {
                s.d.reset()
            } else {
                // Car 2 drops back out of range but stays tracked: the
                // all-clear plays.
                s.frames(25, bikeSpeedMs = 0f) { listOf(car(2, 40, speedMs = 0f)) }
                assertEquals(Heard(AlertDecider.Event.Clear, null), s.heard.last())
            }
            s.frames(3) { listOf(car(2, 40, speedMs = 0f), car(3, 12)) }
            assertEquals("byReset=$byReset", beep(2, 3), s.heard.last())
            // The speed reading goes and car 3 is lost; car 2 is still tracked.
            s.frames(25, bikeSpeedMs = null) { listOf(car(2, 40, speedMs = 0f), car(4, 18)) }
            assertEquals("byReset=$byReset", beep(1, 4), s.heard.last())
        }
    }

    @Test
    fun `a new car arriving in front as the closest is a handover`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 12)) }
        s.frames(3) { listOf(car(1, 12), car(2, 5)) }
        assertEquals(listOf(beep(2, 1), beep(3, 2)), s.heard)
        assertEquals(listOf("new_entry", "handover"), s.rules)
    }

    @Test
    fun `a car joining further back as the closest escalates is an escalation`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 12)) }
        // Car 2 becomes stable on the frame car 1 escalates, which relies on
        // the default two-frame sustain.
        s.frames(1) { listOf(car(1, 12), car(2, 13)) }
        s.frames(1) { listOf(car(1, 6), car(2, 13)) }
        assertEquals(listOf(beep(2, 1), beep(3, 1)), s.heard)
        assertEquals(listOf("new_entry", "escalation"), s.rules)
    }

    @Test
    fun `a held car climbing a band short of the floor is logged again`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5), car(2, 18)) }
        s.frames(10) { listOf(car(2, 18)) }
        s.frames(5) { listOf(car(2, 12)) }
        assertEquals(listOf(beep(3, 1)), s.heard)
        assertEquals(
            listOf("tier=1", "tier=2"),
            s.gateLines.filter { it.startsWith("# gate step-down tid=2") }.map { it.split(" ")[4] },
        )
    }

    @Test
    fun `the rule is only reported on the frame that beeps`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 12)) }
        assertEquals("new_entry", s.rules.single())
        s.frames(1) { listOf(car(1, 12)) }
        assertEquals(null, s.d.lastBeepRule)
    }

    @Test
    fun `a beep armed by an urgent that did not sound is labelled urgent`() {
        // Only an urgent sharing the beep cooldown can be armed and not sound.
        val d = AlertDecider(urgentCooldown = UrgentCooldown.SHARED_WITH_BEEPS)
        val c = Clock()
        val rules = mutableListOf<String?>()
        fun frame(v: List<Vehicle>, bikeSpeedMs: Float) {
            if (d.decide(v, ALERT_MAX, c.tick(), bikeSpeedMs = bikeSpeedMs) is AlertDecider.Event.Beep) rules += d.lastBeepRule
        }
        repeat(6) { frame(emptyList(), 0f) }
        repeat(2) { frame(listOf(car(1, 12, speedMs = -1f)), 0f) }
        // A burst of closing speed inside the cooldown: the urgent waits.
        frame(listOf(car(1, 12, speedMs = -7f)), 0f)
        repeat(15) { frame(listOf(car(1, 12, speedMs = -1f)), 0f) }
        frame(listOf(car(1, 12, speedMs = -1f)), RIDING)
        assertEquals(listOf("new_entry", "urgent"), rules)
    }

    @Test
    fun `on the legacy stream a closest fading beside a moving rider hands over`() {
        val s = Scene()
        s.frames(3) { listOf(car(1, 5, bornAtMs = 0L), car(2, 12, bornAtMs = 0L)) }
        s.frames(3) { listOf(car(1, 2, bornAtMs = 0L), car(2, 12, bornAtMs = 0L)) }
        s.frames(10) { listOf(car(2, 12, bornAtMs = 0L)) }
        assertEquals(listOf(beep(3, 1), beep(2, 2)), s.heard)
    }

    @Test
    fun `a fade at the pass distance counts, one metre further does not`() {
        fun heardAfterFadeAt(distanceM: Int): List<Heard> {
            val s = Scene()
            s.frames(3) { listOf(car(1, 5), car(2, 12)) }
            s.frames(3) { listOf(car(1, distanceM), car(2, 12)) }
            s.frames(10) { listOf(car(2, 12)) }
            return s.heard
        }
        assertEquals(listOf(beep(3, 1), beep(2, 2)), heardAfterFadeAt(3))
        assertEquals(listOf(beep(3, 1)), heardAfterFadeAt(4))
    }

    @Test
    fun `the closest fading as its track id passes to a new car is still a pass`() {
        val s = Scene()
        s.frames(3) { listOf(car(7, 5, bornAtMs = 100L), car(2, 12)) }
        s.frames(3) { listOf(car(7, 2, bornAtMs = 100L), car(2, 12)) }
        // Car 7 fades beside the rider and its id goes to a new car far back.
        s.frames(10) { listOf(car(7, 19, bornAtMs = 200L), car(2, 12)) }
        assertEquals(listOf(beep(3, 7), beep(2, 2)), s.heard)
    }

    @Test
    fun `a beep held below the floor is logged once`() {
        val lines = mutableListOf<String>()
        val d = AlertDecider(onGateEvent = { lines += it })
        val c = Clock()
        repeat(3) { d.decide(listOf(car(1, 5), car(2, 12)), ALERT_MAX, c.tick(), bikeSpeedMs = RIDING) }
        repeat(20) { d.decide(listOf(car(2, 12)), ALERT_MAX, c.tick(), bikeSpeedMs = RIDING) }
        assertEquals(1, lines.count { it.startsWith("# gate step-down tid=2 tier=2 floor=3") })
    }

    @Test
    fun `a beep held below the floor does not delay the next one`() {
        val s = Scene()
        s.frames(13) { listOf(car(1, 5), car(2, 13), car(3, 12)) }
        // Car 1 leaves 5 m back: car 3's band-2 beep is held below the floor.
        s.frames(1) { listOf(car(2, 13), car(3, 12)) }
        // Car 3 passes on the next frame. Car 2 is heard straight away: the
        // held beep sounded nothing, so it started no cooldown.
        s.frames(1) { listOf(car(2, 13), car(3, 1, isBehind = true)) }
        assertEquals(listOf(beep(3, 1), beep(2, 2)), s.heard)
    }

    private companion object {
        const val RIDING = 6f

        /** Bands: 3 within 7 m, 2 within 14 m, 1 within 21 m. */
        const val ALERT_MAX = 21
    }
}
