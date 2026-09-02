// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The count itself, with toggles actually on.
 *
 * Every golden renders this screen with both off, so a count that always
 * answered zero would render correctly in all of them and be wrong on the
 * only phone that matters: a rider's, with something turned on. That is the
 * shape of the defect this replaced.
 */
@RunWith(RobolectricTestRunner::class)
class ExperimentalFeaturesCountTest {

    private fun snap(precog: Boolean, dropFallback: Boolean) = SnapshotFixtures.defaultPrefsSnapshot()
        .copy(precogEnabled = precog, radarDropTrackFallbackEnabled = dropFallback)

    @Test
    fun nothingOnCountsNone() {
        assertEquals(0, ExperimentalFeatures.onCount(snap(precog = false, dropFallback = false)))
    }

    @Test
    fun eachToggleCountsOnItsOwn() {
        // Separately, because a count reading one flag twice gets both of
        // these right only if it happens to read the right one.
        assertEquals(1, ExperimentalFeatures.onCount(snap(precog = true, dropFallback = false)))
        assertEquals(1, ExperimentalFeatures.onCount(snap(precog = false, dropFallback = true)))
    }

    @Test
    fun bothOnCountsBoth() {
        assertEquals(2, ExperimentalFeatures.onCount(snap(precog = true, dropFallback = true)))
    }

    @Test
    fun theTotalIsEveryToggleTheScreenHas() {
        assertEquals(2, ExperimentalFeatures.total)
    }
}
