// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * The audio cue an [AlertDecider.Event] maps to.
 *
 * Both the live service loop and the debug overlay turn decider events into
 * [AlertBeeper] calls. Pulling that mapping into this pure function keeps the
 * safety-relevant wiring unit-testable - which event fires which cue, and
 * that the beep count and lateral position are carried through. Callers just
 * interpret the returned value by calling the matching beeper method, so a
 * mis-wiring (a close pass routed to the impact cue, or a dropped lateral
 * position) is caught by AlertCueTest rather than only on the road.
 */
sealed interface AlertCue {
    data class Beep(val count: Int, val lateralPos: Float) : AlertCue
    object Clear : AlertCue
    data class Urgent(val lateralPos: Float) : AlertCue
    object Silence : AlertCue

    companion object {
        fun forEvent(event: AlertDecider.Event): AlertCue = when (event) {
            is AlertDecider.Event.Beep -> Beep(event.count, event.lateralPos)
            AlertDecider.Event.Clear -> Clear
            is AlertDecider.Event.UrgentApproach -> Urgent(event.lateralPos)
            AlertDecider.Event.None -> Silence
        }
    }
}
