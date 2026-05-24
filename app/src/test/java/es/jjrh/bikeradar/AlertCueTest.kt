// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decider-event -> audio-cue mapping. This is the wiring that both
 * BikeRadarService and DebugOverlayService run every frame; a mis-mapping
 * (a close pass routed to the impact cue, or a dropped lateral position)
 * would defeat the well-tested AlertDecider and AlertBeeper on either end,
 * so it is pinned here rather than left to the untested service loop.
 */
class AlertCueTest {

    @Test fun beep_mapsToBeep_preservingCountAndLateralPos() {
        assertEquals(
            AlertCue.Beep(3, 0.5f),
            AlertCue.forEvent(AlertDecider.Event.Beep(3, 0.5f)),
        )
    }

    @Test fun beep_singleCount() {
        assertEquals(AlertCue.Beep(1, 0f), AlertCue.forEvent(AlertDecider.Event.Beep(1, 0f)))
    }

    @Test fun clear_mapsToClear() {
        assertEquals(AlertCue.Clear, AlertCue.forEvent(AlertDecider.Event.Clear))
    }

    @Test fun urgent_mapsToUrgent_preservingLateralPos() {
        assertEquals(
            AlertCue.Urgent(-0.8f),
            AlertCue.forEvent(AlertDecider.Event.UrgentApproach(-0.8f)),
        )
    }

    @Test fun none_mapsToSilence() {
        assertEquals(AlertCue.Silence, AlertCue.forEvent(AlertDecider.Event.None))
    }

    // Guard the two dangerous mis-wirings explicitly: a close pass must never
    // become the impact cue, and the impact cue must never degrade to a beep.
    @Test fun beep_neverMapsToUrgent() {
        assertTrue(AlertCue.forEvent(AlertDecider.Event.Beep(3, 0f)) is AlertCue.Beep)
    }

    @Test fun urgent_neverMapsToBeep() {
        assertTrue(AlertCue.forEvent(AlertDecider.Event.UrgentApproach(0f)) is AlertCue.Urgent)
    }

    @Test fun lateralPos_extremesPropagate() {
        assertEquals(
            AlertCue.Beep(2, -1f),
            AlertCue.forEvent(AlertDecider.Event.Beep(2, -1f)),
        )
        assertEquals(
            AlertCue.Urgent(1f),
            AlertCue.forEvent(AlertDecider.Event.UrgentApproach(1f)),
        )
    }
}
