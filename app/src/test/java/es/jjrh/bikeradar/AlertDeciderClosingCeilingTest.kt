// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import es.jjrh.bikeradar.AlertDecider.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A target closing faster than the ceiling gets no cue, but stays present for
 * the all-clear. The phantoms below are shaped like those seen in ride
 * captures: one reading held on every frame while the range falls smoothly.
 */
class AlertDeciderClosingCeilingTest {

    private val alertMax = 30
    private val frameMs = 200L

    private fun target(id: Int, distanceM: Int, closingMs: Float, bornAtMs: Long = 0L) = Vehicle(id = id, distanceM = distanceM, speedMs = -closingMs, bornAtMs = bornAtMs)

    private fun run(
        frames: List<List<Vehicle>>,
        bikeSpeedMs: Float?,
        ceiling: Float?,
        gateLines: MutableList<String> = mutableListOf(),
    ): List<Event> {
        val d = AlertDecider(onGateEvent = { gateLines.add(it) })
        // Nearest first, as the decoder's snapshot orders them: the urgent
        // search takes the first qualifying target, so an unsorted fixture
        // can put a real car ahead of a closer phantom and hide the masking.
        return frames.mapIndexed { i, vs ->
            d.decide(vs.sortedBy { it.distanceM }, alertMax, 600L + i * frameMs, bikeSpeedMs = bikeSpeedMs, closingCeilingMs = ceiling)
        }
    }

    private fun List<Event>.audible() = filter { it != Event.None }

    /** A phantom held at 42.5 m/s from 83 m, then gone, then three quiet
     *  seconds. */
    private val phantom42: List<List<Vehicle>> =
        listOf(83, 74, 66, 57, 49, 40, 32, 23, 15, 6).map { listOf(target(85, it, 42.5f)) } +
            List(15) { emptyList() }

    /** A phantom held at 47 m/s from 126 m to a few metres. */
    private val phantom47: List<List<Vehicle>> =
        List(5) { emptyList<Vehicle>() } +
            listOf(126, 117, 107, 98, 89, 79, 70, 60, 51, 42, 32, 23, 13, 4).map { listOf(target(135, it, 47f)) } +
            List(15) { emptyList() }

    @Test fun `a phantom over the ceiling gets no beep and leaves no all-clear`() {
        assertEquals(emptyList<Event>(), run(phantom42, bikeSpeedMs = 5f, ceiling = 40f).audible())
    }

    @Test fun `a phantom over the ceiling gets no urgent cue behind a stopped rider`() {
        assertEquals(emptyList<Event>(), run(phantom47, bikeSpeedMs = 0f, ceiling = 40f).audible())
    }

    @Test fun `with no rider speed at all the phantom is still silenced`() {
        // A radar-only rider whose radar has sent no speed yet: only the beep
        // path runs, and the ceiling must not depend on a speed being known.
        assertTrue(run(phantom42, bikeSpeedMs = null, ceiling = null).any { it is Event.Beep })
        assertEquals(emptyList<Event>(), run(phantom42, bikeSpeedMs = null, ceiling = 40f).audible())
    }

    @Test fun `with no limit the same phantom fires the urgent cue`() {
        assertTrue(run(phantom47, bikeSpeedMs = 0f, ceiling = null).any { it is Event.UrgentApproach })
    }

    @Test fun `a ceiling of zero or below means no limit`() {
        assertTrue(run(phantom47, bikeSpeedMs = 0f, ceiling = 0f).any { it is Event.UrgentApproach })
        assertTrue(run(phantom47, bikeSpeedMs = 0f, ceiling = -5f).any { it is Event.UrgentApproach })
    }

    @Test fun `the rider's ceiling is the one applied`() {
        // 42.5 m/s is under a 45 m/s ceiling, so the phantom beeps there.
        assertTrue(run(phantom42, bikeSpeedMs = 5f, ceiling = 45f).any { it is Event.Beep })
    }

    @Test fun `exactly at the ceiling still cues, half a metre per second over does not`() {
        fun approach(closingMs: Float) = listOf(40, 32, 24, 16, 8).map { listOf(target(9, it, closingMs)) }
        assertTrue(run(approach(40f), bikeSpeedMs = 5f, ceiling = 40f).any { it is Event.Beep })
        assertEquals(emptyList<Event>(), run(approach(40.5f), bikeSpeedMs = 5f, ceiling = 40f).audible())
    }

    @Test fun `exactly at the ceiling the urgent cue still fires behind a stopped rider`() {
        val approach = List(5) { emptyList<Vehicle>() } + listOf(30, 22, 14, 6).map { listOf(target(9, it, 40f)) }
        assertTrue(run(approach, bikeSpeedMs = 0f, ceiling = 40f).any { it is Event.UrgentApproach })
    }

    @Test fun `omitting the ceiling applies the shipped default`() {
        val d = AlertDecider()
        val events = phantom42.mapIndexed { i, vs -> d.decide(vs, alertMax, 600L + i * frameMs, bikeSpeedMs = 5f) }
        assertEquals(emptyList<Event>(), events.audible())
    }

