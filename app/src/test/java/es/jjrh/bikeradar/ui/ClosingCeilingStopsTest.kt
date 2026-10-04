// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClosingCeilingStopsTest {

    @Test fun `the slider offers 35 to 50 m s in fives, then no limit last`() {
        assertEquals(listOf(35, 40, 45, 50, null), closingCeilingStops)
    }

    @Test fun `each stored value lands on its own stop`() {
        assertEquals(0, closingCeilingStopIndex(35))
        assertEquals(1, closingCeilingStopIndex(40))
        assertEquals(3, closingCeilingStopIndex(50))
        assertEquals(4, closingCeilingStopIndex(null))
    }

    @Test fun `a value off the ladder lands on the nearest stop`() {
        // A backup restored from a build with a different ladder.
        assertEquals(0, closingCeilingStopIndex(37))
        assertEquals(2, closingCeilingStopIndex(44))
    }
}
