// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.AlertDecider.Companion.DEFAULT_PASS_CLEARANCE_M
import es.jjrh.bikeradar.AlertDecider.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The imminent-impact cue is vetoed on what the vehicle will MISS the bike by,
 * not on its offset from the radar. These pin that difference.
 *
 * Found on the road: a rider stopped waiting to turn right, angled about 10 deg
 * across the traffic line, took four urgent cues in twelve seconds as ordinary
 * traffic went round him at 1.4-2.2 m. Every cue was a real car and every gate
 * behaved as specified - the specification was measuring the wrong thing.
 *
 * Every approach here starts beyond `alertMaxM`, as real ones do. A track that
 * first appears already inside the TTC window fires before any fit exists, by
 * design (see [AlertDecider.predictedPassFit]'s fail-open), so a scenario built
 * that way would test the fail-open and not the veto.
 */
class AlertDeciderPassClearanceTest {

    private val alertMax = 21

    /** Far enough out to build history, then in through the gates. */
    private val approachDistances = listOf(40, 34, 28, 22, 18, 14, 10, 7, 5)

    private fun car(distanceM: Int, rangeXm: Float) = Vehicle(id = 1, distanceM = distanceM, speedMs = -9f, rangeXm = rangeXm)

    /**
     * Walks one track in along `rangeXm = a + b * distanceM` past a stationary
     * rider and returns the cues.
     */
    private fun approach(
        interceptM: Float,
        slopePerM: Float,
        scoring: PassScoring = PassScoring.BIKE_ENVELOPE,
        clearanceM: Float = DEFAULT_PASS_CLEARANCE_M,
        distances: List<Int> = approachDistances,
        gateLines: MutableList<String> = mutableListOf(),
    ): List<Event> {
        val d = AlertDecider(passScoring = scoring, onGateEvent = { gateLines.add(it) })
        val out = mutableListOf<Event>()
        distances.forEachIndexed { i, dist ->
            out += d.decide(
                vehicles = listOf(car(dist, interceptM + slopePerM * dist)),
                alertMaxM = alertMax,
                nowMs = 600L + i * 300L,
                bikeSpeedMs = 0f,
                passClearanceM = clearanceM,
            )
        }
        return out.filter { it != Event.None }
    }

    private fun List<Event>.urgents() = count { it is Event.UrgentApproach }

    @Test fun `a pass predicted to clear the whole bike is vetoed`() {
        // Holds 3 m off the centreline all the way in - outside the 1.5 m
        // default at both ends of the bike.
        assertEquals(0, approach(interceptM = 3f, slopePerM = 0f).urgents())
    }

    @Test fun `a pass predicted inside the clearance fires`() {
        assertTrue(approach(interceptM = 0.4f, slopePerM = 0f).urgents() > 0)
    }

    @Test fun `a line crossing the centreline inside the bike fires`() {
        // Predicted to cross from one side to the other between the front
        // wheel and the radar: a predicted hit, clearance zero.
        assertTrue(approach(interceptM = 0.6f, slopePerM = 0.35f).urgents() > 0)
    }

    @Test fun `a track converging onto the front wheel fires though it clears the radar`() {
        // At the radar the fit says 2.6 m. Two metres forward, where the front
        // wheel is, it says 1.4 m - inside the clearance. Scoring the radar
        // alone cannot see that; scoring the bike can.
        val cues = approach(interceptM = 2.6f, slopePerM = 0.6f)
        assertTrue("expected the converging track to warn, got $cues", cues.urgents() > 0)
    }

    @Test fun `the same converging track is vetoed when only the radar is scored`() {
        // The discriminator as a difference: identical geometry, scored at the
        // radar. 2.6 m clears the shipped RADAR_POINT threshold, so the front
        // wheel's 1.4 m never enters the decision.
        val cues = approach(interceptM = 2.6f, slopePerM = 0.6f, scoring = PassScoring.RADAR_POINT)
        assertEquals(0, cues.urgents())
    }

    @Test fun `the configured clearance is what decides`() {
        // One geometry, two riders' settings. 2.0 m off the centreline is
        // inside a 2.5 m margin and outside a 1.0 m one.
        assertTrue(approach(interceptM = 2f, slopePerM = 0f, clearanceM = 2.5f).urgents() > 0)
        assertEquals(0, approach(interceptM = 2f, slopePerM = 0f, clearanceM = 1.0f).urgents())
    }

    @Test fun `a track with too little approach to fit still fires`() {
        // Fails OPEN: a first warning is never withheld for lack of history.
        // Three sightings across 2 m is under both confidence floors, and the
        // geometry would otherwise be vetoed at 3 m off the centreline.
        val cues = approach(interceptM = 3f, slopePerM = 0f, distances = listOf(9, 8, 7))
        assertTrue("expected the unfittable track to warn, got $cues", cues.urgents() > 0)
    }

    @Test fun `a late swing-in pulls the fit inside the clearance and fires`() {
        val d = AlertDecider()
        val out = mutableListOf<Event>()
        // A long wide approach: on this history alone the car passes clear.
        listOf(40, 34, 28, 22, 16).forEachIndexed { i, dist ->
            out += d.decide(listOf(car(dist, 4f)), alertMax, 600L + i * 300L, bikeSpeedMs = 0f)
        }
        // Then it swings in hard. The refit crosses inside the clearance at
        // the front of the bike, so the cue fires on that frame rather than
        // waiting for the wide history to age out.
        out += d.decide(listOf(car(7, 0.3f)), alertMax, 2400L, bikeSpeedMs = 0f)
        assertTrue(
            "expected the swing-in to warn, got ${out.filter { it != Event.None }}",
            out.filterIsInstance<Event.UrgentApproach>().isNotEmpty(),
        )
    }

    @Test fun `the default clearance sits between two and one metres`() {
        // Pins the shipped default from both sides without asserting the
        // constant against itself: 2.0 m off the centreline is outside it and
        // 1.2 m is inside.
        assertEquals(0, approach(interceptM = 2f, slopePerM = 0f).urgents())
        assertTrue(approach(interceptM = 1.2f, slopePerM = 0f).urgents() > 0)
    }

    @Test fun `a pass exactly at the clearance is vetoed`() {
        assertEquals(0, approach(interceptM = 2f, slopePerM = 0f, clearanceM = 2f).urgents())
    }

    @Test fun `the rear of the bike counts too`() {
        // Diverging: the car is drawing away laterally as it comes, so the
        // closest point is the REAR of the envelope, not the front. 1.45 m
        // there is inside the clearance; scored at the radar against this
        // same margin it reads 1.55 m and would be vetoed.
        assertTrue(approach(interceptM = 1.55f, slopePerM = -0.2f).urgents() > 0)
    }

    // The three below pin the crossing branch's VALUE, not just that it is
    // taken. It only changes an outcome at margins under a metre, which is
    // inside what the setting allows, so it is reachable and worth pinning.

    @Test fun `a predicted hit scores zero clearance, not merely a small one`() {
        // Crosses the centreline between the front wheel and the radar:
        // front -0.5 m, rear +1.06 m. Scoring the nearer END instead of
        // recognising the crossing gives 0.5 m, which clears the 0.4 m
        // margin and silences a predicted hit. Only the zero does not.
        // 0.4 m is below MIN_PASS_CLEARANCE_M, so no rider can reach this
        // configuration - it pins the crossing value below anything
        // Settings can express, where the 0.7 m case below pins it lower
        // than a margin a rider CAN set.
        assertTrue(approach(interceptM = 0.75f, slopePerM = 0.625f, clearanceM = 0.4f).urgents() > 0)
    }

    @Test fun `a predicted hit is never vetoed, even at a zero margin`() {
        // The veto compares >= the margin, so a margin of zero would veto a
        // clearance of zero - silencing precisely the case the cue exists
        // for. Prefs clamps the rider's value far above this; the guard
        // lives in the decider so no later caller can reintroduce it.
        assertTrue(approach(interceptM = 0.6f, slopePerM = 0.35f, clearanceM = 0f).urgents() > 0)
    }

    @Test fun `the lowest margin the slider allows still vetoes a wider pass`() {
        // 0.5 m is the floor Settings can express; 0.6 m off the centreline
        // clears it. Literal, so moving the floor does not move the test.
        assertEquals(0, approach(interceptM = 0.6f, slopePerM = 0f, clearanceM = 0.5f).urgents())
    }

    @Test fun `the highest margin the slider allows still lets a closer pass fire`() {
        // 3.0 m is the ceiling; 2.9 m off the centreline is inside it.
        assertTrue(approach(interceptM = 2.9f, slopePerM = 0f, clearanceM = 3.0f).urgents() > 0)
    }

    @Test fun `the gate line records the margin it judged against`() {
        // Pinned at a NON-default margin on purpose: asserting it at 1.5 m
        // would stay green against a hardcoded literal, which is the whole
        // failure this field exists to prevent.
        val lines = mutableListOf<String>()
        approach(interceptM = 2f, slopePerM = 0f, clearanceM = 1.0f, gateLines = lines)
        val veto = lines.single { it.startsWith("# gate urgent-pass-veto tid=1") }
        assertTrue("expected the judged margin, got $veto", veto.contains("gate_clearance_m=1.0"))
        assertTrue("expected the scored clearance, got $veto", veto.contains("min_clearance=2.0"))
    }

    @Test fun `a fit oscillating across the margin does not log once a crossing`() {
        // The gate's bound is one line per verdict per track. Routing the
        // pass verdict through the same one-slot memo as the veto breaks
        // it: a fit hovering at the margin alternates and emits on every
        // crossing, and because that slot is shared it re-arms the
        // off-axis and corroboration verdicts too. The pass verdict keeps
        // its own record, so each verdict still emits once per fit window.
        val lines = mutableListOf<String>()
        val d = AlertDecider(onGateEvent = { lines.add(it) })
        var t = 600L
        fun frame(dist: Int, rx: Float) {
            d.decide(listOf(car(dist, rx)), alertMax, t, bikeSpeedMs = 0f)
            t += 300L
        }
        listOf(40, 34, 28, 22, 18).forEach { frame(it, 1.5f) }
        listOf(16, 15, 14, 13, 12, 11, 10, 9).forEachIndexed { i, dist ->
            frame(dist, if (i % 2 == 0) 0.4f else 2.6f)
        }
        val gate = lines.filter { it.startsWith("# gate urgent-pass-") }
        val kinds = gate.map {
            it.substringAfter("# gate urgent-pass-").substringBefore(" tid=") +
                ":" + it.substringAfter("fit=").substringBefore(" ")
        }
        assertTrue("expected both verdicts to occur, got $gate", kinds.any { it.startsWith("veto") } && kinds.any { it.startsWith("ok") })
        assertEquals("each verdict must be logged once per fit window, got $gate", kinds.size, kinds.toSet().size)
    }

    @Test fun `a confident pass is recorded too, not only a veto`() {
        // Without this the capture log cannot tell a fit that cleared the
        // margin from a track that never had a confident fit at all, which
        // is the first question a reported spurious cue raises.
        val lines = mutableListOf<String>()
        approach(interceptM = 0.4f, slopePerM = 0f, gateLines = lines)
        val ok = lines.single { it.startsWith("# gate urgent-pass-ok tid=1") }
        assertTrue("expected the scored clearance, got $ok", ok.contains("min_clearance=0.4"))
        assertTrue("expected the judged margin, got $ok", ok.contains("gate_clearance_m=1.5"))
    }

    @Test fun `a crossing scores zero even when both ends are clear of the centreline`() {
        // Pins the crossing branch's EXISTENCE, not just its value. Front
        // -0.8 m, rear +0.8 m: the line passes straight through the bike while
        // both ENDS clear a 0.7 m margin. Taking the smaller end instead of
        // recognising the crossing scores 0.8 and vetoes a predicted hit.
        // The ends sit 0.1 m clear of the margin on purpose - put them exactly
        // on it and float noise, not the branch, decides the outcome.
        assertTrue(approach(interceptM = 0.48f, slopePerM = 0.64f, clearanceM = 0.7f).urgents() > 0)
    }

    @Test fun `a converging track that stays on one side is not treated as a hit`() {
        // Front 0.5 m, rear 2.0 m, both the same side: the closest point is
        // 0.5 m, which clears a 0.4 m margin.
        assertEquals(0, approach(interceptM = 1.7f, slopePerM = 0.6f, clearanceM = 0.4f).urgents())
    }

    @Test fun `a diverging track that stays on one side is not treated as a hit`() {
        // Mirror of the above with the near end at the rear: front 2.0 m,
        // rear 0.5 m, same side, so again 0.5 m of clearance.
        assertEquals(0, approach(interceptM = 0.8f, slopePerM = -0.6f, clearanceM = 0.4f).urgents())
    }
}
