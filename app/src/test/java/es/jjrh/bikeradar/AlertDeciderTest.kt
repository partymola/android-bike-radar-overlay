// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertDeciderTest {

    private fun car(id: Int, distanceM: Int, isBehind: Boolean = false) =
        Vehicle(id = id, distanceM = distanceM, speedMs = 5, isBehind = isBehind)

    private val alertMax = 21

    /** Frame time helper: each call advances `now` by `dtMs` and returns the
     *  pre-advance value, mirroring how the live loop publishes a state and
     *  then handles it. */
    private class Clock(start: Long = 0L, val dtMs: Long = 100L) {
        var now: Long = start
        fun tick(): Long { val t = now; now += dtMs; return t }
        fun jump(deltaMs: Long) { now += deltaMs }
    }

    @Test fun `empty road returns None`() {
        val d = AlertDecider()
        val c = Clock()
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
    }

    @Test fun `single frame in close zone is suppressed by sustain`() {
        val d = AlertDecider()
        val c = Clock()
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 18)), alertMax, c.tick()))
    }

    @Test fun `tracker beeps urgency-1 for far-zone car after sustain`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `tracker beeps urgency-2 for mid-zone car`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 10)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), ev)
    }

    @Test fun `tracker beeps urgency-3 for near-zone car`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 4)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 4)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `escalation honours cooldown but still escalates`() {
        // Car closes from far->mid->near rapidly. Expect Beep(1) immediately,
        // then a single Beep when cooldown allows, at the latest urgency.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick())          // sustain frame 1
        assertEquals(AlertDecider.Event.Beep(1),                   // sustain frame 2
            d.decide(listOf(car(1, 18)), alertMax, c.tick()))
        // Crosses to mid third immediately (~100ms later) — pending, suppressed:
        assertEquals(AlertDecider.Event.None,
            d.decide(listOf(car(1, 13)), alertMax, c.tick()))
        // Crosses to near third 100ms later — still pending:
        assertEquals(AlertDecider.Event.None,
            d.decide(listOf(car(1, 6)), alertMax, c.tick()))
        // Wait past cooldown:
        c.jump(700)
        val ev = d.decide(listOf(car(1, 4)), alertMax, c.tick())
        // Beep at the CURRENT urgency (3), not whatever was queued.
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `de-escalation does not re-fire`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 4)), alertMax, c.tick())
        d.decide(listOf(car(1, 4)), alertMax, c.tick())  // Beep(3)
        c.jump(1000)
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 18)), alertMax, c.tick()))
    }

    @Test fun `single-frame phantom blip never beeps`() {
        val d = AlertDecider()
        val c = Clock()
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 5)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
    }

    @Test fun `burst of three new entries within cooldown collapses to one beep`() {
        // Synthetic regression: at t=49..51 cars 6,7,8 enter the close zone
        // within ~1.5 s. The pre-cooldown decider fired 3+ overlapping beeps.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()

        // Car 7 enters and sustains at ~13m → Beep(2)
        d.decide(listOf(car(7, 13)), alertMax, c.tick())
        val first = d.decide(listOf(car(7, 13)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), first)

        // Car 6 enters at ~18m a moment later — within cooldown:
        assertEquals(AlertDecider.Event.None,
            d.decide(listOf(car(6, 18), car(7, 12)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None,
            d.decide(listOf(car(6, 17), car(7, 12)), alertMax, c.tick()))

        // Car 8 enters at ~18m — also within cooldown:
        assertEquals(AlertDecider.Event.None,
            d.decide(listOf(car(6, 16), car(7, 11), car(8, 18)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None,
            d.decide(listOf(car(6, 15), car(7, 11), car(8, 17)), alertMax, c.tick()))

        // Cooldown expires:
        c.jump(700)
        val second = d.decide(
            listOf(car(6, 14), car(7, 10), car(8, 16)), alertMax, c.tick(),
        )
        // One beep, not three. Closest is car 7 at 10m → urgency 2.
        assertEquals(AlertDecider.Event.Beep(2), second)
    }

    @Test fun `overtake re-announces urgency of new closest after cooldown`() {
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 4), car(2, 16)), alertMax, c.tick())
        d.decide(listOf(car(1, 3), car(2, 16)), alertMax, c.tick())  // Beep(3)
        c.jump(700)
        val ev = d.decide(
            listOf(car(1, 2, isBehind = true), car(2, 14)),
            alertMax,
            c.tick(),
        )
        assertEquals(AlertDecider.Event.Beep(2), ev)
    }

    @Test fun `overtake of last close car triggers Clear bypassing cooldown`() {
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 8)), alertMax, c.tick())
        d.decide(listOf(car(1, 6)), alertMax, c.tick())  // Beep(3)
        // No cooldown gap — Clear must still fire:
        val ev = d.decide(listOf(car(1, 2, isBehind = true)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Clear, ev)
    }

    @Test fun `close zone emptying triggers Clear once`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        c.jump(1000)
        assertEquals(AlertDecider.Event.Clear, d.decide(emptyList(), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
    }

    @Test fun `stationary close car at zero relative speed still alerts`() {
        val d = AlertDecider()
        val c = Clock()
        val v = Vehicle(id = 4, distanceM = 5, speedMs = 0)
        d.decide(listOf(v), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), d.decide(listOf(v), alertMax, c.tick()))
    }

    @Test fun `track far beyond alert max is ignored`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 50)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 50)), alertMax, c.tick()))
    }

    @Test fun `re-entering same track after leaving close re-fires after cooldown`() {
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        d.decide(listOf(car(1, 10)), alertMax, c.tick())  // Beep(2)
        d.decide(listOf(car(1, 50)), alertMax, c.tick())  // out of close → Clear
        c.jump(700)
        d.decide(listOf(car(1, 10)), alertMax, c.tick())  // re-entering, frame 1
        val ev = d.decide(listOf(car(1, 10)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), ev)
    }

    @Test fun `reset clears all internal state`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        d.reset()
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
    }

    @Test fun `flicker between frames does not accumulate sustain`() {
        val d = AlertDecider()
        val c = Clock()
        for (i in 1..6) {
            val vs = if (i % 2 == 1) listOf(car(1, 10)) else emptyList()
            assertEquals(AlertDecider.Event.None, d.decide(vs, alertMax, c.tick()))
        }
    }

    @Test fun `alongside-stationary track is excluded from close set`() {
        // Parked car next to a crawling rider would meet the close-zone
        // distance test but is gated out by the decoder's
        // isAlongsideStationary flag and must not produce a beep.
        val d = AlertDecider()
        val c = Clock()
        val parked = Vehicle(id = 1, distanceM = 5, speedMs = 0, isAlongsideStationary = true)
        d.decide(listOf(parked), alertMax, c.tick())
        val ev = d.decide(listOf(parked), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `flag dropping promotes alongside track back into alerts`() {
        // Decoder drops isAlongsideStationary when the target starts
        // closing or the rider speeds up. From the alert path's
        // perspective the track first appears as an active threat -
        // sustain frames must accrue from that point and a beep fires.
        val d = AlertDecider()
        val c = Clock()
        val docked = Vehicle(id = 1, distanceM = 5, speedMs = 0, isAlongsideStationary = true)
        d.decide(listOf(docked), alertMax, c.tick())
        d.decide(listOf(docked), alertMax, c.tick())
        // Target now active (flag dropped). Two sustain frames -> beep.
        val active = Vehicle(id = 1, distanceM = 5, speedMs = -3, isAlongsideStationary = false)
        d.decide(listOf(active), alertMax, c.tick())
        val ev = d.decide(listOf(active), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    // ── stationary-suppress gate ─────────────────────────────────────────

    @Test fun `stationary rider suppresses beep after dwell`() {
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        // First frame with rider at 0 km/h - sets lastNotStationary to now.
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 0)
        // 2 s later, sustain + dwell both satisfied. Beep would normally
        // fire; stationary suppresses it to None.
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 0)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `stationary rider still hears clear`() {
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        // Establish sustain + beep while moving.
        d.decide(listOf(car(1, 10)), alertMax, c.tick(), bikeSpeedKmh = 20)
        d.decide(listOf(car(1, 10)), alertMax, c.tick(), bikeSpeedKmh = 20)
        // Rider stops; dwell completes.
        c.jump(3000)
        d.decide(listOf(car(1, 10)), alertMax, c.tick(), bikeSpeedKmh = 0)
        // Close zone empties - Clear must still fire even though rider is stationary.
        val ev = d.decide(emptyList(), alertMax, c.tick(), bikeSpeedKmh = 0)
        assertEquals(AlertDecider.Event.Clear, ev)
    }

    @Test fun `rider moving beeps normally with speed provided`() {
        // Positive control: passing a non-null, above-threshold bikeSpeedKmh
        // must not break the normal beep path.
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 20)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 20)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `stationary suppress requires dwell`() {
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        // Speed drops to 0 on frame 1; dwell clock starts now.
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 0)
        // 100 ms later - dwell not satisfied, beep must still fire.
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 0)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `null speed treated as not stationary`() {
        // No device-status frame has arrived yet; defaulting to not-stationary
        // is the safe choice so beeps aren't silenced without evidence the
        // rider has actually stopped.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = null)
        c.jump(3000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = null)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `gate releases on stationary-to-moving transition`() {
        // After dwell+suppress, rolling off restores beeps without a
        // delayed cooldown gap (the suppressed beep must not have
        // consumed the cooldown).
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 0)
        c.jump(2000)
        val suppressed = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 0)
        assertEquals(AlertDecider.Event.None, suppressed)
        // Rider rolls off; same car still close. beepPending was preserved
        // through the suppression, so the beep fires same frame.
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 20)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `brief speed blip mid-stop resets dwell`() {
        // Radar speed has 1-2 km/h post-stop noise that the threshold of 2
        // absorbs. But a single sample clearly above threshold (5 km/h
        // here) must reset the dwell - otherwise long stops with momentary
        // wake-ups would suppress incorrectly.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedKmh = 0)
        c.jump(1500)
        // Blip - resets dwell.
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedKmh = 5)
        c.jump(1500)
        // Now a car appears - sustain frame 1.
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 0)
        // Sustain frame 2. Total elapsed since blip ~1700 ms < 2000 dwell,
        // so suppress must NOT fire; beep must fire instead.
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 0)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `bike speed equal to threshold counts as stationary`() {
        // Boundary check on the <= semantics: speed exactly at the
        // threshold (2 km/h) must count as stationary.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 2)
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedKmh = 2)
        assertEquals(AlertDecider.Event.None, ev)
    }
}
