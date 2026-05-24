// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Covers the [DashcamStatus] sealed hierarchy's own members. The deriver
 * tests exercise which variant is returned; this pins the data-object
 * identity and string forms the variants expose (used in logging and the
 * overlay's warning-slot diagnostics).
 */
class DashcamStatusTest {

    @Test fun dataObjectsHaveStableToString() {
        // data object toString is the simple class name; logging and the
        // capture log lean on these being human-readable.
        assertEquals("Ok", DashcamStatus.Ok.toString())
        assertEquals("Searching", DashcamStatus.Searching.toString())
        assertEquals("Missing", DashcamStatus.Missing.toString())
        assertEquals("Dropped", DashcamStatus.Dropped.toString())
    }

    @Test fun variantsAreDistinctSingletons() {
        // Each is a singleton; the four must be mutually unequal so a
        // `when (status)` over the sealed interface can't conflate two.
        val all = listOf(
            DashcamStatus.Ok,
            DashcamStatus.Searching,
            DashcamStatus.Missing,
            DashcamStatus.Dropped,
        )
        assertEquals("variants must be unique", all.size, all.toSet().size)
        assertNotEquals(DashcamStatus.Ok, DashcamStatus.Dropped)
    }
}
