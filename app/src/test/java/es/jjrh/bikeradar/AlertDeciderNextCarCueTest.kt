// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The next-car cue: a vehicle that has not been covered at its current tier
 * gets one Beep at its own tier once it is within two thirds of the alert
 * distance, even when another vehicle is closer. Scenes that compare run the
 * cue on and off; the off runs are what the closest-car rules alone give.
 */
class AlertDeciderNextCarCueTest {

    private val alertMax = 21 // thirds at 7 and 14 m

    private class Clock(val dtMs: Long = 100L) {
        var now = 0L
        fun tick(): Long = now.also { now += dtMs }
        fun jump(ms: Long) {
            now += ms
        }
    }

    private fun car(
        id: Int,
        distanceM: Int,
        rangeXm: Float = 0f,
        speedMs: Float = -2f,
        bornAtMs: Long = 1L,
        isBehind: Boolean = false,
    ) = Vehicle(id = id, distanceM = distanceM, speedMs = speedMs, rangeXm = rangeXm, isBehind = isBehind, bornAtMs = bornAtMs)

    /** One audible event and the vehicle the decider attributed it to. */
    private data class Heard(val event: AlertDecider.Event, val tid: Int?, val nextCar: Boolean)

    private fun AlertDecider.heard(
        vs: List<Vehicle>,
        c: Clock,
        on: Boolean,
        bikeSpeedMs: Float? = 6f,
        bikeNotDriving: Boolean? = null,
        alertMaxM: Int = alertMax,
    ): Heard? {
        val ev = decide(vs, alertMaxM, c.tick(), bikeSpeedMs = bikeSpeedMs, bikeNotDriving = bikeNotDriving, nextCarCueEnabled = on)
        return if (ev == AlertDecider.Event.None) null else Heard(ev, lastTierTrigger?.id, lastCueWasNextCar)
    }

    private fun beep(count: Int, tid: Int, nextCar: Boolean) = Heard(AlertDecider.Event.Beep(count), tid, nextCar)

    /** Car 1 sounded at tier 3, then car 2 arrives at tier 2 and holds there. */
    private fun secondCarScene(on: Boolean): List<Heard> {
        val d = AlertDecider()
        val c = Clock()
        val out = mutableListOf<Heard>()
        repeat(2) { d.heard(listOf(car(1, 5)), c, on)?.let(out::add) }
        repeat(15) { d.heard(listOf(car(1, 4), car(2, 12)), c, on)?.let(out::add) }
        return out
    }

    @Test
    fun `a second car arriving at the tier already sounded gets its own beep`() {
        assertEquals(listOf(beep(3, 1, false), beep(2, 2, true)), secondCarScene(on = true))
        assertEquals(listOf(beep(3, 1, false)), secondCarScene(on = false))
    }

    @Test
    fun `an announced car sounds again only on a tier raise, never twice at one tier`() {
        val d = AlertDecider()
        val c = Clock()
        val heard = mutableListOf<Heard>()
        repeat(2) { d.heard(listOf(car(1, 3)), c, true)?.let(heard::add) }
        // Car 2 holds tier 2, wobbling across distances inside it.
        for (dist in listOf(13, 12, 13, 11, 12, 13, 12, 11, 12, 13)) d.heard(listOf(car(1, 3), car(2, dist)), c, true)?.let(heard::add)
        // Then it reaches the near third while car 1 is still closer.
        repeat(10) { d.heard(listOf(car(1, 2), car(2, 6)), c, true)?.let(heard::add) }
        assertEquals(listOf(beep(3, 1, false), beep(2, 2, true), beep(3, 2, true)), heard)
    }

    @Test
    fun `a recycled tid is a new car`() {
        val d = AlertDecider()
        val c = Clock()
        val heard = mutableListOf<Heard>()
        repeat(2) { d.heard(listOf(car(1, 3)), c, true)?.let(heard::add) }
        repeat(10) { d.heard(listOf(car(1, 3), car(2, 12, bornAtMs = 1_000L)), c, true)?.let(heard::add) }
        // Car 1 keeps the episode open, so nothing is cleared in between.
        repeat(10) { d.heard(listOf(car(1, 3), car(2, 12, bornAtMs = 9_000L)), c, true)?.let(heard::add) }
        assertEquals(listOf(1, 2, 2), heard.map { it.tid })
    }

