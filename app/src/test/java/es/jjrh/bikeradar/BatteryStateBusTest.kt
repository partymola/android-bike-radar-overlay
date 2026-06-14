// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BatteryStateBusTest {

    @Before fun reset() {
        BatteryStateBus.clearForTest()
    }

    @After fun tearDown() {
        // BatteryStateBus is a process-wide singleton; empty it so entries
        // don't leak into the next test.
        BatteryStateBus.clearForTest()
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

    @Test fun markSeenStampsLastSeenElapsedMs() {
        BatteryStateBus.update(
            BatteryEntry("dashcam3", "Vue", 70, readAtMs = 1_000L, lastSeenElapsedMs = 2_000L),
        )
        BatteryStateBus.markSeen("dashcam3", nowMs = 60_000L, elapsedMs = 65_000L)
        val e = BatteryStateBus.entries.value["dashcam3"]
        // markSeen must refresh the monotonic freshness clock the walk-away
        // alarm reads, not just the wall readAtMs - else the alarm silently
        // stops firing between full GATT reads.
        assertEquals(65_000L, e?.lastSeenElapsedMs)
        assertEquals(60_000L, e?.readAtMs)
    }
}