    @Test fun `a phantom closer than a real car changes nothing about the real car's beeps`() {
        // The audio voices only the closest target, so a phantom that stayed in
        // the running would take that place and the real car would go unheard.
        val real = (0..30).map { listOf(target(7, 40 - it, 5f)) } + List(15) { emptyList() }
        val withGhost = real.mapIndexed { i, vs -> if (i <= 30) vs + target(85, 8, 45f) else vs }
        val alone = run(real, bikeSpeedMs = 5f, ceiling = 40f)
        assertTrue("the real car must beep on its own", alone.any { it is Event.Beep })
        assertEquals(alone, run(withGhost, bikeSpeedMs = 5f, ceiling = 40f))
    }

    @Test fun `a phantom closer than a real fast closer changes nothing about the urgent cue`() {
        val approach = listOf(30, 28, 25, 23, 21, 18, 16, 13, 11, 9, 6, 4)
        val real = List(5) { emptyList<Vehicle>() } +
            approach.map { listOf(target(7, it, 12f)) } +
            List(15) { emptyList() }
        val withGhost = real.mapIndexed { i, vs -> if (i in 5 until 5 + approach.size) vs + target(85, 5, 46f) else vs }
        val alone = run(real, bikeSpeedMs = 0f, ceiling = 40f)
        assertTrue("the real car must fire the urgent cue on its own", alone.any { it is Event.UrgentApproach })
        assertEquals(alone, run(withGhost, bikeSpeedMs = 0f, ceiling = 40f))
    }

    @Test fun `one spiked reading does not silence a real car for the rest of its approach`() {
        // Frame 15 sits at 25 m, mid tier 1 and away from any tier boundary.
        val real = (0..30).map { listOf(target(7, 40 - it, 5f)) } + List(15) { emptyList() }
        val spiked = real.mapIndexed { i, vs -> if (i == 15) listOf(target(7, 25, 45f)) else vs }
        val alone = run(real, bikeSpeedMs = 5f, ceiling = 40f)
        assertTrue("the real car must beep on its own", alone.count { it is Event.Beep } >= 2)
        assertEquals(alone, run(spiked, bikeSpeedMs = 5f, ceiling = 40f))
    }

    @Test fun `one spiked reading does not delay a confirmed car's urgent cue`() {
        // 8 m/s behind a stopped rider: confirmed from 28 m, and the time to
        // collision first reaches 3 s at 24 m, the frame after the spike.
        val approach = listOf(30, 28, 27, 25, 24, 22, 20, 19, 17)
        val real = List(5) { emptyList<Vehicle>() } + approach.map { listOf(target(7, it, 8f)) }
        val spiked = real.mapIndexed { i, vs -> if (i == 8) listOf(target(7, 25, 45f)) else vs }
        val alone = run(real, bikeSpeedMs = 0f, ceiling = 40f)
        val firstAlone = alone.indexOfFirst { it is Event.UrgentApproach }
        assertEquals("the real car must fire on the frame after the spike", 9, firstAlone)
        assertEquals(firstAlone, run(spiked, bikeSpeedMs = 0f, ceiling = 40f).indexOfFirst { it is Event.UrgentApproach })
    }

    @Test fun `a spiked reading does not knock a car out of the exit band`() {
        // In at 30 m, then lingering at 32 m inside the 3 m exit band. One
        // reading over the ceiling must not cost it the band.
        val d = AlertDecider()
        fun step(i: Int, closingMs: Float, at: Int) = d.decide(listOf(target(7, at, closingMs)), alertMax, 600L + i * frameMs, bikeSpeedMs = 5f, closingCeilingMs = 40f)
        step(0, 1f, 30)
        step(1, 1f, 30)
        step(2, 1f, 32)
        step(3, 45f, 32)
        assertNull("the spiked frame itself has no tier trigger", d.lastTierTrigger)
        step(4, 1f, 32)
        assertEquals(7, d.lastTierTrigger?.id)
    }

    @Test fun `a phantom still behind the rider holds back the all-clear`() {
        // A delayed all-clear is preferred to a false one, and a reading over
        // the ceiling may yet be a real car.
        val real = (0..20).map { listOf(target(7, 40 - it, 5f)) }
        val ghostStays = List(20) { listOf(target(85, 20, 44f)) }
        val events = run(real + ghostStays + List(15) { emptyList() }, bikeSpeedMs = 5f, ceiling = 40f)
        val clearAt = events.indexOfFirst { it == Event.Clear }
        assertTrue("expected a beep for the real car", events.any { it is Event.Beep })
        assertTrue("the all-clear must wait for the phantom to go, fired at frame $clearAt", clearAt >= real.size + ghostStays.size)
    }

    @Test fun `each silenced track is logged once, at its first frame in range`() {
        val lines = mutableListOf<String>()
        run(phantom42, bikeSpeedMs = 5f, ceiling = 40f, gateLines = lines)
        val ceilingLines = lines.filter { it.startsWith("# gate ceiling ") }
        assertEquals("expected one line for the one track, got $lines", 1, ceilingLines.size)
        assertEquals("# gate ceiling tid=85 closing=42.5 d=23 ceiling=40.0", ceilingLines.single())
    }

    @Test fun `a recycled track id is logged again`() {
        val lines = mutableListOf<String>()
        val first = listOf(23, 15, 6).map { listOf(target(85, it, 42.5f, bornAtMs = 1_000L)) }
        val second = listOf(23, 15, 6).map { listOf(target(85, it, 42.5f, bornAtMs = 9_000L)) }
        run(first + second, bikeSpeedMs = 5f, ceiling = 40f, gateLines = lines)
        assertEquals(2, lines.count { it.startsWith("# gate ceiling tid=85 ") })
    }
}