    /** Car 1 sounds at tier 3; car 5 is announced at tier 2 and leaves with
     *  car 1; inside the all-clear grace a new car takes tid 5 at tier 1. */
    private fun recycledAfterAnnouncement(firstBorn: Long, secondBorn: Long, lateralUnknown: Boolean, bikeSpeedMs: Float?): List<Pair<Int?, AlertDecider.Event>> {
        val d = AlertDecider()
        val c = Clock()
        fun v(id: Int, dist: Int, born: Long) = Vehicle(id = id, distanceM = dist, speedMs = if (lateralUnknown) 0f else -2f, lateralUnknown = lateralUnknown, bornAtMs = born)
        val heard = mutableListOf<Pair<Int?, AlertDecider.Event>>()
        fun frame(vs: List<Vehicle>) = d.heard(vs, c, true, bikeSpeedMs = bikeSpeedMs)?.let { heard += it.tid to it.event }
        repeat(2) { frame(listOf(v(1, 3, firstBorn))) }
        repeat(8) { frame(listOf(v(1, 3, firstBorn), v(5, 12, firstBorn))) }
        repeat(2) { frame(emptyList()) }
        repeat(5) { frame(listOf(v(5, 18, secondBorn))) }
        return heard
    }

    @Test
    fun `a recycled tid keeps its closest-car beeps after a next-car cue`() {
        assertEquals(
            listOf(1 to AlertDecider.Event.Beep(3), 5 to AlertDecider.Event.Beep(2), 5 to AlertDecider.Event.Beep(1)),
            recycledAfterAnnouncement(firstBorn = 1L, secondBorn = 9_000L, lateralUnknown = false, bikeSpeedMs = 6f),
        )
    }

    @Test
    fun `a legacy-stream recycled tid keeps its closest-car beeps`() {
        // Range-only frames: no birth stamp, no lateral, no closing speed, no
        // rider speed. The cue still announces car 5, and sets no latch the
        // next car under its tid would inherit.
        assertEquals(
            listOf(1 to AlertDecider.Event.Beep(3), 5 to AlertDecider.Event.Beep(2), 5 to AlertDecider.Event.Beep(1)),
            recycledAfterAnnouncement(firstBorn = 0L, secondBorn = 0L, lateralUnknown = true, bikeSpeedMs = null),
        )
    }

    @Test
    fun `a car that triggered an urgent cue is not announced again at tier 3`() {
        val d = AlertDecider()
        val c = Clock()
        val fast = car(1, 5, speedMs = -10.5f)
        // 3 s: past the 1.4 s slow-band cooldown a tier beep would wait for,
        // short of the urgent's own 3 s repeat.
        val events = (1..30).map { d.decide(listOf(fast), alertMax, c.tick(), bikeSpeedMs = 4f) }
        assertTrue("the moving-path urgent must fire, got $events", events.any { it is AlertDecider.Event.UrgentApproach })
        assertFalse("no tier beep may follow the urgent for the same car, got $events", events.any { it is AlertDecider.Event.Beep })
    }

    @Test
    fun `an urgent cue from the middle band covers its car at tier 3`() {
        val d = AlertDecider()
        val c = Clock()
        val tids = mutableListOf<Int?>()
        var urgentFrom: Int? = null
        fun frame(vs: List<Vehicle>) {
            val ev = d.decide(vs, alertMax, c.tick(), bikeSpeedMs = 4f)
            if (ev is AlertDecider.Event.Beep) tids += d.lastTierTrigger?.id
            if (ev is AlertDecider.Event.UrgentApproach && urgentFrom == null) urgentFrom = ev.triggerDistanceM
        }
        repeat(2) { frame(listOf(car(1, 4))) }
        // Car 2 closes fast from 12 m, the middle band, behind a slower car 1,
        // and then holds in the near third.
        repeat(2) { frame(listOf(car(1, 4), car(2, 12, speedMs = -10.5f))) }
        repeat(25) { frame(listOf(car(1, 4), car(2, 6, speedMs = -10.5f))) }
        assertEquals(12, urgentFrom)
        assertEquals(listOf<Int?>(1), tids)
    }

