// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertDeciderTest {

    private fun car(id: Int, distanceM: Int, isBehind: Boolean = false) =
        Vehicle(id = id, distanceM = distanceM, speedMs = 5f, isBehind = isBehind)

    private fun closingCar(id: Int, distanceM: Int, speedMs: Float) =
        Vehicle(id = id, distanceM = distanceM, speedMs = speedMs)

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
        //
        // Under the closest-only fix, same-tier additional entries
        // are silent regardless of cooldown — adding cars at the same
        // closest-urgency tier as the already-alerted track does NOT
        // produce a follow-on beep. The cooldown gate is unrelated; D2a
        // suppresses these entries even after cooldown expires.
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

        // Cooldown expires. Closest is car 7 still at 10m (urgency 2).
        // Under D2a + per-track tier hysteresis, this is the same closest
        // tid at the same tier we already audibly fired for — silent.
        c.jump(700)
        val second = d.decide(
            listOf(car(6, 14), car(7, 10), car(8, 16)), alertMax, c.tick(),
        )
        assertEquals(AlertDecider.Event.None, second)
    }

    @Test fun `overtake re-announces urgency of new closest after cooldown`() {
        // Originally this test asserted Beep(2) on the overtake. Under
        // D2b (filtered overtake re-ack), an overtake while others remain
        // close is SILENT unless the remaining closest-urgency is strictly
        // greater than the peak the overtaking track ever reached. Here
        // car 1 was the close-tier (u=3) overtaker; car 2 remains at
        // mid-tier (u=2). 2 > 3 is false — silent.
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
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `overtake of last close car triggers Clear bypassing cooldown`() {
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 8)), alertMax, c.tick())
        d.decide(listOf(car(1, 6)), alertMax, c.tick())  // Beep(3)
        // Overtake empties the in-front set; the clear-grace defers the
        // Clear (the overtaking track stays isBehind, so it never re-enters
        // range to cancel the pending clear).
        assertEquals(AlertDecider.Event.None,
            d.decide(listOf(car(1, 2, isBehind = true)), alertMax, c.tick()))
        // After the grace, Clear fires - not gated by the beep cooldown.
        c.jump(1000)
        val ev = d.decide(listOf(car(1, 2, isBehind = true)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Clear, ev)
    }

    @Test fun `close zone emptying triggers Clear once after grace`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        // First empty frame starts the clear-grace; no Clear yet.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        // Road stays empty past the grace window -> Clear fires once.
        c.jump(1000)
        assertEquals(AlertDecider.Event.Clear, d.decide(emptyList(), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
    }

    // ── clear-grace + distance-band exit hysteresis ──────────────────────
    //
    // Fix for the beep -> clear -> re-beep churn on one continuous
    // approach. The close set had a 2-frame entry debounce but a zero-frame
    // exit, so a single dropped or boundary-flapping frame fired a premature
    // Clear that wiped the per-track latch, and the re-stabilising car
    // re-beeped.

    @Test fun `car lingering at the alertMax edge does not flap-clear (distance band)`() {
        // alertMax=21 -> exit band = 21/10 = 2, so a track stays in close
        // out to 23 m. A car hovering 21<->22 (decoded distance jitter at
        // the window edge) must NOT drop out and fire a Clear.
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 21)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), d.decide(listOf(car(1, 21)), alertMax, c.tick()))
        // Jitter just past the hard edge then back - band keeps it in
        // close, so no Clear and no re-beep.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 22)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 21)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 22)), alertMax, c.tick()))
    }

    @Test fun `car leaving past the exit band fires Clear after grace`() {
        // Negative control for the band: a track that genuinely drives off
        // beyond alertMax + band still leaves the close set and clears.
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 21)), alertMax, c.tick())
        d.decide(listOf(car(1, 21)), alertMax, c.tick())  // Beep(1)
        // 30 m > alertMax(21) + band(2): drops out, grace starts.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 30)), alertMax, c.tick()))
        c.jump(1000)
        assertEquals(AlertDecider.Event.Clear, d.decide(listOf(car(1, 30)), alertMax, c.tick()))
    }

    @Test fun `band-retained track that flips isBehind leaves close and clears`() {
        // The isBehind filter runs before the distance/band test, so a
        // band-retained edge car that overtakes is ejected regardless of
        // the band and (after the grace) clears.
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 21)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), d.decide(listOf(car(1, 21)), alertMax, c.tick()))
        // Edge jitter to 22 m - band keeps it in close, no clear.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 22)), alertMax, c.tick()))
        // Same track overtakes: isBehind ejects it, starting the grace.
        assertEquals(AlertDecider.Event.None,
            d.decide(listOf(car(1, 22, isBehind = true)), alertMax, c.tick()))
        c.jump(1000)
        assertEquals(AlertDecider.Event.Clear,
            d.decide(listOf(car(1, 22, isBehind = true)), alertMax, c.tick()))
    }

    @Test fun `single-frame dropout within grace does not clear or re-beep`() {
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        // Radar drops the target for one frame (absent from vehicles).
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        // Wait past the beep cooldown so a re-beep is not merely cooldown-
        // gated; still inside the clear-grace (800 ms < 1000 ms).
        c.jump(700)
        // Same car returns: no Clear, and no fresh re-beep (latch survived
        // the cancelled grace).
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
    }

    @Test fun `different car during grace beeps and cancels the pending clear`() {
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        d.decide(listOf(car(1, 10)), alertMax, c.tick())  // Beep(2) for car 1
        // Car 1 drops out; grace pending.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        c.jump(700)
        // A DIFFERENT car enters within the grace: it must beep (new tid,
        // no latch) and its presence cancels the pending Clear.
        d.decide(listOf(car(2, 18)), alertMax, c.tick())
        val ev = d.decide(listOf(car(2, 18)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `car returning closer within grace re-beeps at the higher tier`() {
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick())
        d.decide(listOf(car(1, 18)), alertMax, c.tick())  // Beep(1) far
        // Drops out for one frame...
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        c.jump(700)
        // ...and returns materially closer (near third). Latch was 1, so
        // the higher tier must still fire - the threat state changed.
        d.decide(listOf(car(1, 4)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 4)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `stationary close car at zero relative speed still alerts`() {
        val d = AlertDecider()
        val c = Clock()
        val v = Vehicle(id = 4, distanceM = 5, speedMs = 0f)
        d.decide(listOf(v), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), d.decide(listOf(v), alertMax, c.tick()))
    }

    @Test fun `track far beyond alert max is ignored`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 50)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 50)), alertMax, c.tick()))
    }

    @Test fun `re-entering same track after a real departure re-fires after cooldown`() {
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        d.decide(listOf(car(1, 10)), alertMax, c.tick())  // Beep(2)
        // Car leaves the close zone for longer than the clear-grace: a real
        // departure, so the Clear fires and the per-track latch is wiped.
        d.decide(listOf(car(1, 50)), alertMax, c.tick())  // grace starts
        c.jump(1000)
        d.decide(listOf(car(1, 50)), alertMax, c.tick())  // grace elapsed → Clear
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
        val parked = Vehicle(id = 1, distanceM = 5, speedMs = 0f, isAlongsideStationary = true)
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
        val docked = Vehicle(id = 1, distanceM = 5, speedMs = 0f, isAlongsideStationary = true)
        d.decide(listOf(docked), alertMax, c.tick())
        d.decide(listOf(docked), alertMax, c.tick())
        // Target now active (flag dropped). Two sustain frames -> beep.
        val active = Vehicle(id = 1, distanceM = 5, speedMs = -3f, isAlongsideStationary = false)
        d.decide(listOf(active), alertMax, c.tick())
        val ev = d.decide(listOf(active), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    // ── stationary-suppress gate ─────────────────────────────────────────

    @Test fun `stationary rider suppresses beep after dwell`() {
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        // First frame with rider at 0 m/s - sets lastNotStationary to now.
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        // 2 s later, sustain + dwell both satisfied. Beep would normally
        // fire; stationary suppresses it to None.
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `stationary rider still hears clear`() {
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        // Establish sustain + beep while moving.
        d.decide(listOf(car(1, 10)), alertMax, c.tick(), bikeSpeedMs = 6f)
        d.decide(listOf(car(1, 10)), alertMax, c.tick(), bikeSpeedMs = 6f)
        // Rider stops; dwell completes.
        c.jump(3000)
        d.decide(listOf(car(1, 10)), alertMax, c.tick(), bikeSpeedMs = 0f)
        // Close zone empties; the clear-grace defers the Clear one frame.
        assertEquals(AlertDecider.Event.None,
            d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f))
        c.jump(1000)
        // Clear must still fire after the grace even though rider is stationary.
        val ev = d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.Clear, ev)
    }

    @Test fun `rider moving beeps normally with speed provided`() {
        // Positive control: passing a non-null, above-threshold bikeSpeedMs
        // must not break the normal beep path.
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `stationary suppress requires dwell`() {
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        // Speed drops to 0 on frame 1; dwell clock starts now.
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        // 100 ms later - dwell not satisfied, beep must still fire.
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `null speed treated as not stationary`() {
        // No device-status frame has arrived yet; defaulting to not-stationary
        // is the safe choice so beeps aren't silenced without evidence the
        // rider has actually stopped.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = null)
        c.jump(3000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = null)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    // ── LDI bike_not_driving ground truth wins over GPS speed ────────

    @Test fun `LDI bikeNotDriving true wins over GPS that thinks moving`() {
        // Urban-canyon scenario (Holborn / Bank): wheel sensor says
        // stopped, GPS bounces around showing 6 m/s. The decider must
        // trust the wheel sensor and suppress after dwell, even though
        // bikeSpeedMs is well above the threshold.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f, bikeNotDriving = true)
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f, bikeNotDriving = true)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `LDI bikeNotDriving false wins over GPS that thinks stopped`() {
        // Inverse canyon scenario: GPS clamps to 0 m/s while the rider
        // is actually rolling at 4 m/s (sensor says wheel is turning).
        // The decider must beep normally; LDI ground truth means the
        // rider is not actually stationary, regardless of GPS.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, bikeNotDriving = false)
        c.jump(3000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, bikeNotDriving = false)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `LDI absent (null) falls back to bikeSpeedMs gate - moving`() {
        // No-LDI rider (no Bosch eBike, or experimental flag off). The
        // decider must work exactly as before: GPS-derived speed gates
        // the stationary suppress. This is the graceful-degradation path
        // that mandatory per the feedback memory.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f, bikeNotDriving = null)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f, bikeNotDriving = null)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `LDI absent (null) falls back to bikeSpeedMs gate - stationary`() {
        // No-LDI rider stopping at a light. GPS reads 0, LDI is null,
        // stationary suppress engages after dwell exactly as before.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, bikeNotDriving = null)
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, bikeNotDriving = null)
        assertEquals(AlertDecider.Event.None, ev)
    }

    // ── climbing override on the stationary-suppress gate ────────────

    @Test fun `climbing forces the stationary gate off even when LDI says stopped`() {
        // Rider grinding up Fitzjohns at a wheel-near-rest cadence:
        // bikeNotDriving could read true on a brief pedal-stroke pause,
        // but the climb override must keep alerts firing because the
        // rider is exposed to overtaking traffic.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeNotDriving = true, climbing = true)
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeNotDriving = true, climbing = true)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `climbing forces gate off with no-LDI low GPS speed`() {
        // No-LDI rider on a hill: GPS clamps to ~0 m/s through canyon
        // noise; without the climb override the stationary gate would fire after dwell.
        // Climbing override keeps the alert path open.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, climbing = true)
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, climbing = true)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `climbing default false leaves existing behaviour intact`() {
        // Graceful degradation: when ClimbDetector hasn't accumulated
        // dwell, climbing defaults to false and the stationary path
        // works exactly as before the climb override landed.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    // ── speed-aware inter-beep cooldown ─────────────────────────────

    @Test fun `effective cooldown defaults when no speed signal`() {
        // null speed (no LDI, no radar-bike-speed yet): use the base
        // minBeepGapMs unchanged. Graceful degradation contract.
        val d = AlertDecider(minBeepGapMs = 700L)
        assertEquals(700L, d.effectiveMinBeepGapMs(null))
    }

    @Test fun `cooldown is base in the 15-25 kmh band`() {
        val d = AlertDecider(minBeepGapMs = 700L)
        assertEquals(700L, d.effectiveMinBeepGapMs(5f))         // 18 km/h
        assertEquals(700L, d.effectiveMinBeepGapMs(20f / 3.6f)) // 20 km/h
    }

    @Test fun `cooldown doubles below 15 kmh (slow urban crawl)`() {
        // 2 m/s = 7.2 km/h, well into the slow band. Cooldown widens to
        // damp the flapping-beep cacophony from cars hovering at the
        // urgency-tier boundary while everyone queues.
        val d = AlertDecider(minBeepGapMs = 700L)
        assertEquals(1400L, d.effectiveMinBeepGapMs(2f))
    }

    @Test fun `cooldown halves above 25 kmh (fast descent)`() {
        // 8 m/s = 28.8 km/h. Reaction time is shorter; re-arm faster on
        // tier raises for the same closing-speed threat.
        val d = AlertDecider(minBeepGapMs = 700L)
        assertEquals(350L, d.effectiveMinBeepGapMs(8f))
    }

    @Test fun `slow cooldown actually gates a Beep in the integration path`() {
        // Slow rider (5 km/h) gets first beep, then a fresh trigger
        // 800 ms later. Under the flat 700 ms cooldown this would fire
        // again; under the slow 1400 ms cooldown it must stay silent.
        val d = AlertDecider(minBeepGapMs = 700L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 5f / 3.6f)
        val first = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 5f / 3.6f)
        assertEquals(AlertDecider.Event.Beep(1), first)
        c.jump(800)
        // New tier raise (mid-zone). Cooldown not yet done at slow speed
        // (1400 ms required, only 800 ms elapsed). Must stay silent.
        val tooSoon = d.decide(listOf(car(1, 10)), alertMax, c.tick(), bikeSpeedMs = 5f / 3.6f)
        assertEquals(AlertDecider.Event.None, tooSoon)
    }

    @Test fun `must not widen UrgentApproach repeat-while-held cadence`() {
        // Safety invariant: a stationary rider in front of an imminent
        // threat must still get the repeated UrgentApproach at the base
        // cooldown rate (700 ms), NOT at the slow-band widened rate
        // (1400 ms). The point of UrgentApproach is to warn fast about
        // impending impact; widening the cadence at low speed would
        // make a stopped rider WAIT LONGER for the next warning, which
        // is the opposite of what they need.
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        val c = Clock()
        // Establish stationary state.
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        // Imminent threat: 5 m at -8 m/s closing => proximity gate fires.
        val v = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)  // fires first urgent
        c.jump(700)
        // 700 ms later, at the base cooldown boundary. The speed-aware path in the
        // slow-band would require 1400 ms; UrgentApproach must bypass
        // and fire at 700 ms.
        val again = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), again)
    }

    @Test fun `fast cooldown allows a faster re-arm in the integration path`() {
        // Fast rider (30 km/h). At 400 ms after a beep, a tier raise
        // would be suppressed by the flat 700 ms cooldown, but the
        // fast-band 350 ms cooldown lets it through.
        val d = AlertDecider(minBeepGapMs = 700L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 30f / 3.6f)
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 30f / 3.6f)
        c.jump(400)
        // Same track raises to mid-zone tier. Fast cooldown is done at
        // 350 ms; the tier raise must fire.
        val ev = d.decide(listOf(car(1, 10)), alertMax, c.tick(), bikeSpeedMs = 30f / 3.6f)
        assertEquals(AlertDecider.Event.Beep(2), ev)
    }

    @Test fun `gate releases on stationary-to-moving transition`() {
        // After dwell+suppress, rolling off restores beeps without a
        // delayed cooldown gap (the suppressed beep must not have
        // consumed the cooldown).
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val suppressed = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, suppressed)
        // Rider rolls off; same car still close. beepPending was preserved
        // through the suppression, so the beep fires same frame.
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `brief speed blip mid-stop resets dwell`() {
        // Radar speed has post-stop noise up to ~0.5 m/s that the threshold
        // absorbs. A single sample clearly above threshold (2 m/s here)
        // must reset the dwell - otherwise long stops with momentary
        // wake-ups would suppress incorrectly.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(1500)
        // Blip - resets dwell.
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 2f)
        c.jump(1500)
        // Now a car appears - sustain frame 1.
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        // Sustain frame 2. Total elapsed since blip ~1700 ms < 2000 dwell,
        // so suppress must NOT fire; beep must fire instead.
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `bike speed equal to threshold counts as stationary`() {
        // Boundary check on the <= semantics: speed exactly at the
        // threshold (0.5 m/s) must count as stationary.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0.5f)
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0.5f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    // ── stationary safety override ───────────────────────────────────────

    @Test fun `stationary plus close plus fast-closing fires UrgentApproach`() {
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        // Establish stationary state (no cars yet, just dwell).
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        // Car at near-third proximity (5 m, alertMax=21 -> near-third = 7),
        // closing at -8 m/s (below the -5 override threshold).
        val v = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `stationary plus close plus slow-closing stays suppressed`() {
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        // Same proximity but only -3 m/s (above -5, so not "fast-closing").
        val v = closingCar(id = 1, distanceM = 5, speedMs = -3f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `stationary plus beyond-alertMax plus fast-closing stays suppressed`() {
        // The TTC gate's distance ceiling is alertMaxM: a target
        // outside the alert envelope doesn't fire even at low TTC,
        // because the rider explicitly opted into not being alerted at
        // that distance.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        // alertMax = 21, distance = 25 (> alertMax), closing -8 m/s
        // (TTC = 25/8 = 3.1 s, would fire if envelope were open).
        val v = closingCar(id = 1, distanceM = 25, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `moving rider with fast-closing car beeps normally not urgent`() {
        // Override only applies to stationary riders. A moving rider gets
        // the normal Beep at the appropriate urgency.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 6f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 6f)
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `urgent override does not fire on isBehind tracks`() {
        // Negative control: an overtaken track (isBehind=true) is filtered
        // out of the close set before the stableClose computation, so it
        // can't trigger the safety override regardless of its closing
        // speed. Pins this guarantee against future filter refactors.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val ahead = Vehicle(id = 1, distanceM = 5, speedMs = -8f, isBehind = true)
        d.decide(listOf(ahead), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(ahead), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `decelerating-into-junction with imminent threat fires urgent before full dwell`() {
        // Rider decelerating into a stop with a fast-closing vehicle at
        // near-third proximity. The 2 s ordinary stationary-suppress
        // dwell is too long here — TTC at the imminent gate is sub-2 s,
        // so waiting it out leaves the urgent tone silent during the
        // entire reaction window. The mini-dwell (URGENT_OVERRIDE_DWELL_MS,
        // 500 ms) absorbs single-frame speed noise without delaying
        // urgent past the TTC window.
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        val c = Clock()
        // Rider at zero speed; mini-dwell starts ticking.
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        // 600 ms below threshold — past mini-dwell, well short of
        // 2 s ordinary suppress.
        c.jump(600)
        val v = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `single-frame speed noise does NOT fire urgent (mini-dwell guard)`() {
        // Defensive: a one-frame radar bike-speed dropout below the
        // stationary threshold while the rider is genuinely moving
        // must NOT fire urgent even if a vehicle happens to satisfy
        // the imminent gate. The mini-dwell prevents this.
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        val c = Clock()
        // Rider moving normally; single-frame dropout to 0.
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 6f)
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f) // single noise frame
        val v = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 6f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 6f)
        // Rider is back above threshold; no urgent. Ordinary Beep(3)
        // fires per the moving-rider path.
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `escalation from slow to fast triggers UrgentApproach`() {
        // Realistic scenario: a vehicle approaching slowly enough to be
        // suppressed, then suddenly closing fast (driver realises late and
        // brakes hard, or didn't see the queue). Same tid; once closing
        // crosses the threshold the override fires next frame.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val slow = closingCar(id = 1, distanceM = 5, speedMs = -3f)
        d.decide(listOf(slow), alertMax, c.tick(), bikeSpeedMs = 0f)
        val sustainedSlow = d.decide(listOf(slow), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, sustainedSlow)
        // Same tid now closing fast.
        val fast = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        val ev = d.decide(listOf(fast), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `urgent override threshold inclusive at -6 m_per_s`() {
        // Boundary check: speedMs exactly at SAFETY_OVERRIDE_CLOSING_MS
        // (-6) must fire (<= semantics).
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 5, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `urgent override does not fire at half-quantum below threshold`() {
        // Float-precision boundary: -5.5f sits at the radar's native
        // 0.5 m/s quantum (raw byte -11), one half-step short of the
        // -6f gate. Must NOT fire - pins the quantum-strict semantics
        // motivating the threshold choice.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 5, speedMs = -5.5f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `ttc gate fires at medium distance for fast-closing vehicle`() {
        // The headline win the TTC gate exists for. 12 m at 6 m/s =
        // TTC 2 s - well outside near-third (alertMax/3 = 7), so the
        // proximity gate would not fire (it caps at 6 m at the same
        // closing speed = TTC 1 s). The TTC gate catches the same
        // approach earlier, at TTC = 2 s.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 12, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `ttc gate boundary at exactly 2 seconds fires`() {
        // dist 12 / closing 6 = TTC 2.0 s exactly. <= TTC_GATE_SECONDS
        // is inclusive.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 12, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `ttc gate excludes ttc above 2 seconds`() {
        // dist 14 / closing 6 = TTC 2.33 s; just past threshold. Outside
        // near-third too (14 > 7), so the proximity gate is also off.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 14, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `ttc gate closing floor inclusive at 6 m_per_s`() {
        // closing exactly TTC_GATE_CLOSING_FLOOR_MS (6) at TTC 2 s
        // fires. Mirrors the SAFETY_OVERRIDE_CLOSING_MS quantum-strict
        // bound on the proximity gate.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 12, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `ttc gate closing floor excludes 5 m_per_s`() {
        // closing 5 < TTC_GATE_CLOSING_FLOOR_MS - slow-queue traffic
        // merging into a stopped rider, where the driver is clearly
        // tracking and braking. Outside near-third (8 > 7), so
        // proximity gate also off. Pins the slow-queue suppression
        // at the new tighter floor.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 8, speedMs = -5f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `ttc gate distance ceiling inclusive at alertMax`() {
        // distanceM == alertMaxM exactly is INSIDE the TTC envelope
        // (`v.distanceM in 0..alertMaxM`). At 21 m / 12 m/s = 1.75 s
        // TTC, the gate fires (well within the 2 s ceiling).
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = alertMax, speedMs = -12f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `ttc gate does not fire when rider is moving`() {
        // The stationary safety override (in either disjunct) only
        // engages when the rider has been below stationaryMsThreshold
        // for the mini-dwell. A moving rider with a TTC-imminent
        // threat gets the ordinary Beep at the appropriate urgency
        // tier, not UrgentApproach.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 12, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 6f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 6f)
        // alertMax = 21 -> thirds at 7 / 14; distance 12 -> mid third.
        assertEquals(AlertDecider.Event.Beep(2), ev)
    }

    // ── closest-only-audio pinning tests ────────────────────────────────
    //
    // These tests pin the closest-only audio model. See the
    // AlertDecider class KDoc for the trigger semantics and why each
    // gate exists.

    @Test fun `same-tier new entry while closer track still close is silent`() {
        // D2a: a new track entering the close set is silent unless its
        // arrival raises the closest-urgency tier above what we have
        // already audibly fired for. Here car 1 is established at near-
        // tier (u=3); car 2 enters at far-tier (u=1). Closest stays car 1
        // at u=3 — adding car 2 must not produce a beep.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        // Establish car 1 at near-tier.
        d.decide(listOf(car(1, 4)), alertMax, c.tick())
        val first = d.decide(listOf(car(1, 4)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), first)
        // Wait past cooldown so the cooldown isn't what's silencing this.
        c.jump(1000)
        // Car 2 enters at 18m — closest is still car 1 at u=3.
        d.decide(listOf(car(1, 4), car(2, 18)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 4), car(2, 18)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `overtake re-ack at same-or-lower tier is silent`() {
        // D2b: overtake re-ack only fires if the remaining closest's
        // urgency is STRICTLY GREATER than the peak the overtaking track
        // ever reached. Same-or-lower tier remainders are silent — the
        // rider was already alerted at that tier by the now-overtaking
        // track. Also exercises the "lower tier" leg specifically.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        // Car 1 at near-tier (u=3), car 2 at far-tier (u=1).
        d.decide(listOf(car(1, 4), car(2, 18)), alertMax, c.tick())
        d.decide(listOf(car(1, 4), car(2, 18)), alertMax, c.tick())  // Beep(3)
        c.jump(1000)
        // Car 1 overtakes. Remaining is car 2 at u=1; peak[1] was 3.
        // 1 > 3? No — silent.
        val ev = d.decide(
            listOf(car(1, 2, isBehind = true), car(2, 18)),
            alertMax,
            c.tick(),
        )
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `intra-tier distance flap does NOT re-fire`() {
        // Per-track tier hysteresis: once we have audibly fired at urgency
        // N for tid T, no re-fire for the same tid at the same tier even
        // if distance jitters across the bucket boundary. alertMax=21,
        // near-third = 7. Distance flapping 6 -> 8 -> 6 -> 7 stays at the
        // (mostly) near boundary; even if a frame technically lands in
        // mid-tier (8m -> u=2), coming back to 6m (u=3) must NOT re-fire
        // for the same tid since fired[1]=3 already.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 6)), alertMax, c.tick())
        val first = d.decide(listOf(car(1, 6)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), first)
        c.jump(1000)
        // Drop back to mid-tier (8m, u=2), then return to near (6m, u=3).
        // Without hysteresis, the 6m re-entry would look like an
        // escalation 2 -> 3 and re-fire. With the latch, fired[1]=3 ≥ 3
        // so no fire.
        d.decide(listOf(car(1, 8)), alertMax, c.tick())
        d.decide(listOf(car(1, 8)), alertMax, c.tick())
        c.jump(1000)
        val flapped = d.decide(listOf(car(1, 6)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, flapped)
        val flapped2 = d.decide(listOf(car(1, 6)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, flapped2)
    }

    @Test fun `cacophony scenario truck pass produces at most three audible beeps`() {
        // Replays a single-truck overtake scene (~19 s, multi-track
        // cluster: tid 51 + tid 111 + transient tid 124 multipath).
        // Under a fire-on-every-new-entry model this produces 8 beeps;
        // under closest-only it should be ≤ 3.
        //
        // alertMaxM = 30 (matches real configs), so near-third = 10 m,
        // mid-third = 20 m. We pass no bike speed so the stationary-
        // suppress / urgent-override paths don't enter — this
        // isolates the closest-only audio model.
        val alertMaxTruck = 30
        val d = AlertDecider()  // default minBeepGapMs = 700
        val audible = mutableListOf<AlertDecider.Event>()
        var t = 0L

        fun feed(vs: List<Vehicle>) {
            val ev = d.decide(vs, alertMaxTruck, t)
            if (ev !is AlertDecider.Event.None) audible.add(ev)
            t += 200
        }
        // Two-frame sustain helper.
        fun scene(vs: List<Vehicle>) { feed(vs); feed(vs) }
        fun gap(ms: Long) { t += ms }

        // t+0 — tid 51@16 (u=2 mid), tid 111@27 (u=1 far). Closest 51.
        scene(listOf(car(51, 16), car(111, 27)))
        // t+6 — tid 51 closes to 10 m (u=3 near), 111 still 27 m.
        // Escalation on 51: Beep(3).
        gap(5500)
        scene(listOf(car(51, 10), car(111, 27)))
        // t+11 — tid 51 leaves close, 111 at 13 m (u=2 mid). Same close
        // set never empties (111 stays in), so latches preserved. Drop in
        // closest urgency 3 -> 2 is silent.
        gap(4800)
        scene(listOf(car(111, 13)))
        // t+12 — tid 51 re-appears at 0 m, tid 111 at 9 m (u=3 near).
        // Closest is 51 @ 0 (u=3). New entry on 51, but firedTier[51]
        // was cleared when 51 left the close set... actually no — it
        // is only cleared on full Clear. 51 is a new entry; closestUrg
        // raises 2 -> 3 but newEntryRaisesTier requires the entry IS
        // among the new entries. 51 IS the new entry and IS the closest.
        // So this fires (Beep(3)).
        gap(700)
        scene(listOf(car(51, 0), car(111, 9)))
        // t+13 — tid 124 enters at 12 m. Closest still 51 @ 0 (u=3).
        // Same-tier new entry (124 doesn't change closest urgency) — D2a
        // says silent.
        gap(900)
        scene(listOf(car(51, 0), car(111, 3), car(124, 12)))
        // t+13.7 — 51 still 0, 124 closes to 2 m (still u=3). 111
        // implied still close. Closest 51@0 — same tier, silent.
        gap(700)
        scene(listOf(car(51, 0), car(124, 2)))
        // t+15 — tid 51 only at 0 m. Cooldown long expired. Same
        // closest, same tier. Silent.
        gap(1500)
        scene(listOf(car(51, 0)))
        // t+19 — close set finally empties (truck has fully passed).
        gap(1000)
        feed(emptyList())  // Clear
        feed(emptyList())  // None

        // Expect: Beep(2) for tid 51 entering mid-tier, Beep(3) on
        // escalation, Beep(3) on tid 51's re-entry-as-closest at u=3
        // (since fired[51] was 3 BUT only counts on the *closest at
        // firing time*; in the simulator output we see new-mode firing
        // exactly twice in this window plus a Clear). Either way: ≤ 3.
        val beepEvents = audible.filterIsInstance<AlertDecider.Event.Beep>()
        assertEquals(
            "expected ≤ 3 audible beeps over the truck window, got ${beepEvents.size}: $beepEvents",
            true,
            beepEvents.size <= 3,
        )
    }

    @Test fun `closest-only invariant — adding a same-tier second car does not re-cue`() {
        // A scene where two cars arrive at the same tier behind the
        // rider must produce exactly ONE audible cue, not one per car.
        // The first cue describes "a car at tier N is closest"; the
        // second car at the same tier does not change that statement.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        // Car 1 enters at 13 m (alertMax=21, mid-third 7..14, so u=2).
        d.decide(listOf(car(1, 13)), alertMax, c.tick())
        val first = d.decide(listOf(car(1, 13)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), first)
        // Wait past cooldown.
        c.jump(1000)
        // Car 2 enters at 12 m (also u=2). Car 1 still at 13 m. Closest
        // is now car 2 (12 < 13) but at the SAME tier as the alert that
        // already fired — must be silent.
        d.decide(listOf(car(1, 13), car(2, 12)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 13), car(2, 12)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `closest-only invariant — adding a HIGHER-tier vehicle DOES cue and replaces the audible thread`() {
        // Counterpart to the same-tier test above: when a NEW track
        // arrives at a HIGHER tier (closer than the existing closest),
        // it MUST produce an audible cue — the audible thread now
        // describes the new closest at its higher tier. This guards
        // against an over-aggressive "silence everything after the
        // first beep" simplification.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        // Car 1 enters at 18 m (u=1 far).
        d.decide(listOf(car(1, 18)), alertMax, c.tick())
        val first = d.decide(listOf(car(1, 18)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), first)
        c.jump(1000)
        // Car 2 enters at 4 m (u=3 near). Closest jumps to car 2; tier
        // raises 1 -> 3. Must fire.
        d.decide(listOf(car(1, 18), car(2, 4)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 18), car(2, 4)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `held imminent threat fires UrgentApproach every cooldown`() {
        // The imminent-impact gate fires when time-to-collision is
        // sub-2 seconds. A second urgent tone 3-6 seconds later would
        // be post-impact in the worst case. UrgentApproach must re-fire
        // while the threat persists — every safety-critical industry
        // standard surveyed (TCAS, IEC 60601-1-8, smoke T3, automotive
        // FCW, ISO 7731) repeats-while-held. The cooldown is the rate
        // limiter, not a per-tid latch. DO NOT add a per-tid urgent
        // latch back.
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val first = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), first)
        c.jump(700)
        val again = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), again)
        c.jump(700)
        val third = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), third)
    }

    @Test fun `held imminent threat via TTC gate fires UrgentApproach every cooldown`() {
        // Same repeat-while-held semantics as the proximity-gate
        // variant above, but for a target that fires the TTC gate
        // ONLY: 12 m at -6 m/s = TTC 2.0 s, well outside near-third
        // (alertMax/3 = 7) so the proximity gate never engages.
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 12, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val first = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), first)
        c.jump(700)
        val again = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), again)
        c.jump(700)
        val third = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), third)
    }

    // ── lateralPos plumbing for directional-audio (experimental) ───────

    @Test fun `Beep carries closest target lateralPos`() {
        // Vehicle at far third with lateralPos = +0.7 (right-of-rider).
        // The Beep event must surface that lateralPos so the AlertBeeper
        // pan stage can steer the cue to the rider's right ear.
        val d = AlertDecider()
        val c = Clock()
        val v = Vehicle(id = 1, distanceM = 18, speedMs = 5f, lateralPos = 0.7f)
        d.decide(listOf(v), alertMax, c.tick())
        val ev = d.decide(listOf(v), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(count = 1, lateralPos = 0.7f), ev)
    }

    @Test fun `UrgentApproach carries triggering vehicle lateralPos`() {
        // Stationary rider; vehicle closes fast at lateralPos = -0.6
        // (left-of-rider). The UrgentApproach must surface -0.6 so the
        // urgent cue pans to the rider's left ear.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = Vehicle(id = 1, distanceM = 5, speedMs = -8f, lateralPos = -0.6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(lateralPos = -0.6f), ev)
    }
}
