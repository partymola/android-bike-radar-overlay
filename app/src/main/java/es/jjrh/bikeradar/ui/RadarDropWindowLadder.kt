// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.data.Prefs
import kotlin.math.abs

/**
 * The rungs the dead-radar cue's traffic window offers, and the mapping
 * between a slider position and the seconds
 * [es.jjrh.bikeradar.data.Prefs.radarDropTrackWindowSec] stores.
 *
 * Rungs rather than a continuous range because the useful values span two
 * orders of magnitude: a linear slider from 30 s to an hour would put every
 * setting a commuter might pick inside its first millimetre.
 *
 * The ends ARE the pref's clamp bounds ([Prefs.RADAR_DROP_TRACK_WINDOW_MIN_SEC],
 * [Prefs.RADAR_DROP_TRACK_WINDOW_MAX_SEC]), so every position the rider can
 * reach round-trips through the store unchanged;
 * `RadarDropWindowLadderTest.theLaddersEndsAreTheStoresBounds` pins it.
 */
internal object RadarDropWindowLadder {

    val RUNGS_SEC = listOf(30, 60, 120, 300, 600, 1800, 3600)

    /** Compose counts the positions BETWEEN the two ends, so this is not the
     *  rung count. Derived here so it cannot drift from the list above. */
    val sliderSteps: Int get() = RUNGS_SEC.size - 2

    /** The rung nearest a stored value. A value off the ladder (hand-edited,
     *  or carried in from a backup) renders on the closest rung rather than
     *  snapping the rider's setting to an end; a tie takes the shorter window,
     *  which is the one that cues less. */
    fun indexOf(sec: Int): Int = RUNGS_SEC.indices.minBy { abs(RUNGS_SEC[it] - sec) }

    fun secondsAt(index: Int): Int = RUNGS_SEC[index.coerceIn(RUNGS_SEC.indices)]
}
