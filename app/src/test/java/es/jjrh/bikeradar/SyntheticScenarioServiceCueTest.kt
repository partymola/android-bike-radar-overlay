// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bench scenario replayed through the real [AlertDecider], asserting it
 * actually produces the cues it exists to demonstrate.
 *
 * `SyntheticScenarioServiceScriptTest` pins that the script is internally
 * consistent. This pins the consequence: consistent data that still fires
 * nothing would be a bench tool that runs, reports success, and auditions
 * silence. The scenario is the only way to hear alert behaviour without a
 * ride, so "it played no cues" has to be a failure rather than an
 * observation someone makes on the day.
 */
@RunWith(RobolectricTestRunner::class)
class SyntheticScenarioServiceCueTest {

    private val svc = SyntheticScenarioService()

    /** Cue events the decider emits over the whole 60 s, stamped with the
     *  scenario time that produced them. Frame cadence matches the service's
     *  own 10 Hz sampling. */
    private fun replay(): List<Pair<Long, AlertDecider.Event>> {
        val decider = AlertDecider()
        val out = mutableListOf<Pair<Long, AlertDecider.Event>>()
        for (step in 0..600) {
            val t = step * 100L
            val ev = decider.decide(
                vehicles = svc.scriptAt(t),
                alertMaxM = 30,
                nowMs = t,
                bikeSpeedMs = svc.bikeSpeedAt(t),
            )
            if (ev !is AlertDecider.Event.None) out.add(t to ev)
        }
        return out
    }

    @Test
    fun theScenarioProducesAudibleCues() {
        val cues = replay()
        assertTrue("the bench scenario fired no cues at all", cues.isNotEmpty())
        val beeps = cues.count { it.second is AlertDecider.Event.Beep }
        assertTrue("expected tier beeps, got ${cues.map { it.second }}", beeps > 0)
    }

    @Test
    fun theScenarioReachesTheImminentImpactCue() {
        // The docstring claims an urgent escalation and a truck that trips the
        // danger band. Both need a closing speed past the urgent floor, so
        // this is the assertion that the scenario still demonstrates the
        // action channel and not only the awareness one.
        val urgents = replay().filter { it.second is AlertDecider.Event.UrgentApproach }
        assertTrue("no urgent cue in the whole scenario", urgents.isNotEmpty())
    }
}
