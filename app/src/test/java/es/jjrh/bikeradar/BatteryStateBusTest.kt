// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryStateBusTest {

    @After fun tearDown() {
        // Keep the singleton empty between tests.
        BatteryStateBus.entries.value.keys.toList().forEach {
            // No public clear; overwrite with a sentinel then rely on markSeen
            // not creating entries. Safest: reassign via update() of the same
            // entry then ignore. For this test suite we only rely on the slugs
            // we explicitly touch, so full reset isn't required.
        }
    }

    @Test fun markSeenBumpsReadAtMsOnExistingEntry() {
        BatteryStateBus.update(BatteryEntry("dashcam1", "Vue", 80, readAtMs = 1_000L))
        BatteryStateBus.markSeen("dashcam1", nowMs = 60_000L)
        val e = BatteryStateBus.entries.value["dashcam1"]
        assertEquals(60_000L, e?.readAtMs)
        assertEquals(80, e?.pct)
    }

    @Test fun markSeenNoOpOnUnknownSlug() {
        BatteryStateBus.markSeen("nonexistent_slug_xyz", nowMs = 60_000L)
        assertNull(BatteryStateBus.entries.value["nonexistent_slug_xyz"])
    }

    @Test fun markSeenPreservesNameAndPct() {
        BatteryStateBus.update(BatteryEntry("dashcam2", "My Vue", 42, readAtMs = 500L))
        BatteryStateBus.markSeen("dashcam2", nowMs = 99_999L)
        val e = BatteryStateBus.entries.value["dashcam2"]
        assertEquals("My Vue", e?.name)
        assertEquals(42, e?.pct)
        assertEquals(99_999L, e?.readAtMs)
    }
}
