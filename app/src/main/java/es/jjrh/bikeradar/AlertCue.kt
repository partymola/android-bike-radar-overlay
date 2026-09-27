// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

/**
 * The audio cue an [AlertDecider.Event] maps to.
 *
 * The live service loop, the debug overlay and the sound demo turn decider
 * events into [AlertBeeper] calls. Pulling that mapping into this pure function
 * keeps the safety-relevant wiring unit-testable - which event fires which cue,
 * and that the beep count is carried through. Callers hand the result to
 * [playCue], so a mis-wiring (a close pass routed to the impact cue) is caught
 * by AlertCueTest and CuePreviewTest rather than only on the road.
 */
sealed interface AlertCue {
    data class Beep(val count: Int) : AlertCue
    object Clear : AlertCue
    object Urgent : AlertCue
    object Silence : AlertCue

    companion object {
        fun forEvent(event: AlertDecider.Event): AlertCue = when (event) {
            is AlertDecider.Event.Beep -> Beep(event.count)
            AlertDecider.Event.Clear -> Clear
            is AlertDecider.Event.UrgentApproach -> Urgent
            AlertDecider.Event.None -> Silence
        }
    }
}
