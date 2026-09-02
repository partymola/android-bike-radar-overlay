// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings row's count and the Experimental screen are two lists of the
 * same thing, and they drifted: a toggle was added to the screen and nothing
 * told the row, so it kept reporting on one feature and a rider who had turned
 * the other on read "All off".
 *
 * Nothing in the type system connects them, so this reads the screen's source
 * and counts its toggles. It fails on the commit that adds the third one,
 * rather than on the ride where the subtitle turns out to be wrong.
 */
class ExperimentalFeaturesSeamTest {

    private fun screen(): File = RepoFiles.mainSource("ui/SettingsExperimental.kt")

    private fun renderedToggles(): Int = screen().readText().split("SettingsToggleRow(").size - 1

    @Test
    fun everyToggleOnTheExperimentalScreenIsCounted() {
        assertTrue("the screen moved: ${screen().absolutePath}", screen().isFile)

        val rendered = renderedToggles()

        assertEquals(
            "the Experimental screen renders $rendered toggles; ExperimentalFeatures knows about ${ExperimentalFeatures.total}",
            rendered,
            ExperimentalFeatures.total,
        )
    }

    @Test
    fun theScreenActuallyRendersToggles() {
        // The anti-vacuity assertion belongs on the READ side. Asserting
        // `total > 0` instead would pass while this test's own parse found
        // nothing - zero rendered against a non-zero total fails the check
        // above, but a parse that silently stops matching is the failure worth
        // naming here rather than diagnosing there.
        assertTrue(
            "no SettingsToggleRow( found, so the count above compares against a parse that found nothing",
            renderedToggles() > 0,
        )
    }
}
