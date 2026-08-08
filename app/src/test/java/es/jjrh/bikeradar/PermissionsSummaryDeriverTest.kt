// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionsSummaryDeriverTest {

    @Test fun aMissingRequiredPermissionAlwaysNeedsAction() {
        assertEquals(
            PermissionsSummary.ACTION_NEEDED,
            PermissionsSummaryDeriver.derive(grantedCount = 3, requiredMissing = 1, total = 4),
        )
    }

    @Test fun requiredGrantedWithOptionalsOutstandingIsNotAllGranted() {
        // The shipped defect: two of four granted, both of them the required
        // ones, rendered as "All granted (2 of 4)".
        assertEquals(
            PermissionsSummary.PARTIALLY_GRANTED,
            PermissionsSummaryDeriver.derive(grantedCount = 2, requiredMissing = 0, total = 4),
        )
    }

    @Test fun everythingGrantedIsAllGranted() {
        assertEquals(
            PermissionsSummary.ALL_GRANTED,
            PermissionsSummaryDeriver.derive(grantedCount = 4, requiredMissing = 0, total = 4),
        )
    }
}
