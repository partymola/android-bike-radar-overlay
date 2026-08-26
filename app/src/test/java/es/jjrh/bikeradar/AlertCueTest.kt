// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decider-event -> audio-cue mapping. This is the wiring that both
 * BikeRadarService and DebugOverlayService run every frame; a mis-mapping
 * (a close pass routed to the impact cue, or a dropped beep count) would
 * defeat the well-tested AlertDecider and AlertBeeper on either end, so it
 * is pinned here rather than left to the untested service loop.
 */
class AlertCueTest {

    @Test fun beep_mapsToBeep_preservingCount() {
        assertEquals(
            AlertCue.Beep(3),
            AlertCue.forEvent(AlertDecider.Event.Beep(3)),
        )
    }

    @Test fun beep_singleCount() {
        assertEquals(AlertCue.Beep(1), AlertCue.forEvent(AlertDecider.Event.Beep(1)))
    }

    @Test fun clear_mapsToClear() {
        assertEquals(AlertCue.Clear, AlertCue.forEvent(AlertDecider.Event.Clear))
    }

    @Test fun urgent_mapsToUrgent() {
        assertEquals(
            AlertCue.Urgent,
            AlertCue.forEvent(AlertDecider.Event.UrgentApproach()),
        )
    }

    @Test fun none_mapsToSilence() {
        assertEquals(AlertCue.Silence, AlertCue.forEvent(AlertDecider.Event.None))
    }

    // Guard the two dangerous mis-wirings explicitly: a close pass must never
    // become the impact cue, and the impact cue must never degrade to a beep.
    @Test fun beep_neverMapsToUrgent() {
        assertTrue(AlertCue.forEvent(AlertDecider.Event.Beep(3)) is AlertCue.Beep)
    }

    @Test fun urgent_neverMapsToBeep() {
        assertEquals(AlertCue.Urgent, AlertCue.forEvent(AlertDecider.Event.UrgentApproach()))
    }

    // The tier count is what distinguishes one beep cue from another, so it
    // has to survive the mapping at both ends of its range.
    @Test fun beep_countExtremesPropagate() {
        assertEquals(AlertCue.Beep(1), AlertCue.forEvent(AlertDecider.Event.Beep(1)))
        assertEquals(AlertCue.Beep(3), AlertCue.forEvent(AlertDecider.Event.Beep(3)))
    }
}