    /** Car 1 announced, rider stops, car 2 arrives in the stop, rider moves
     *  off. [stop] gives the rider's speed and eBike signal for a stopped frame. */
    private fun stoppedScene(stop: (AlertDecider, List<Vehicle>, Clock) -> Heard?): List<Heard> {
        val d = AlertDecider()
        val c = Clock()
        val heard = mutableListOf<Heard>()
        repeat(2) { d.heard(listOf(car(1, 4)), c, true)?.let(heard::add) }
        // Stopped past the dwell, with nothing left pending.
        repeat(25) { stop(d, listOf(car(1, 4)), c)?.let(heard::add) }
        repeat(20) { stop(d, listOf(car(1, 4), car(2, 12)), c)?.let(heard::add) }
        repeat(3) { d.heard(listOf(car(1, 4), car(2, 12)), c, true)?.let(heard::add) }
        return heard
    }

    @Test
    fun `a car arriving while the rider is stopped is announced only on moving off`() {
        val expected = listOf(beep(3, 1, false), beep(2, 2, true))
        assertEquals(expected, stoppedScene { d, vs, c -> d.heard(vs, c, true, bikeSpeedMs = 0f) })
        assertEquals(expected, stoppedScene { d, vs, c -> d.heard(vs, c, true, bikeSpeedMs = 6f, bikeNotDriving = true) })
    }

    @Test
    fun `the cue keeps sounding until the rider has been stopped for the full dwell`() {
        // The rider stops after the frame at 100 ms. A car arriving early in
        // the stop is announced once the slow-band cooldown allows; one
        // arriving after the 2 s dwell waits for moving off.
        fun heardAt(arriveAtMs: Long): List<Pair<Long, Int?>> {
            val d = AlertDecider()
            val c = Clock()
            val out = mutableListOf<Pair<Long, Int?>>()
            while (c.now <= 3_000L) {
                val t = c.now
                val vs = if (t >= arriveAtMs) listOf(car(1, 4), car(2, 12)) else listOf(car(1, 4))
                d.heard(vs, c, true, bikeSpeedMs = if (t < 200L) 6f else 0f)?.let { out += t to it.tid }
            }
            return out
        }
        assertEquals(listOf(100L to 1, 1_500L to 2), heardAt(arriveAtMs = 900L))
        assertEquals(listOf(100L to 1), heardAt(arriveAtMs = 2_200L))
    }

    @Test
    fun `the cue waits out the speed-scaled cooldown the last beep started`() {
        val d = AlertDecider()
        val c = Clock()
        val heardAt = mutableListOf<Pair<Long, Int?>>()
        fun frame(vs: List<Vehicle>) {
            val t = c.now
            // 4 m/s is in the slow band, where the cooldown is 1.4 s.
            d.heard(vs, c, true, bikeSpeedMs = 4f)?.let { heardAt += t to it.tid }
        }
        repeat(2) { frame(listOf(car(1, 4))) }
        repeat(20) { frame(listOf(car(1, 4), car(2, 12))) }
        assertEquals(listOf(100L to 1, 1_500L to 2), heardAt)
    }

    @Test
    fun `a closest-car beep falling due inside a next-car cue's cooldown waits for it`() {
        // Intended: the next-car cue starts the ordinary cooldown, so the
        // admitted ghost's beep comes 700 ms after it rather than on the
        // admission frame. Delayed, not dropped.
        val d = AlertDecider()
        val c = Clock()
        val heardAt = mutableListOf<Triple<Long, Int?, Boolean>>()
        fun frame(ghostClosing: Float) {
            val t = c.now
            val ghost = Vehicle(id = 5, distanceM = 5, speedMs = ghostClosing, bornDistanceM = 5, bornInformative = true, bornAtMs = 1L)
            d.heard(listOf(ghost, car(2, 12)), c, true)?.let { heardAt += Triple(t, it.tid, it.nextCar) }
        }
        repeat(3) { frame(0f) } // the ghost's beep is suppressed; car 2 is announced at 200
        repeat(10) { frame(-3f) } // closing evidence admits the ghost at 400
        assertEquals(listOf(Triple(200L, 2, true), Triple(900L, 5, false)), heardAt)
    }

