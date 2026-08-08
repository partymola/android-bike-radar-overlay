// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window that stops a stored battery read being shown as a live one.
 *
 * BatteryStateBus is never cleared in production, so an entry read once at the
 * start of a ride is still there hours later. Settings rendered any present
 * entry as "Connected" with a green dot, disagreeing with the radar and dashcam
 * sub-screens, which applied this same window to the same datum one tap away.
 */
class BatteryFreshnessTest {

    @Test fun aReadingInsideTheWindowIsFresh() {
        assertTrue(batteryReadIsFresh(readAtMs = 1_000L, nowMs = 1_000L))
        assertTrue(batteryReadIsFresh(readAtMs = 1_000L, nowMs = 1_000L + 29_999L))
    }

    @Test fun theWindowIsExclusiveAtItsEdge() {
        assertFalse(batteryReadIsFresh(readAtMs = 1_000L, nowMs = 1_000L + 30_000L))
    }

    @Test fun anHourOldReadingIsNotFresh() {
        // The case that shipped: one read at the start of a ride, still being
        // rendered as a live connection long after the device was switched off.
        assertFalse(batteryReadIsFresh(readAtMs = 0L, nowMs = 3_600_000L))
    }

    @Test fun aClockJumpBackwardsDoesNotMakeAReadingStale() {
        // Wall clock, so it can move backwards. A negative age is not staleness.
        assertTrue(batteryReadIsFresh(readAtMs = 10_000L, nowMs = 5_000L))
    }
}
