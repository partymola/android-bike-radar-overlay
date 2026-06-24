// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Redraw-gating for [RadarOverlayView].
 *
 * [RadarState] stamps a fresh [RadarState.timestamp] on every frame (and
 * carries a [RadarState.source] tag), neither of which `onDraw` reads - so a
 * plain `newState == state` guard almost never short-circuits, and the Canvas
 * redraws on every radar frame even when the picture is identical. With a
 * handlebar-mounted phone the overlay is on screen the whole ride, so that is
 * real per-frame work.
 *
 * [overlayRenderEquivalent] is true when two states would draw the SAME frame:
 * it compares only the fields `onDraw` actually consumes - the vehicles, the
 * scenario-time label, and the rider speed that sets the threat-colour bands
 * (the a11y summary likewise reads only the vehicles). The view skips the
 * redraw when this holds.
 *
 * Keep this in lockstep with `onDraw`: if the overlay starts reading another
 * [RadarState] field, add it here too, or the overlay will go stale (a moved
 * vehicle whose box never repaints). That is why this is a pure function with
 * its own test rather than an inline field list.
 */
internal fun overlayRenderEquivalent(a: RadarState, b: RadarState): Boolean = a.vehicles == b.vehicles &&
    a.scenarioTimeMs == b.scenarioTimeMs &&
    a.bikeSpeedMs == b.bikeSpeedMs