    @Test
    fun `the nearest of several unannounced cars goes first`() {
        val d = AlertDecider()
        val c = Clock()
        repeat(2) { d.heard(listOf(car(1, 3)), c, true) }
        val heard = (1..30).mapNotNull { d.heard(listOf(car(1, 3), car(2, 13), car(3, 10)), c, true) }
        assertEquals(listOf(3, 2), heard.map { it.tid })
    }

    @Test
    fun `the nearest is judged on true range`() {
        // Car 2 is nearer along the bike (9 m) but 6 m to the side, 10.8 m
        // away; car 3 is 10 m straight back.
        val d = AlertDecider()
        val c = Clock()
        repeat(2) { d.heard(listOf(car(1, 3)), c, true) }
        val heard = (1..30).mapNotNull { d.heard(listOf(car(1, 3), car(2, 9, rangeXm = 6f), car(3, 10)), c, true) }
        assertEquals(listOf(3, 2), heard.map { it.tid })
    }

    @Test
    fun `a closest-car beep after a next-car cue is not labelled next-car`() {
        val d = AlertDecider()
        val c = Clock()
        val heard = mutableListOf<Heard>()
        repeat(2) { d.heard(listOf(car(1, 10)), c, true)?.let(heard::add) }
        repeat(12) { d.heard(listOf(car(1, 10), car(2, 12)), c, true)?.let(heard::add) }
        repeat(2) { d.heard(listOf(car(1, 5), car(2, 12)), c, true)?.let(heard::add) }
        assertEquals(listOf(beep(2, 1, false), beep(2, 2, true), beep(3, 1, false)), heard)
    }

    /** Car 2 is announced at tier 3 behind car 1; car 1 passes, and car 2 flaps
     *  out to tier 2 and back as the closest. */
    private fun announcedThenClosest(legacy: Boolean): List<Heard> {
        val d = AlertDecider()
        val c = Clock()
        val heard = mutableListOf<Heard>()
        fun v(id: Int, dist: Int, isBehind: Boolean = false) = if (legacy) {
            Vehicle(id = id, distanceM = dist, speedMs = 0f, lateralUnknown = true, isBehind = isBehind, bornAtMs = 0L)
        } else {
            car(id, dist, isBehind = isBehind)
        }
        fun frame(vs: List<Vehicle>) = d.heard(vs, c, true, bikeSpeedMs = if (legacy) null else 6f)?.let(heard::add)
        repeat(2) { frame(listOf(v(1, 4))) }
        repeat(12) { frame(listOf(v(1, 4), v(2, 6))) }
        repeat(3) { frame(listOf(v(1, 0, isBehind = true), v(2, 6))) }
        repeat(3) { frame(listOf(v(2, 9))) }
        repeat(12) { frame(listOf(v(2, 6))) }
        return heard
    }

    @Test
    fun `a car announced by the cue keeps its tier latch once it is the closest`() {
        assertEquals(listOf(beep(3, 1, false), beep(3, 2, true)), announcedThenClosest(legacy = false))
    }

    @Test
    fun `on the legacy stream a car announced by the cue is beeped once more at its tier as the closest`() {
        // Intended: with no birth stamp the cue sets no latch, since one could
        // not be told from a recycled tid's and would cost that car its beeps.
        // The price is this one repeat.
        assertEquals(
            listOf(beep(3, 1, false), beep(3, 2, true), beep(3, 2, false)),
            announcedThenClosest(legacy = true),
        )
    }

