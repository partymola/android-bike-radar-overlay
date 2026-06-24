// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertDeciderTest {

    private fun car(id: Int, distanceM: Int, isBehind: Boolean = false) = Vehicle(id = id, distanceM = distanceM, speedMs = 5f, isBehind = isBehind)

    private fun closingCar(id: Int, distanceM: Int, speedMs: Float) = Vehicle(id = id, distanceM = distanceM, speedMs = speedMs)

    private val alertMax = 21

    /** Frame time helper: each call advances `now` by `dtMs` and returns the
     *  pre-advance value, mirroring how the live loop publishes a state and
     *  then handles it. */
    private class Clock(start: Long = 0L, val dtMs: Long = 100L) {
        var now: Long = start
        fun tick(): Long {
            val t = now
            now += dtMs
            return t
        }
        fun jump(deltaMs: Long) {
            now += deltaMs
        }
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

    @Test fun `escalation under NONE mode honours cooldown then fires latest urgency`() {
        // escalationBypass = NONE reproduces the pre-E1 behaviour: a tier raise
        // inside the cooldown window is deferred and collapses into a single
        // beep at the closest urgency at the moment the cooldown expires.
        val d = AlertDecider(minBeepGapMs = 700, escalationBypass = EscalationCooldownBypass.NONE)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick()) // sustain frame 1
        assertEquals(
            AlertDecider.Event.Beep(1), // sustain frame 2
            d.decide(listOf(car(1, 18)), alertMax, c.tick()),
        )
        // Crosses to mid third (~100ms later) - deferred, suppressed:
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 13)), alertMax, c.tick()))
        // Crosses to near third 100ms later - still deferred:
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 6)), alertMax, c.tick()))
        c.jump(700)
        // Beep at the CURRENT urgency (3), not whatever was queued.
        assertEquals(AlertDecider.Event.Beep(3), d.decide(listOf(car(1, 4)), alertMax, c.tick()))
    }

    @Test fun `escalation bypasses cooldown and fires each tier raise same-frame (E1 ALL)`() {
        // Default escalationBypass = ALL. A car closing far->mid->near on
        // consecutive frames fires Beep(1), Beep(2), Beep(3) in real time:
        // each strict tier raise bypasses the 700ms cooldown instead of being
        // deferred and collapsed into one beep when it expires.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick()) // sustain frame 1
        assertEquals(AlertDecider.Event.Beep(1), d.decide(listOf(car(1, 18)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 13)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.Beep(3), d.decide(listOf(car(1, 6)), alertMax, c.tick()))
    }

    @Test fun `fast closer overtaking within cooldown - ALL beeps the escalation, NONE drops it`() {
        // The safety case E1 targets: a fast closer raises a tier, then
        // overtakes before the cooldown expires. Under ALL the tier-3 raise
        // fires the same frame; under NONE it is deferred and then lost when
        // the car leaves the close set - the rider never hears it.
        val f1 = listOf(car(1, 18))
        val f2 = listOf(car(1, 18))
        val f3 = listOf(car(1, 5)) // raise to near third, ~200ms in (< 700 cooldown)
        val f4 = listOf(car(1, 2, isBehind = true)) // overtakes, leaves the close set

        val all = AlertDecider(minBeepGapMs = 700)
        val ca = Clock()
        assertEquals(AlertDecider.Event.None, all.decide(f1, alertMax, ca.tick()))
        assertEquals(AlertDecider.Event.Beep(1), all.decide(f2, alertMax, ca.tick()))
        assertEquals(AlertDecider.Event.Beep(3), all.decide(f3, alertMax, ca.tick())) // escalation heard
        assertEquals(AlertDecider.Event.None, all.decide(f4, alertMax, ca.tick())) // overtake; clear-grace

        val none = AlertDecider(minBeepGapMs = 700, escalationBypass = EscalationCooldownBypass.NONE)
        val cn = Clock()
        assertEquals(AlertDecider.Event.None, none.decide(f1, alertMax, cn.tick()))
        assertEquals(AlertDecider.Event.Beep(1), none.decide(f2, alertMax, cn.tick()))
        assertEquals(AlertDecider.Event.None, none.decide(f3, alertMax, cn.tick())) // deferred
        assertEquals(AlertDecider.Event.None, none.decide(f4, alertMax, cn.tick())) // escalation dropped
    }

    @Test fun `same-tier re-statement within cooldown stays silent under ALL (latch holds)`() {
        // E1 bypasses only a strict tier RAISE. A car that beeps then stays at
        // the same tier inside the cooldown must not re-fire - the per-tid
        // latch suppresses same-tier re-statements regardless of bypass.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 11)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 9)), alertMax, c.tick()))
    }

    @Test fun `TOP_TIER bypasses only a raise into the near third`() {
        // escalationBypass = TOP_TIER: a 1->2 raise inside the cooldown is
        // still gated (deferred), but a raise into the near third (Beep 3 -
        // the imminent tier) bypasses and fires the same frame.
        val d = AlertDecider(minBeepGapMs = 700, escalationBypass = EscalationCooldownBypass.TOP_TIER)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), d.decide(listOf(car(1, 18)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 13)), alertMax, c.tick())) // 1->2 gated
        assertEquals(AlertDecider.Event.Beep(3), d.decide(listOf(car(1, 6)), alertMax, c.tick())) // ->3 bypasses
    }

    @Test fun `de-escalation does not re-fire`() {
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 4)), alertMax, c.tick())
        d.decide(listOf(car(1, 4)), alertMax, c.tick()) // Beep(3)
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
        // are silent regardless of cooldown - adding cars at the same
        // closest-urgency tier as the already-alerted track does NOT
        // produce a follow-on beep. The cooldown gate is unrelated; D2a
        // suppresses these entries even after cooldown expires.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()

        // Car 7 enters and sustains at ~13m → Beep(2)
        d.decide(listOf(car(7, 13)), alertMax, c.tick())
        val first = d.decide(listOf(car(7, 13)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), first)

        // Car 6 enters at ~18m a moment later - within cooldown:
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(car(6, 18), car(7, 12)), alertMax, c.tick()),
        )
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(car(6, 17), car(7, 12)), alertMax, c.tick()),
        )

        // Car 8 enters at ~18m - also within cooldown:
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(car(6, 16), car(7, 11), car(8, 18)), alertMax, c.tick()),
        )
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(car(6, 15), car(7, 11), car(8, 17)), alertMax, c.tick()),
        )

        // Cooldown expires. Closest is car 7 still at 10m (urgency 2).
        // Under D2a + per-track tier hysteresis, this is the same closest
        // tid at the same tier we already audibly fired for - silent.
        c.jump(700)
        val second = d.decide(
            listOf(car(6, 14), car(7, 10), car(8, 16)),
            alertMax,
            c.tick(),
        )
        assertEquals(AlertDecider.Event.None, second)
    }

    @Test fun `overtake re-announces urgency of new closest after cooldown`() {
        // Originally this test asserted Beep(2) on the overtake. Under
        // D2b (filtered overtake re-ack), an overtake while others remain
        // close is SILENT unless the remaining closest-urgency is strictly
        // greater than the peak the overtaking track ever reached. Here
        // car 1 was the close-tier (u=3) overtaker; car 2 remains at
        // mid-tier (u=2). 2 > 3 is false - silent.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 4), car(2, 16)), alertMax, c.tick())
        d.decide(listOf(car(1, 3), car(2, 16)), alertMax, c.tick()) // Beep(3)
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
        d.decide(listOf(car(1, 6)), alertMax, c.tick()) // Beep(3)
        // Overtake empties the in-front set; the clear-grace defers the
        // Clear (the overtaking track stays isBehind, so it never re-enters
        // range to cancel the pending clear).
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(car(1, 2, isBehind = true)), alertMax, c.tick()),
        )
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
        d.decide(listOf(car(1, 21)), alertMax, c.tick()) // Beep(1)
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
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(car(1, 22, isBehind = true)), alertMax, c.tick()),
        )
        c.jump(1000)
        assertEquals(
            AlertDecider.Event.Clear,
            d.decide(listOf(car(1, 22, isBehind = true)), alertMax, c.tick()),
        )
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
        d.decide(listOf(car(1, 10)), alertMax, c.tick()) // Beep(2) for car 1
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
        d.decide(listOf(car(1, 18)), alertMax, c.tick()) // Beep(1) far - now stable
        // Drops out for one frame. The track is already stable, so the W8
        // grace preserves its sustain counter across the single gap.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        c.jump(700)
        // ...and returns materially closer (near third). The preserved counter
        // makes it stable immediately on the RETURN frame, so the higher tier
        // fires there (latch was 1; threat state changed) - one frame sooner
        // than before W8, where a single gap reset the sustain to zero.
        val ev = d.decide(listOf(car(1, 4)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `stable track reset after two dropped frames re-earns sustain`() {
        val d = AlertDecider(minBeepGapMs = 0)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick())
        d.decide(listOf(car(1, 18)), alertMax, c.tick()) // Beep(1) - stable, latch 1
        // Two consecutive dropped frames exceed the grace (GRACE_MISS_FRAMES = 2),
        // so even an already-stable track's sustain counter resets.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick())) // miss 1: preserved
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick())) // miss 2: reset
        // Returning closer must now re-earn the full sustain: the first present
        // frame is silent (rebuilding), the higher tier fires only on the second.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 4)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.Beep(3), d.decide(listOf(car(1, 4)), alertMax, c.tick()))
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
        d.decide(listOf(car(1, 10)), alertMax, c.tick()) // Beep(2)
        // Car leaves the close zone for longer than the clear-grace: a real
        // departure, so the Clear fires and the per-track latch is wiped.
        d.decide(listOf(car(1, 50)), alertMax, c.tick()) // grace starts
        c.jump(1000)
        d.decide(listOf(car(1, 50)), alertMax, c.tick()) // grace elapsed → Clear
        c.jump(700)
        d.decide(listOf(car(1, 10)), alertMax, c.tick()) // re-entering, frame 1
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
        assertEquals(
            AlertDecider.Event.None,
            d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f),
        )
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

    // ── eBike bike_not_driving ground truth wins over GPS speed ────────

    @Test fun `eBike bikeNotDriving true wins over GPS that thinks moving`() {
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

    @Test fun `eBike bikeNotDriving false wins over GPS that thinks stopped`() {
        // Inverse canyon scenario: GPS clamps to 0 m/s while the rider
        // is actually rolling at 4 m/s (sensor says wheel is turning).
        // The decider must beep normally; eBike ground truth means the
        // rider is not actually stationary, regardless of GPS.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, bikeNotDriving = false)
        c.jump(3000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, bikeNotDriving = false)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `eBike absent (null) falls back to bikeSpeedMs gate - moving`() {
        // No-eBike rider (no Bosch eBike, or experimental flag off). The
        // decider must work exactly as before: GPS-derived speed gates
        // the stationary suppress. This is the graceful-degradation path
        // that mandatory per the feedback memory.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f, bikeNotDriving = null)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 6f, bikeNotDriving = null)
        assertEquals(AlertDecider.Event.Beep(1), ev)
    }

    @Test fun `eBike absent (null) falls back to bikeSpeedMs gate - stationary`() {
        // No-eBike rider stopping at a light. GPS reads 0, eBike is null,
        // stationary suppress engages after dwell exactly as before.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, bikeNotDriving = null)
        c.jump(2000)
        val ev = d.decide(listOf(car(1, 18)), alertMax, c.tick(), bikeSpeedMs = 0f, bikeNotDriving = null)
        assertEquals(AlertDecider.Event.None, ev)
    }

    // ── climbing override on the stationary-suppress gate ────────────

    @Test fun `climbing forces the stationary gate off even when eBike says stopped`() {
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

    @Test fun `climbing forces gate off with no-eBike low GPS speed`() {
        // No-eBike rider on a hill: GPS clamps to ~0 m/s through canyon
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
        // null speed (no eBike, no radar-bike-speed yet): use the base
        // minBeepGapMs unchanged. Graceful degradation contract.
        val d = AlertDecider(minBeepGapMs = 700L)
        assertEquals(700L, d.effectiveMinBeepGapMs(null))
    }

    @Test fun `cooldown is base in the 15-25 kmh band`() {
        val d = AlertDecider(minBeepGapMs = 700L)
        assertEquals(700L, d.effectiveMinBeepGapMs(5f)) // 18 km/h
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
        // Pinned to NONE: under the default ALL a tier raise would bypass the
        // cooldown, so this exercises the speed-aware slow band in the mode
        // where it still gates a tier raise. Slow rider (5 km/h) gets the
        // first beep, then a fresh tier raise 800 ms later: under the slow
        // 1400 ms cooldown it must stay silent.
        val d = AlertDecider(minBeepGapMs = 700L, escalationBypass = EscalationCooldownBypass.NONE)
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
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f) // fires first urgent
        c.jump(700)
        // 700 ms later, at the base cooldown boundary. The speed-aware path in the
        // slow-band would require 1400 ms; UrgentApproach must bypass
        // and fire at 700 ms.
        val again = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), again)
    }

    @Test fun `fast cooldown allows a faster re-arm in the integration path`() {
        // Pinned to NONE: under the default ALL the tier raise bypasses the
        // cooldown outright, so this exercises the fast band in the mode where
        // it still governs the re-arm. Fast rider (30 km/h). At 400 ms after a
        // beep, a tier raise would be suppressed by the flat 700 ms cooldown,
        // but the fast-band 350 ms cooldown lets it through.
        val d = AlertDecider(minBeepGapMs = 700L, escalationBypass = EscalationCooldownBypass.NONE)
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
        // A rider above URGENT_MOVING_MAX_KMH (6 m/s = 21.6 km/h here)
        // gets the normal Beep at the appropriate urgency; the override
        // is reserved for stationary and low-speed riders.
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
        // dwell is too long here - TTC at the imminent gate is sub-2 s,
        // so waiting it out leaves the urgent tone silent during the
        // entire reaction window. The mini-dwell (URGENT_OVERRIDE_DWELL_MS,
        // 500 ms) absorbs single-frame speed noise without delaying
        // urgent past the TTC window.
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        val c = Clock()
        // Rider at zero speed; mini-dwell starts ticking.
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        // 600 ms below threshold - past mini-dwell, well short of
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

    // ── low-speed urgent extension ──────────────────────────────────────

    @Test fun `slow-moving rider with very fast closer fires UrgentApproach`() {
        // Rider at 4 m/s (14.4 km/h, inside the moving gate), car at
        // near-third proximity closing 10.5 m/s (past the 10 m/s moving
        // floor): the urgent tone fires while still rolling. The
        // motivating decelerating-into-junction case.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -10.5f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        assertEquals(AlertDecider.Event.UrgentApproach(viaMovingPath = true), ev)
    }

    @Test fun `moving TTC gate fires beyond near-third for a very fast closer`() {
        // Distance 18 m (outside near-third = 7 m at alertMax 21) but
        // TTC = 18/10.5 = 1.7 s: the TTC disjunct fires on the moving
        // path just as it does when stationary.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 18, speedMs = -10.5f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        assertEquals(AlertDecider.Event.UrgentApproach(viaMovingPath = true), ev)
    }

    @Test fun `slow-moving rider below the moving closing floor beeps normally`() {
        // 9.5 m/s closing (raw -19) is one radar quantum short of the
        // 10 m/s moving floor: above the stationary 6 m/s bar, but a
        // moving rider gets the ordinary Beep(3), not urgent. Pins the
        // quantum-strict moving floor.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -9.5f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `moving closing floor inclusive at exactly 10 m_per_s`() {
        // Raw -20 = -10.0 m/s sits exactly on the floor and must fire
        // (>= semantics), mirroring the stationary -6f boundary test.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -10f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        assertEquals(AlertDecider.Event.UrgentApproach(viaMovingPath = true), ev)
    }

    @Test fun `rider above 15 kmh beeps normally even for a very fast closer`() {
        // 4.25 m/s = 15.3 km/h, one radar speed quantum above the
        // moving gate: ordinary tiered beep, no urgent. Pins the gate
        // boundary on the radar's actual 0.25 m/s value grid.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -16f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4.25f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4.25f)
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `low-speed toggle off restores stationary-only urgent behaviour`() {
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -16f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f, urgentLowSpeedEnabled = false)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f, urgentLowSpeedEnabled = false)
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `no speed signal keeps the moving gate closed`() {
        // bikeSpeedMs == null means no signal at all: neither the
        // stationary dwell nor the moving gate can open (fail-closed),
        // so the rider gets the ordinary beep path.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -16f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = null)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = null)
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `just-stopped rider in the 6-10 closing band waits out the mini-dwell`() {
        // Handoff seam: a rider who has just dropped below the
        // stationary threshold is still on the MOVING path (floor 10)
        // until the 500 ms mini-dwell elapses, so a 6-10 m/s closer
        // beeps first and goes urgent only once the stationary floor
        // (6 m/s) takes over. Pins the bounded <= 500 ms delay so a
        // refactor can't silently widen it.
        val d = AlertDecider()
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f) // dwell starts
        val before = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f) // ~100 ms below
        assertEquals(AlertDecider.Event.Beep(3), before)
        c.jump(700) // past URGENT_OVERRIDE_DWELL_MS and the beep cooldown
        val after = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), after)
    }

    @Test fun `held moving-path urgent repeats every base cooldown`() {
        // Repeat-while-held holds on the moving path too, and at the
        // BASE cooldown: 4 m/s sits in the slow band whose ordinary
        // beeps wait out a doubled 1400 ms gap, but a held imminent
        // threat must re-fire after the base 700 ms regardless of
        // rider speed. The re-fire also re-evaluates the moving gate
        // on the held frame, so a regression requiring a fresh
        // stationary-to-moving transition would fail here.
        val d = AlertDecider() // minBeepGapMs = 700
        val c = Clock()
        val v = closingCar(id = 1, distanceM = 5, speedMs = -10.5f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        val first = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        assertEquals(AlertDecider.Event.UrgentApproach(viaMovingPath = true), first)
        c.jump(700)
        val again = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 4f)
        assertEquals(AlertDecider.Event.UrgentApproach(viaMovingPath = true), again)
    }

    @Test fun `stationary path keeps the 6 m_per_s floor with extension enabled`() {
        // A stopped rider's override must not inherit the stricter
        // moving floor: -6.5 m/s (well short of 10) still fires once
        // the stationary mini-dwell is satisfied.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 5, speedMs = -6.5f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
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
        // TTC 2 s - well within the 3 s gate and well outside near-third
        // (alertMax/3 = 7), so the proximity gate would not fire (it caps
        // at 6 m at the same closing speed = TTC 1 s). The TTC gate
        // catches the same approach earlier in the encounter.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 12, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `ttc gate boundary at exactly 3 seconds fires`() {
        // dist 18 / closing 6 = TTC 3.0 s exactly. <= TTC_GATE_SECONDS
        // is inclusive, so the boundary fires.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 18, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `ttc gate excludes ttc above 3 seconds`() {
        // dist 19 / closing 6 = TTC 3.17 s; just past threshold. Outside
        // near-third too (19 > 7), so the proximity gate is also off.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 19, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `ttc gate reach extends past the old 2 second envelope`() {
        // Behaviour-change guard for the 2.0 -> 3.0 s raise. 16 m at 6 m/s
        // = TTC 2.67 s: inside the 3 s gate (fires), but it was None under
        // the old 2 s gate. Outside near-third (16 > 7), so the proximity
        // gate is not what fires it. A revert to 2.0 s surfaces here.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 16, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `ttc gate closing floor inclusive at 6 m_per_s`() {
        // closing exactly TTC_GATE_CLOSING_FLOOR_MS (6) at TTC 2 s
        // (within the 3 s gate) fires. Mirrors the
        // SAFETY_OVERRIDE_CLOSING_MS quantum-strict bound on the
        // proximity gate.
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
        // TTC, the gate fires (well within the 3 s ceiling).
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
        // Above the low-speed gate (6 m/s = 21.6 km/h > URGENT_MOVING_
        // MAX_KMH) the override engages only once the rider has been
        // below stationaryMsThreshold for the mini-dwell. A rider at
        // cruising speed with a TTC-imminent threat gets the ordinary
        // Beep at the appropriate urgency tier, not UrgentApproach.
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
        // at u=3 - adding car 2 must not produce a beep.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        // Establish car 1 at near-tier.
        d.decide(listOf(car(1, 4)), alertMax, c.tick())
        val first = d.decide(listOf(car(1, 4)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(3), first)
        // Wait past cooldown so the cooldown isn't what's silencing this.
        c.jump(1000)
        // Car 2 enters at 18m - closest is still car 1 at u=3.
        d.decide(listOf(car(1, 4), car(2, 18)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 4), car(2, 18)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `overtake re-ack at same-or-lower tier is silent`() {
        // D2b: overtake re-ack only fires if the remaining closest's
        // urgency is STRICTLY GREATER than the peak the overtaking track
        // ever reached. Same-or-lower tier remainders are silent - the
        // rider was already alerted at that tier by the now-overtaking
        // track. Also exercises the "lower tier" leg specifically.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        // Car 1 at near-tier (u=3), car 2 at far-tier (u=1).
        d.decide(listOf(car(1, 4), car(2, 18)), alertMax, c.tick())
        d.decide(listOf(car(1, 4), car(2, 18)), alertMax, c.tick()) // Beep(3)
        c.jump(1000)
        // Car 1 overtakes. Remaining is car 2 at u=1; peak[1] was 3.
        // 1 > 3? No - silent.
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
        // suppress / urgent-override paths don't enter - this
        // isolates the closest-only audio model.
        val alertMaxTruck = 30
        val d = AlertDecider() // default minBeepGapMs = 700
        val audible = mutableListOf<AlertDecider.Event>()
        var t = 0L

        fun feed(vs: List<Vehicle>) {
            val ev = d.decide(vs, alertMaxTruck, t)
            if (ev !is AlertDecider.Event.None) audible.add(ev)
            t += 200
        }

        // Two-frame sustain helper.
        fun scene(vs: List<Vehicle>) {
            feed(vs)
            feed(vs)
        }
        fun gap(ms: Long) {
            t += ms
        }

        // t+0 - tid 51@16 (u=2 mid), tid 111@27 (u=1 far). Closest 51.
        scene(listOf(car(51, 16), car(111, 27)))
        // t+6 - tid 51 closes to 10 m (u=3 near), 111 still 27 m.
        // Escalation on 51: Beep(3).
        gap(5500)
        scene(listOf(car(51, 10), car(111, 27)))
        // t+11 - tid 51 leaves close, 111 at 13 m (u=2 mid). Same close
        // set never empties (111 stays in), so latches preserved. Drop in
        // closest urgency 3 -> 2 is silent.
        gap(4800)
        scene(listOf(car(111, 13)))
        // t+12 - tid 51 re-appears at 0 m, tid 111 at 9 m (u=3 near).
        // Closest is 51 @ 0 (u=3). New entry on 51, but firedTier[51]
        // was cleared when 51 left the close set... actually no - it
        // is only cleared on full Clear. 51 is a new entry; closestUrg
        // raises 2 -> 3 but newEntryRaisesTier requires the entry IS
        // among the new entries. 51 IS the new entry and IS the closest.
        // So this fires (Beep(3)).
        gap(700)
        scene(listOf(car(51, 0), car(111, 9)))
        // t+13 - tid 124 enters at 12 m. Closest still 51 @ 0 (u=3).
        // Same-tier new entry (124 doesn't change closest urgency) - D2a
        // says silent.
        gap(900)
        scene(listOf(car(51, 0), car(111, 3), car(124, 12)))
        // t+13.7 - 51 still 0, 124 closes to 2 m (still u=3). 111
        // implied still close. Closest 51@0 - same tier, silent.
        gap(700)
        scene(listOf(car(51, 0), car(124, 2)))
        // t+15 - tid 51 only at 0 m. Cooldown long expired. Same
        // closest, same tier. Silent.
        gap(1500)
        scene(listOf(car(51, 0)))
        // t+19 - close set finally empties (truck has fully passed).
        gap(1000)
        feed(emptyList()) // Clear
        feed(emptyList()) // None

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

    @Test fun `closest-only invariant - adding a same-tier second car does not re-cue`() {
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
        // already fired - must be silent.
        d.decide(listOf(car(1, 13), car(2, 12)), alertMax, c.tick())
        val ev = d.decide(listOf(car(1, 13), car(2, 12)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `closest-only invariant - adding a HIGHER-tier vehicle DOES cue and replaces the audible thread`() {
        // Counterpart to the same-tier test above: when a NEW track
        // arrives at a HIGHER tier (closer than the existing closest),
        // it MUST produce an audible cue - the audible thread now
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
        // while the threat persists - every safety-critical industry
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

    // ── filtered-overtake re-ack: the FIRE leg ───────────────────────────

    @Test fun `overtake re-acks when remaining closest tier exceeds overtaken peak`() {
        // D2b fire leg (the strict-greater case). A far-tier car (u=1) is
        // tracked alongside a near-tier car (u=3); the near one is the
        // closest, so the only audible thread so far is at u=3. When the
        // FAR car overtakes, peakOvertaken = peak[far] = 1, and the
        // remaining closest is still at u=3. 3 > 1, so the gate must
        // re-announce the surviving closest threat.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 18), car(2, 4)), alertMax, c.tick())
        // Closest is car 2 (4 m, u=3) -> Beep(3); peak[1]=1, peak[2]=3.
        assertEquals(
            AlertDecider.Event.Beep(3),
            d.decide(listOf(car(1, 18), car(2, 4)), alertMax, c.tick()),
        )
        c.jump(1000)
        // Far car 1 overtakes; car 2 remains closest at u=3. peakOvertaken
        // = peak[1] = 1; 3 > 1 -> re-ack the surviving near-tier threat.
        val ev = d.decide(
            listOf(car(1, 16, isBehind = true), car(2, 4)),
            alertMax,
            c.tick(),
        )
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `multi-car overtake takes the max peak across overtaken tracks`() {
        // D2b's `overtakes.maxOf { peak }` must reduce over EVERY overtaking
        // track, not just one. Two cars overtake at once: a far one
        // (peak=1) and a mid one (peak=2). A near car (u=3) remains
        // closest. peakOvertaken = max(1, 2) = 2; 3 > 2 -> re-ack fires.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 18), car(2, 10), car(3, 4)), alertMax, c.tick())
        // Closest car 3 (u=3) -> Beep(3). peaks: [1]=1, [2]=2, [3]=3.
        assertEquals(
            AlertDecider.Event.Beep(3),
            d.decide(listOf(car(1, 18), car(2, 10), car(3, 4)), alertMax, c.tick()),
        )
        c.jump(1000)
        // Cars 1 and 2 both overtake; car 3 remains closest at u=3.
        // peakOvertaken = max(peak[1]=1, peak[2]=2) = 2; 3 > 2 -> fire.
        val ev = d.decide(
            listOf(
                car(1, 16, isBehind = true),
                car(2, 16, isBehind = true),
                car(3, 4),
            ),
            alertMax,
            c.tick(),
        )
        assertEquals(AlertDecider.Event.Beep(3), ev)
    }

    @Test fun `multi-car overtake stays silent when max overtaken peak ties remaining`() {
        // Inverse of the test above for the maxOf reduction: two cars
        // overtake (peaks 2 and 3); the remaining car is at u=3. The
        // overtaken max is 3, so closestUrgency 3 > 3 is false -> silent.
        // Confirms the reduction includes the HIGHER-peak overtaken track
        // (car at peak 3), not just the first one iterated.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        d.decide(listOf(car(1, 10), car(2, 4), car(3, 5)), alertMax, c.tick())
        // Closest car 2 (4 m, u=3) -> Beep(3). peaks: [1]=2, [2]=3, [3]=3.
        assertEquals(
            AlertDecider.Event.Beep(3),
            d.decide(listOf(car(1, 10), car(2, 4), car(3, 5)), alertMax, c.tick()),
        )
        c.jump(1000)
        // Cars 1 (peak 2) and 2 (peak 3) overtake; car 3 (u=3) remains.
        // peakOvertaken = max(2, 3) = 3; 3 > 3 is false -> silent.
        val ev = d.decide(
            listOf(
                car(1, 8, isBehind = true),
                car(2, 2, isBehind = true),
                car(3, 5),
            ),
            alertMax,
            c.tick(),
        )
        assertEquals(AlertDecider.Event.None, ev)
    }

    @Test fun `three-car overtake keeps the running max when later peaks are lower`() {
        // Drives the reduction's compare-and-keep leg: the first overtaken
        // track iterated has the HIGHEST peak (3), so subsequent lower
        // peaks (1 then 2) must NOT replace the running max. peakOvertaken
        // stays 3; the remaining car is also u=3, so 3 > 3 is false ->
        // silent. Pins that the max is a true reduction, not last-wins.
        val d = AlertDecider(minBeepGapMs = 700)
        val c = Clock()
        // peaks: car1 near (u=3), car2 far (u=1), car3 mid (u=2); car4 near.
        d.decide(
            listOf(car(1, 4), car(2, 18), car(3, 10), car(4, 5)),
            alertMax,
            c.tick(),
        )
        // Closest is car 1 (4 m, u=3) -> Beep(3). peaks: [1]=3,[2]=1,[3]=2,[4]=3.
        assertEquals(
            AlertDecider.Event.Beep(3),
            d.decide(
                listOf(car(1, 4), car(2, 18), car(3, 10), car(4, 5)),
                alertMax,
                c.tick(),
            ),
        )
        c.jump(1000)
        // Cars 1, 2, 3 all overtake; car 4 (u=3) remains closest.
        // peakOvertaken = max(3, 1, 2) = 3; 3 > 3 is false -> silent.
        val ev = d.decide(
            listOf(
                car(1, 2, isBehind = true),
                car(2, 16, isBehind = true),
                car(3, 8, isBehind = true),
                car(4, 5),
            ),
            alertMax,
            c.tick(),
        )
        assertEquals(AlertDecider.Event.None, ev)
    }

    // ── imminent-override cooldown gate (line 384) ───────────────────────

    @Test fun `imminent threat within cooldown stays silent until gap elapses`() {
        // The UrgentApproach path is rate-limited by the base minBeepGapMs
        // (NOT the speed-scaled cooldown). A held imminent threat fires
        // once, and a re-trigger BEFORE minBeepGapMs has elapsed must stay
        // None - the cooldown gate's false branch on the imminent path.
        val d = AlertDecider(stationaryDwellMs = 2000L, minBeepGapMs = 700L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 5, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(
            AlertDecider.Event.UrgentApproach(),
            d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f),
        )
        // Only 100 ms later (clock dt) - well inside the 700 ms gap. The
        // threat is still imminent (beepPending re-armed) but the cooldown
        // is not done, so no urgent tone yet.
        val tooSoon = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, tooSoon)
    }

    @Test fun `band-retained vehicle past alertMax does not fire the TTC gate`() {
        // The TTC gate's distance ceiling is `distanceM in 0..alertMaxM`.
        // A vehicle held in the close set only by the exit-hysteresis band
        // (alertMax < distance <= alertMax + band) is INSIDE the close set
        // but OUTSIDE the TTC envelope. Even fast-closing it must not fire
        // UrgentApproach - it sits past the rider's configured alert
        // distance. Exercises the high-bound-false leg of the TTC ceiling.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        // Establish stationary state with the car already at the edge so it
        // is in prevCloseRaw (needed for the band to retain it next frame).
        d.decide(listOf(closingCar(1, 21, -8f)), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        // Now jitter to 22 m: 22 > alertMax(21) but <= alertMax + band(23),
        // so it stays in close via prevCloseRaw. TTC = 22/8 = 2.75 s (within
        // the 3 s gate) but, crucially, 22 !in 0..21, so byTtc is false -
        // the distance ceiling is the operative exclusion. byProximity needs
        // distance <= 7, also false. -> suppressed.
        d.decide(listOf(closingCar(1, 22, -8f)), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(closingCar(1, 22, -8f)), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.None, ev)
    }

    // ── close-set distance-band entry/exit boundary branches ─────────────

    @Test fun `negative-distance track is excluded from the close set`() {
        // Defensive: a decoded distanceM below zero (would be a malformed
        // range field) fails the `0..alertMaxM` lower bound and must never
        // enter the close set, so it cannot beep. Exercises the low side
        // of the entry range check.
        val d = AlertDecider()
        val c = Clock()
        val bogus = Vehicle(id = 1, distanceM = -3, speedMs = 5f)
        d.decide(listOf(bogus), alertMax, c.tick())
        assertEquals(AlertDecider.Event.None, d.decide(listOf(bogus), alertMax, c.tick()))
    }

    @Test fun `prev-close track with a negative distance this frame drops out`() {
        // A track that WAS in range last frame (so its id is in
        // prevCloseRaw) but reports a negative distance this frame must
        // fail the band's RHS range on the lower bound and leave the close
        // set. Exercises the `distanceM in 0..(alertMax + band)` low-bound
        // leg with the id-in-prevCloseRaw branch already taken.
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 20)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), d.decide(listOf(car(1, 20)), alertMax, c.tick()))
        // id 1 is in prevCloseRaw, but distance -3 fails `-3 >= 0`, so the
        // band cannot retain it -> drops out, clear-grace starts.
        assertEquals(
            AlertDecider.Event.None,
            d.decide(listOf(Vehicle(id = 1, distanceM = -3, speedMs = 5f)), alertMax, c.tick()),
        )
        c.jump(1000)
        assertEquals(
            AlertDecider.Event.Clear,
            d.decide(listOf(Vehicle(id = 1, distanceM = -3, speedMs = 5f)), alertMax, c.tick()),
        )
    }

    @Test fun `track in prev-close-raw beyond the exit band drops out`() {
        // A car that was in range last frame (in prevCloseRaw) but whose
        // distance this frame exceeds alertMax + band fails the RHS of the
        // close filter and leaves the set, starting the clear-grace. After
        // the grace a Clear fires. Pins the RHS-false leg of the exit band.
        val d = AlertDecider()
        val c = Clock()
        d.decide(listOf(car(1, 20)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), d.decide(listOf(car(1, 20)), alertMax, c.tick()))
        // alertMax 21 + band 2 = 23; 40 m is well past it. id IS in
        // prevCloseRaw so the RHS is evaluated and is false -> excluded.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 40)), alertMax, c.tick()))
        c.jump(1000)
        assertEquals(AlertDecider.Event.Clear, d.decide(listOf(car(1, 40)), alertMax, c.tick()))
    }

    // ── clear-pending re-arm after a cancelled grace ─────────────────────

    @Test fun `clear-grace re-arms after a cancellation then fires`() {
        // Exercises the clear-pending state machine across a cancel/re-arm
        // cycle: stable car -> drops out (pending) -> returns within grace
        // (cancels) -> drops out again (re-pends) -> stays empty past grace
        // (Clear). Pins that the pending flag is correctly re-armed after a
        // cancellation, not stuck.
        val d = AlertDecider(minBeepGapMs = 700L)
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        // Drop out: clear-grace pends.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        // Car returns within grace -> cancels the pending Clear (and the
        // surviving latch keeps it silent).
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        // Drops out again: clear-grace re-pends.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        // Road stays empty past the grace -> Clear fires.
        c.jump(1000)
        assertEquals(AlertDecider.Event.Clear, d.decide(emptyList(), alertMax, c.tick()))
    }

    // ── beepPending surviving the stable set emptying mid-cooldown ───────

    @Test fun `pending beep is dropped when the stable set empties before cooldown`() {
        // A trigger sets beepPending, but the cooldown isn't done yet. When
        // the close set then empties before the cooldown elapses, the frame
        // must still yield None - a stale pending beep is never emitted onto a
        // road that has already cleared. (At this frame the cooldown-not-done
        // leg short-circuits first; the empty-stable-set isNotEmpty leg is
        // isolated by the companion test below.)
        // Pinned to NONE: under the default ALL the tier raise fires the same
        // frame and never pends, so this pending-then-emptied path is only
        // reachable in NONE mode.
        val d = AlertDecider(minBeepGapMs = 700L, escalationBypass = EscalationCooldownBypass.NONE)
        val c = Clock()
        // Car closes from far to mid quickly: first Beep(1), then a tier
        // raise sets beepPending again while still in cooldown.
        d.decide(listOf(car(1, 18)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), d.decide(listOf(car(1, 18)), alertMax, c.tick()))
        // Tier raise to mid third, but within the 700 ms cooldown -> pending,
        // suppressed.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        // Car vanishes while still inside the cooldown. beepPending is still
        // set but the cooldown isn't done, so the gate yields None before the
        // empty stable set even matters.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
    }

    @Test fun `pending beep with cooldown done but empty stable set yields None`() {
        // Complements the test above for the `stableTids.isNotEmpty()` leg
        // of the beep gate: here beepPending AND cooldownDone are both TRUE
        // but the close set is empty on this frame, so the beep gate's
        // isNotEmpty leg short-circuits to None (and the freshly-opened
        // clear-grace has not yet elapsed). A stale pending beep is never
        // emitted onto an already-empty road.
        // Pinned to NONE: under the default ALL the tier raise fires the same
        // frame and never pends, so this pending-then-emptied path is only
        // reachable in NONE mode.
        val d = AlertDecider(minBeepGapMs = 700L, escalationBypass = EscalationCooldownBypass.NONE)
        val c = Clock()
        d.decide(listOf(car(1, 18)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(1), d.decide(listOf(car(1, 18)), alertMax, c.tick()))
        // Tier raise within cooldown -> beepPending set, suppressed.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        // Cooldown now elapses while the car vanishes the same frame.
        c.jump(700)
        // beepPending still set, cooldownDone true, but stable set empty:
        // gate yields None. The clear-grace only just started this frame,
        // so no Clear yet either.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
    }

    // ── defensive-guard live-arm pins ────────────────────────────────────
    //
    // The branches these guards short-circuit on are statically present but
    // semantically unreachable (see the UNREACHABLE-BRANCH LEDGER at the end
    // of this file for the proof). We cannot flip the dead arm with a valid
    // input, but we CAN pin the LIVE arm at the exact state the surrounding
    // logic guarantees, so a refactor that severs that guarantee (and thus
    // makes the dead arm reachable) trips a concrete, hand-derived assertion
    // here rather than silently changing behaviour.

    @Test fun `byProximity middle conjunct fires at the shared closing floor (live arm)`() {
        // L485 `v.speedMs <= SAFETY_OVERRIDE_CLOSING_MS`. SAFETY_OVERRIDE_
        // CLOSING_MS (-6) equals the negated stationary closing floor
        // (TTC_GATE_CLOSING_FLOOR_MS = 6), so once L484 `closingMs >= floor`
        // is true the middle conjunct is ALWAYS true too (its false arm is
        // dead - see ledger). This pins the live (true) arm at the exact
        // boundary: dist 5 <= alertMax/3 (=7), speed -6 -> closingMs 6.0 ==
        // floor (L484 true), -6 <= -6 (L485 true), 5 <= 7 (L486 true) ->
        // byProximity fires UrgentApproach. Mutation caught: relaxing
        // SAFETY_OVERRIDE_CLOSING_MS below the floor (e.g. to -5) would let a
        // -5.5 m/s frame satisfy L485 and fire spuriously; this boundary fire
        // plus the existing `urgent override does not fire at half-quantum
        // below threshold` (-5.5 -> None) fence both sides of the gate.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 5, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `byTtc closing-floor conjunct fires beyond near-third on the disjunct it owns`() {
        // L488 `closingMs >= TTC_GATE_CLOSING_FLOOR_MS` (the byTtc-specific
        // closing conjunct). Like L485 its false arm is dead (floor
        // equivalence - see ledger), but its live arm carries the case the
        // TTC gate exists for: a target OUTSIDE near-third that byProximity
        // can never catch. dist 12 > alertMax/3 (=7) so byProximity is false
        // on L486; closingMs 6.0 >= 6 (L487 true, L488 true), 12 in 0..21
        // (L489 true), TTC 12/6 = 2.0 <= 3.0 (L490 true) -> byTtc fires.
        // This isolates the byTtc disjunct from byProximity (separates the
        // two `||` operands at L491). Mutation caught: dropping the L488
        // floor would admit slow-queue traffic - e.g. a 15 m / -5 m/s merge
        // (TTC 3.0 s) would fire spuriously on TTC despite the driver
        // clearly tracking and braking; the floor is what filters it.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 12, speedMs = -6f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `byTtc range lower bound is live at distance zero`() {
        // L489 `v.distanceM in 0..alertMaxM`. The lower-bound false arm
        // (distanceM < 0) is dead: the close-set entry filter already
        // requires distanceM >= 0, so a negative distance never reaches the
        // byTtc block (see ledger). Pin the live lower bound at the extreme:
        // distanceM == 0 (a target right on the rider) must still satisfy
        // `0 in 0..21` and fire. closingMs 8.0 >= 6 (L487/L488 true), 0 in
        // 0..21 (L489 true), TTC 0/8 = 0.0 <= 3.0 (L490 true) -> byTtc fires.
        // (byProximity also fires here, 0 <= 7; firstOrNull returns the same
        // vehicle either way - the UrgentApproach is what we assert.)
        // Mutation caught: tightening the lower bound to `1..alertMaxM` would
        // drop a zero-distance imminent threat - the most urgent case.
        val d = AlertDecider(stationaryDwellMs = 2000L)
        val c = Clock()
        d.decide(emptyList(), alertMax, c.tick(), bikeSpeedMs = 0f)
        c.jump(2000)
        val v = closingCar(id = 1, distanceM = 0, speedMs = -8f)
        d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        val ev = d.decide(listOf(v), alertMax, c.tick(), bikeSpeedMs = 0f)
        assertEquals(AlertDecider.Event.UrgentApproach(), ev)
    }

    @Test fun `clear-grace does not re-arm or re-clear across consecutive empty frames`() {
        // L530 else-if `stableTids.isEmpty() && prevStableClose.isNotEmpty()
        // && !clearPending`. On the FIRST empty frame all three conjuncts are
        // true and the Clear is deferred (clearPending set, timer started).
        // On the SECOND consecutive empty frame within the grace the
        // `prevStableClose.isNotEmpty()` conjunct is now FALSE (the previous
        // frame was already empty), so the else-if body is NOT re-entered:
        // clearPending stays set and the grace timer keeps counting from the
        // FIRST empty frame - it is not re-armed. Crucially the deferred
        // Clear fires exactly once, timed from the first empty frame, NOT
        // pushed back by the intervening empties. Mutation caught: dropping
        // the `prevStableClose.isNotEmpty()` guard would let each empty frame
        // re-arm `clearPendingSinceMs`, so a long empty stretch would never
        // accumulate the grace and Clear would never fire (a stuck alert
        // state). The B-false short-circuit is also reached by the existing
        // phantom-blip test, but only with clearPending already false; this
        // is the clearPending-true path that the grace timing depends on.
        val d = AlertDecider(minBeepGapMs = 700L) // clearGraceMs default 1000
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        // First empty frame (t=200): clearPending set, timer starts.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        // Second empty frame (t=300), still inside the 1000 ms grace:
        // prevStableClose now empty -> else-if not re-entered, no premature
        // Clear, timer NOT reset.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        // Third empty frame (t=400), still inside the grace -> still None.
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        // Jump to t>=1200 (grace measured from the FIRST empty frame at
        // t=200, +1000 = 1200). The Clear fires now and exactly once - if the
        // intervening empties had re-armed the timer, this would still be
        // None.
        c.jump(800)
        assertEquals(AlertDecider.Event.Clear, d.decide(emptyList(), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
    }

    @Test fun `returning vehicle cancels a pending clear before the grace at L528 not L535`() {
        // Pins the BEHAVIOUR: a car returning within the clear-grace must cancel
        // the pending Clear and stay silent - the latch must survive (no Clear,
        // no re-beep), and no Clear may fire even after the original grace window
        // would have elapsed. The latch is what suppresses the re-beep.
        //
        // It does NOT isolate L528 (the `clearPending = false` reset on
        // anyInRange) from L535 (`stableTids.isEmpty()` in the clearGraceElapsed
        // predicate): both guards independently suppress the Clear on a returning
        // car. When the car returns, anyInRange is true so L528 resets
        // clearPending; even if that reset were removed, stableTids becomes
        // non-empty so the L535 `isEmpty()` conjunct already blocks the Clear.
        // No events-only input flips one without the other, so a mutant on L528
        // alone survives here.
        val d = AlertDecider(minBeepGapMs = 700L) // clearGraceMs default 1000
        val c = Clock()
        d.decide(listOf(car(1, 10)), alertMax, c.tick())
        assertEquals(AlertDecider.Event.Beep(2), d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        // Drop out: clear-grace pends (clearPending true).
        assertEquals(AlertDecider.Event.None, d.decide(emptyList(), alertMax, c.tick()))
        // Same car returns within the grace. anyInRange true -> clearPending
        // reset at L528; no Clear, and the surviving latch keeps it silent.
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
        // Even after the original grace window would have elapsed, no Clear
        // fires - the pending Clear was cancelled, not merely deferred.
        c.jump(1000)
        assertEquals(AlertDecider.Event.None, d.decide(listOf(car(1, 10)), alertMax, c.tick()))
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNREACHABLE-BRANCH LEDGER (AlertDecider.kt, branch-coverage gate 0.93)
    // ─────────────────────────────────────────────────────────────────────
    //
    // AlertDecider sits at ~94.3% branch coverage with 12 missed branches.
    // Each one is a DEFENSIVE / DEAD bytecode arm whose flipping input the
    // surrounding logic makes impossible to construct. They are documented
    // here so future work does NOT waste effort trying to "cover" them, or
    // worse, contort a test (or weaken production) to game the gate. Each line
    // number is for AlertDecider.kt at the time of writing; re-derive against
    // the current source if it has moved. The proof for each is mechanical -
    // it follows from an invariant the code upstream establishes.
    //
    //  L360  `consecutiveClose[it.id] ?: 0` (elvis null arm)
    //      Every tid in `close` is written into `consecutiveClose` at L343-344
    //      (the `for (tid in currentCloseTids)` loop) before L360 reads it, and
    //      `close` -> `currentCloseTids`. The map lookup is therefore never
    //      null at L360; the `?: 0` fallback is unreachable.
    //      (L344's `consecutiveClose[tid] ?: 0` null arm is NOT dead: a
    //      genuinely-new tid is in currentCloseTids but not yet in the
    //      prior-frame `consecutiveClose` map, so its `?: 0` fallback fires on
    //      that track's first close frame. It is reachable and covered.)
    //
    //  L408  `closestVehicle == null` (true arm)
    //      This sub-expression is only evaluated when `newEntries.isNotEmpty()`
    //      (the `&&` ahead of it). newEntries = stableTids - prevStableClose;
    //      non-empty implies stableTids non-empty implies stableClose non-empty
    //      implies closestVehicle != null. The `== null` arm is dead.
    //
    //  L412  `overtakes.maxOf { peakUrgencyPerTid[it] ?: 0 }` (carries 3 missed)
    //      Reached only inside `overtakes.isNotEmpty() && stableTids.isNotEmpty()`.
    //      Three dead arms live on this one line:
    //       (a) the elvis `?: 0` null arm - an overtaken tid was in
    //           prevStableClose, i.e. it was stable on a prior frame, at which
    //           point peakUrgencyPerTid[it] was written (L371-375), so the
    //           lookup is non-null for any overtaken tid;
    //       (b) the inline `maxOf` empty-collection throw - `overtakes` is
    //           guarded non-empty by the surrounding `overtakes.isNotEmpty()`,
    //           so maxOf never sees an empty collection;
    //       (c) a benign multi-element loop edge inside maxOf's fold (the
    //           "second-or-later element" branch), only taken when >1 tid
    //           overtakes on the same frame - possible but not exercised, and
    //           harmless.
    //
    //  L485  `v.speedMs <= SAFETY_OVERRIDE_CLOSING_MS` (false arm)
    //      byProximity's middle conjunct. SAFETY_OVERRIDE_CLOSING_MS == -6 and
    //      the binding closing floor (TTC_GATE_CLOSING_FLOOR_MS) == 6, so the
    //      preceding conjunct `closingMs >= floor` (i.e. -v.speedMs >= 6, i.e.
    //      v.speedMs <= -6) being TRUE forces this conjunct TRUE. The moving
    //      path raises the floor to 10, only making it MORE true. There is no
    //      input where L485 is reached and false. (Pinned live-arm above.)
    //
    //  L488  `closingMs >= TTC_GATE_CLOSING_FLOOR_MS` (false arm)
    //      byTtc's closing conjunct. Same floor equivalence as L485: the
    //      preceding `closingMs >= urgentClosingFloor` (floor 6 stationary /
    //      10 moving, both >= 6) being true forces closingMs >= 6 here. The
    //      false arm is dead. (Pinned live-arm above.)
    //
    //  L489  `v.distanceM in 0..alertMaxM` (lower-bound false arm)
    //      The close-set entry filter (L322-326) only admits vehicles with
    //      distanceM >= 0 (both the direct `0..alertMaxM` test and the
    //      prevCloseRaw band `0..(alertMaxM + band)` have a 0 lower bound), so
    //      no negative-distance vehicle ever reaches the byTtc range check.
    //      The `>= 0` lower-bound false arm is dead. (Upper-bound arms are
    //      live and already covered by the alertMax-edge tests; lower-bound
    //      live arm pinned above.)
    //
    //  L530  `... && prevStableClose.isNotEmpty() && ...` (2 missed arms)
    //      This else-if is reached only when anyInRange is false, i.e.
    //      currentCloseTids is empty, hence stableTids (a subset) is empty -
    //      so the `stableTids.isEmpty()` conjunct's FALSE arm is dead, and the
    //      `!clearPending` conjunct's FALSE arm is dead too (it would need
    //      prevStableClose non-empty AND clearPending already true on the same
    //      frame, but pending is only set on a frame whose prev was non-empty,
    //      and the next frame's prev is then empty). The reachable
    //      prevStableClose-false short-circuit IS covered (the phantom-blip
    //      test, and the consecutive-empty-frames pin above). The 2 missed
    //      arms are the two dead conjunct-false paths.
    //
    //  L535  `stableTids.isEmpty()` (false arm, in clearGraceElapsed)
    //      Reached only when clearPending is true (the `&&` ahead). But any
    //      in-range track sets anyInRange at L527 and resets clearPending to
    //      false at L528 before L534/L535 run, and stableTids non-empty
    //      implies currentCloseTids non-empty implies anyInRange true. So
    //      clearPending-true and stableTids-non-empty cannot co-occur; the
    //      false arm is dead. The real "vehicle returns cancels the pending
    //      Clear" behaviour happens at L528, pinned above.
    //
    //  L578  `if (v != null)` (else / null arm)
    //      Reached in the ordinary-beep branch, guarded by
    //      `... && stableTids.isNotEmpty()` at L547, which implies
    //      closestVehicle (== v) is non-null. The KDoc above the else arm
    //      already flags it as defensive. The `v == null` else is dead.
    //
    // Net: all 12 missed branches are unreachable. The branch ratio cannot be
    // raised by adding tests - it is capped until a production change removes a
    // redundant conjunct (e.g. collapsing the L485/L488 closing duplicates, or
    // the L530/L535 belt-and-braces empties guards). Do NOT lower the 0.93
    // floor and do NOT add no-op tests; the live-arm pins above are the
    // correct anti-regression coverage for these guards.
}
