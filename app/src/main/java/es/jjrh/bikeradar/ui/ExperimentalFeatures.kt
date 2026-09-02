// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.data.PrefsSnapshot

/**
 * Every toggle on the Experimental screen, so the Settings row can say how
 * many there are and how many are on.
 *
 * One list rather than a hand-written sentence, because the sentence went
 * stale: it named Precog and nothing else, so a second toggle shipped
 * invisible from the Settings row and a rider who had turned it on saw "All
 * off". `ExperimentalFeaturesSeamTest` reads the screen and fails if the two
 * fall out of step again.
 */
object ExperimentalFeatures {

    private val FLAGS: List<(PrefsSnapshot) -> Boolean> = listOf(
        { it.precogEnabled },
        { it.radarDropTrackFallbackEnabled },
    )

    val total: Int get() = FLAGS.size

    fun onCount(snap: PrefsSnapshot): Int = FLAGS.count { it(snap) }
}