    @Test
    fun `the cue scores a car on its true range, not its distance along the bike`() {
        fun heardFor(other: Vehicle): List<Heard> {
            val d = AlertDecider()
            val c = Clock()
            repeat(2) { d.heard(listOf(car(1, 3)), c, true) }
            return (1..15).mapNotNull { d.heard(listOf(car(1, 3), other), c, true) }
        }
        // 14 m back but 4 m to the side is 14.6 m away: the far third.
        assertEquals(emptyList<Heard>(), heardFor(car(2, 14, rangeXm = 4f)))
        // 7 m back and 4 m to the side is 8.1 m away: the middle third.
        assertEquals(listOf(beep(2, 2, true)), heardFor(car(2, 7, rangeXm = 4f)))
        // An unmeasured lateral is not honoured: 14 m back scores as 14 m.
        assertEquals(listOf(beep(2, 2, true)), heardFor(car(2, 14, rangeXm = 8f).copy(lateralUnknown = true)))
    }

    @Test
    fun `the cue starts at two thirds of the alert distance, inclusive`() {
        fun announcedAt(dist: Int): Boolean {
            val d = AlertDecider()
            val c = Clock()
            repeat(2) { d.heard(listOf(car(1, 3)), c, true) }
            return (1..15).any { d.heard(listOf(car(1, 3), car(2, dist)), c, true)?.tid == 2 }
        }
        assertTrue(announcedAt(14))
        assertFalse(announcedAt(15))
    }

    @Test
    fun `a car beside the rider or past them, off the road, or born-close-gated is never announced`() {
        val ghost = Vehicle(id = 5, distanceM = 6, speedMs = -0.5f, bornDistanceM = 6, bornInformative = true, bornAtMs = 1L)
        val excluded = listOf(
            Vehicle(id = 2, distanceM = 5, speedMs = 0f, isAlongsideStationary = true, bornAtMs = 1L),
            car(3, 4, isBehind = true),
            Vehicle(id = 4, distanceM = 6, speedMs = -2f, rangeXm = 11f, rangeXmRaw = 11f, bornAtMs = 1L),
            ghost,
        )
        val d = AlertDecider()
        val c = Clock()
        val heard = (1..30).mapNotNull { d.heard(listOf(car(1, 3)) + excluded, c, true) }
        assertEquals(listOf(1), heard.map { it.tid })
    }

    @Test
    fun `a closest-car tier raise wins the frame and the other car follows after the cooldown`() {
        val d = AlertDecider()
        val c = Clock()
        val heard = mutableListOf<Heard>()
        repeat(2) { d.heard(listOf(car(1, 10)), c, true)?.let(heard::add) } // Beep(2) for car 1
        c.jump(1_000)
        d.heard(listOf(car(1, 10), car(2, 12)), c, true)?.let(heard::add)
        // Car 1 rises into the near third on the frame car 2 becomes eligible,
        // with the cooldown long past for both.
        repeat(12) { d.heard(listOf(car(1, 5), car(2, 12)), c, true)?.let(heard::add) }
        assertEquals(listOf(beep(2, 1, false), beep(3, 1, false), beep(2, 2, true)), heard)
    }

    @Test
    fun `the capture fields name the announced car and its own distance`() {
        val d = AlertDecider()
        val c = Clock()
        repeat(2) { d.heard(listOf(car(1, 3)), c, true) }
        val other = car(2, 12, rangeXm = 3f)
        val ev = (1..15).firstNotNullOfOrNull { d.heard(listOf(car(1, 3), other), c, true) }
        assertEquals(beep(2, 2, true), ev)
        assertEquals(12.369f, d.lastTierDistanceM, 1e-3f)
    }

    @Test
    fun `with no speed signal the cue still sounds`() {
        val d = AlertDecider()
        val c = Clock()
        repeat(2) { d.heard(listOf(car(1, 3)), c, true, bikeSpeedMs = null) }
        val heard = (1..15).mapNotNull { d.heard(listOf(car(1, 3), car(2, 12)), c, true, bikeSpeedMs = null) }
        assertEquals(listOf(2), heard.map { it.tid })
    }

