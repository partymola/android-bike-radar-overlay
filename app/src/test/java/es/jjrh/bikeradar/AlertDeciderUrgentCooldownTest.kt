// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.AlertDecider.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The imminent-impact cue is the action channel and must not be mutable by an
 * awareness beep. These pin [UrgentCooldown]: under the shipped default an
 * urgent fires even when a beep sounded inside the beep cooldown, and the
 * retained [UrgentCooldown.SHARED_WITH_BEEPS] value still reproduces the old
 * behaviour so the corpus A/B stays meaningful.
 *
 * Found on the road: a legitimate escalation beep landing 288 ms before an
 * imminent-impact frame silenced the warning entirely, because both cues drew
 * on one 700 ms gap.
 */
class AlertDeciderUrgentCooldownTest {

    private val alertMax = 21

    // -11 m/s clears the stricter closing floor the moving urgent path uses.
    private fun closer(id: Int, distanceM: Int, speedMs: Float = -11f) = Vehicle(id, distanceM, speedMs)

    /**
     * Drives a stationary rider up to an imminent threat: a car sustains into
     * the close set at a mid-tier distance (earning a beep), then jumps inside
     * the proximity gate a fraction of the beep cooldown later. Returns the
     * cues in order.
     */
    private fun beepThenImminent(mode: UrgentCooldown): List<Event> {
        val d = AlertDecider(urgentCooldown = mode)
        val out = mutableListOf<Event>()
        // Rider rolling, so ordinary beeps are not stationary-suppressed. The
        // car drifts in slowly at first: mid-tier, well under every urgent
        // gate, so the only cue is the awareness beep.
        out += d.decide(listOf(closer(1, 12, speedMs = -2f)), alertMax, 0L, bikeSpeedMs = 4f)
        out += d.decide(listOf(closer(1, 12, speedMs = -2f)), alertMax, 100L, bikeSpeedMs = 4f)
        // 300 ms later - well inside the 700 ms beep cooldown - the same car
        // is inside alertMax/3 and closing hard: the imminent-impact gate.
        out += d.decide(listOf(closer(1, 5)), alertMax, 400L, bikeSpeedMs = 4f)
        return out.filter { it != Event.None }
    }

    @Test fun `an awareness beep cannot mute the urgent cue`() {
        val cues = beepThenImminent(UrgentCooldown.EPISODE_ONLY)
        assertTrue(
            "expected a beep then an urgent, got $cues",
            cues.size == 2 && cues[0] is Event.Beep && cues[1] is Event.UrgentApproach,
        )
    }

    @Test fun `the retained SHARED_WITH_BEEPS value reproduces the old suppression`() {
        // Pins the A/B knob's contract: without it the corpus replay comparing
        // the two urgent policies would be comparing nothing.
        val cues = beepThenImminent(UrgentCooldown.SHARED_WITH_BEEPS)
        assertEquals(1, cues.size)
        assertTrue("expected only the awareness beep, got $cues", cues[0] is Event.Beep)
    }

    /**
     * The radar-only rider stopped at a light: speed comes from the radar's
     * own status field, `bikeNotDriving` is null because there is no eBike to
     * ask. The beep fires while still rolling and stamps the cooldown; the
     * urgent frame then lands 650 ms later - inside the 700 ms gap, so the
     * shared-cooldown rule would swallow it, and past the 500 ms stationary
     * dwell, so the stationary gate is open.
     */
    private fun stationaryBeepThenImminent(mode: UrgentCooldown): List<Event> {
        val d = AlertDecider(urgentCooldown = mode)
        val out = mutableListOf<Event>()
        out += d.decide(listOf(closer(1, 12, speedMs = -2f)), alertMax, 0L, bikeSpeedMs = 3f)
        out += d.decide(listOf(closer(1, 12, speedMs = -2f)), alertMax, 100L, bikeSpeedMs = 3f)
        out += d.decide(listOf(closer(1, 12, speedMs = -2f)), alertMax, 200L, bikeSpeedMs = 0f)
        out += d.decide(listOf(closer(1, 5)), alertMax, 750L, bikeSpeedMs = 0f)
        return out.filter { it != Event.None }
    }

    @Test fun `the same holds on the stationary path, with no eBike`() {
        // The moving path and the stationary path open the urgent gate by
        // different tests, and only the moving one is exercised above.
        val cues = stationaryBeepThenImminent(UrgentCooldown.EPISODE_ONLY)
        assertTrue(
            "stationary rider must still get the urgent cue, got $cues",
            cues.any { it is Event.UrgentApproach },
        )
    }

    @Test fun `on the stationary path too, SHARED_WITH_BEEPS suppresses it`() {
        // The paired half. Without this the test above would pass under either
        // mode if the timings ever drifted to the edge of the cooldown, and
        // would then be pinning nothing.
        val cues = stationaryBeepThenImminent(UrgentCooldown.SHARED_WITH_BEEPS)
        assertTrue(
            "the old rule must swallow the stationary urgent, got $cues",
            cues.none { it is Event.UrgentApproach },
        )
    }

    @Test fun `an urgent still holds off the next awareness beep`() {
        // The yield is asymmetric on purpose: the urgent ignores the beep
        // cooldown, but firing one still stamps lastBeepAtMs, so an awareness
        // beep cannot immediately pile on behind it.
        val d = AlertDecider(urgentCooldown = UrgentCooldown.EPISODE_ONLY)
        d.decide(listOf(closer(1, 12, speedMs = -2f)), alertMax, 0L, bikeSpeedMs = 4f)
        d.decide(listOf(closer(1, 12, speedMs = -2f)), alertMax, 100L, bikeSpeedMs = 4f)
        val urgent = d.decide(listOf(closer(1, 5)), alertMax, 400L, bikeSpeedMs = 4f)
        assertTrue("setup did not produce an urgent, got $urgent", urgent is Event.UrgentApproach)
        // A frame 200 ms later with a slower car at a fresh tier: silent,
        // because the urgent consumed the beep cooldown.
        val after = d.decide(
            listOf(Vehicle(id = 2, distanceM = 6, speedMs = -1f), closer(1, 5)),
            alertMax,
            600L,
            bikeSpeedMs = 4f,
        )
        assertEquals(Event.None, after)
    }
}
