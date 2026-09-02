// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarAccessSummaryTest {

    private fun grant(pkg: String, read: Boolean = false, control: Boolean = false) = RadarGrant(pkg, "aa11", pkg, 0L, 0L, read, control)

    @Test
    fun noAppsMeansNothingIsAllowed() {
        assertEquals(RadarAccessSummary.NONE, RadarAccessSummary.of(emptyList()))
    }

    @Test
    fun anAppThatOnlyReadsIsNotReportedAsControlling() {
        assertEquals(
            RadarAccessSummary.READING,
            RadarAccessSummary.of(listOf(grant("a", read = true))),
        )
    }

    @Test
    fun oneControllingAppOutranksSeveralReadingOnes() {
        // The row has to name the highest trust the rider has given, not the
        // commonest, or an app that can switch the tail light off hides behind
        // two that can only look.
        assertEquals(
            RadarAccessSummary.CONTROLLING,
            RadarAccessSummary.of(
                listOf(
                    grant("a", read = true),
                    grant("b", read = true),
                    grant("c", read = true, control = true),
                ),
            ),
        )
    }

    @Test
    fun aGrantAllowingNeitherReadsAsNothing() {
        assertEquals(RadarAccessSummary.NONE, RadarAccessSummary.of(listOf(grant("a"))))
    }
}