    @Test
    fun `the all-clear and reset both forget who was announced`() {
        fun announcesCar2After(forget: (AlertDecider, Clock) -> Unit): Boolean {
            val d = AlertDecider()
            val c = Clock()
            repeat(2) { d.heard(listOf(car(1, 3)), c, true) }
            repeat(15) { d.heard(listOf(car(1, 3), car(2, 12)), c, true) }
            forget(d, c)
            repeat(2) { d.heard(listOf(car(1, 3)), c, true) }
            return (1..15).any { d.heard(listOf(car(1, 3), car(2, 12)), c, true)?.tid == 2 }
        }
        assertTrue(
            "after the all-clear",
            announcesCar2After { d, c ->
                d.heard(emptyList(), c, true)
                c.jump(2_500)
                assertEquals(AlertDecider.Event.Clear, d.decide(emptyList(), alertMax, c.tick()))
            },
        )
        assertTrue("after reset", announcesCar2After { d, _ -> d.reset() })
        assertFalse("without either", announcesCar2After { _, _ -> })
    }

    @Test
    fun `an urgent cue lands on the same frame whether or not a next-car cue just sounded`() {
        // Riding at 4 m/s, inside the low-speed urgent extension. Car 2's
        // next-car cue at 1500 starts a 1.4 s cooldown; car 3 closing at
        // 10.5 m/s qualifies 200 ms later and must not wait for it.
        fun events(on: Boolean): List<Pair<Long, String>> {
            val d = AlertDecider()
            val c = Clock()
            val out = mutableListOf<Pair<Long, String>>()
            while (c.now <= 2_500L) {
                val t = c.now
                val vs = buildList {
                    add(car(1, 4))
                    if (t >= 200L) add(car(2, 12))
                    if (t >= 1_600L) add(car(3, 10, speedMs = -10.5f))
                }
                when (val ev = d.decide(vs, alertMax, c.tick(), bikeSpeedMs = 4f, nextCarCueEnabled = on)) {
                    is AlertDecider.Event.Beep -> out += t to "B${ev.count}"
                    is AlertDecider.Event.UrgentApproach -> out += t to "U${ev.triggerTid}"
                    else -> {}
                }
            }
            return out
        }
        assertEquals(listOf(100L to "B3", 1_500L to "B2", 1_700L to "U3"), events(on = true))
        assertEquals(listOf(100L to "B3", 1_700L to "U3"), events(on = false))
    }

    @Test
    fun `the cacophony truck scene with the cue on`() {
        // Same frames as AlertDeciderTest's cacophony test, which pins <= 3
        // beeps with the cue off. With it on, tids 111 and 124 each get a
        // two-beep and a three-beep cue: three more than with it off. Its
        // vehicles carry no birth stamp, so this is the legacy-stream path.
        val d = AlertDecider()
        val heard = mutableListOf<Pair<Int, Int>>()
        var t = 0L
        fun feed(vs: List<Vehicle>) {
            val ev = d.decide(vs, 30, t)
            if (ev is AlertDecider.Event.Beep) heard += d.lastTierTrigger!!.id to ev.count
            t += 200
        }
        fun scene(vs: List<Vehicle>) {
            feed(vs)
            feed(vs)
        }
        fun c(id: Int, dist: Int) = Vehicle(id = id, distanceM = dist, speedMs = 0f)
        scene(listOf(c(51, 16), c(111, 27)))
        t += 5_500
        scene(listOf(c(51, 10), c(111, 27)))
        t += 4_800
        scene(listOf(c(111, 13)))
        t += 700
        scene(listOf(c(51, 0), c(111, 9)))
        t += 900
        scene(listOf(c(51, 0), c(111, 3), c(124, 12)))
        t += 700
        scene(listOf(c(51, 0), c(124, 2)))
        t += 1_500
        scene(listOf(c(51, 0)))
        assertEquals(listOf(51 to 2, 51 to 3, 111 to 2, 111 to 3, 124 to 2, 124 to 3), heard)
    }
}
