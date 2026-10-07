// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The count itself, with toggles actually on.
 *
 * The Settings menu goldens all show one fixed combination, so a count that is
 * wrong for any other combination renders correctly in every one of them and
 * is wrong on a rider's phone. Only these tests reach the other combinations.
 */
@RunWith(RobolectricTestRunner::class)
class ExperimentalFeaturesCountTest {

    private fun snap(urgentWait: Boolean = false, precog: Boolean = false, dropFallback: Boolean = false) = SnapshotFixtures.defaultPrefsSnapshot()
        .copy(urgentUnconfidentWaitEnabled = urgentWait, precogEnabled = precog, radarDropTrackFallbackEnabled = dropFallback)

    @Test
    fun nothingOnCountsNone() {
        assertEquals(0, ExperimentalFeatures.onCount(snap()))
    }

    @Test
    fun eachToggleCountsOnItsOwn() {
        // Separately, because a count reading one flag twice gets all of
        // these right only if it happens to read the right one.
        assertEquals(1, ExperimentalFeatures.onCount(snap(urgentWait = true)))
        assertEquals(1, ExperimentalFeatures.onCount(snap(precog = true)))
        assertEquals(1, ExperimentalFeatures.onCount(snap(dropFallback = true)))
    }

    @Test
    fun allOnCountsAll() {
        assertEquals(3, ExperimentalFeatures.onCount(snap(urgentWait = true, precog = true, dropFallback = true)))
    }

    @Test
    fun theTotalIsEveryToggleTheScreenHas() {
        assertEquals(3, ExperimentalFeatures.total)
    }
}
