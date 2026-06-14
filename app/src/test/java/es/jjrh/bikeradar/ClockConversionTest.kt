// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the monotonic->wall conversion used to persist the radar off-instant as a
 * ride-history end time. The off-instant is monotonic (jump-resilient); ride
 * history renders it as a wall-clock Date, so a sign/order slip here would write
 * a 1970-relative date on every ride.
 */
class ClockConversionTest {

    @Test fun pastMonotonicInstantMapsToTheSameWallOffset() {
        // off happened 30 s ago in monotonic terms -> 30 s before now in wall terms.
        val nowWall = 1_700_000_000_000L
        val nowMono = 5_000_000L
        val offMono = nowMono - 30_000L
        assertEquals(nowWall - 30_000L, ClockConversion.monotonicToWallMs(offMono, nowMono, nowWall))
    }

    @Test fun theNowInstantMapsToNowWall() {
        assertEquals(1_700L, ClockConversion.monotonicToWallMs(42L, 42L, 1_700L))
    }
}
