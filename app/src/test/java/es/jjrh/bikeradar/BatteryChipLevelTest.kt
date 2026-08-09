// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryChipLevelTest {

    @Test fun theThresholdItselfCountsAsLow() {
        // A threshold of 20 means "warn me at 20 percent", not "below 20".
        assertTrue(batteryIsLow(pct = 20, lowThresholdPct = 20))
        assertFalse(batteryIsLow(pct = 21, lowThresholdPct = 20))
        assertTrue(batteryIsLow(pct = 30, lowThresholdPct = 30))
        assertFalse(batteryIsLow(pct = 31, lowThresholdPct = 30))
    }

    @Test fun theCriticalBandNeverEscapesTheLowBand() {
        // What this actually pins, stated honestly: since the LOW arm calls
        // batteryIsLow, the shared term cancels and the only property left is
        // that CRITICAL is a subset of LOW. Nothing else pins that, and
        // widening the critical cut past the low one breaks it.
        for (threshold in listOf(0, 1, 10, 20, 30, 99, 100)) {
            for (pct in 0..100) {
                if (batteryChipLevel(pct, threshold) == BatteryChipLevel.CRITICAL) {
                    assertTrue(
                        "pct=$pct threshold=$threshold critical but not low",
                        batteryIsLow(pct, threshold),
                    )
                }
            }
        }
    }

    @Test fun theOverlayMarksADeviceSittingExactlyOnTheThreshold() {
        // The call site the chip/marker disagreement actually lived at. The
        // chip banded at or below the threshold while this filter took
        // strictly below, so a device sitting on it got an amber chip and no
        // overlay marker.
        val onTheThreshold = BatteryEntry("radar", "RearVue8", 20, readAtMs = 1_000L)
        assertEquals(
            setOf("radar"),
            lowBatterySlugs(listOf(onTheThreshold), lowThresholdPct = 20, nowMs = 1_000L, staleAfterMs = 30_000L),
        )
        val justAbove = BatteryEntry("radar", "RearVue8", 21, readAtMs = 1_000L)
        assertEquals(
            emptySet<String>(),
            lowBatterySlugs(listOf(justAbove), lowThresholdPct = 20, nowMs = 1_000L, staleAfterMs = 30_000L),
        )
    }

    @Test fun aReadingTooOldToDescribeTheDeviceIsNotMarked() {
        val entry = BatteryEntry("radar", "RearVue8", 5, readAtMs = 1_000L)
        assertEquals(
            "a reading inside the window still marks",
            setOf("radar"),
            lowBatterySlugs(listOf(entry), lowThresholdPct = 20, nowMs = 30_999L, staleAfterMs = 30_000L),
        )
        assertEquals(
            "a reading exactly at the window edge is too old",
            emptySet<String>(),
            lowBatterySlugs(listOf(entry), lowThresholdPct = 20, nowMs = 31_000L, staleAfterMs = 30_000L),
        )
    }

    @Test fun theDefaultThresholdKeepsTheOriginalBands() {
        assertEquals(BatteryChipLevel.CRITICAL, batteryChipLevel(pct = 10, lowThresholdPct = 20))
        assertEquals(BatteryChipLevel.LOW, batteryChipLevel(pct = 11, lowThresholdPct = 20))
        assertEquals(BatteryChipLevel.LOW, batteryChipLevel(pct = 20, lowThresholdPct = 20))
        assertEquals(BatteryChipLevel.NORMAL, batteryChipLevel(pct = 21, lowThresholdPct = 20))
    }

    @Test fun aRaisedThresholdMovesBothBands() {
        // The divergence that shipped: at a threshold of 30 the in-ride cue
        // fired at 30% while the chip stayed neutral until 20%.
        assertEquals(BatteryChipLevel.LOW, batteryChipLevel(pct = 30, lowThresholdPct = 30))
        assertEquals(BatteryChipLevel.CRITICAL, batteryChipLevel(pct = 15, lowThresholdPct = 30))
        assertEquals(BatteryChipLevel.NORMAL, batteryChipLevel(pct = 31, lowThresholdPct = 30))
    }

    @Test fun aLoweredThresholdNarrowsBothBands() {
        assertEquals(BatteryChipLevel.NORMAL, batteryChipLevel(pct = 11, lowThresholdPct = 10))
        assertEquals(BatteryChipLevel.LOW, batteryChipLevel(pct = 10, lowThresholdPct = 10))
        assertEquals(BatteryChipLevel.CRITICAL, batteryChipLevel(pct = 5, lowThresholdPct = 10))
    }
}
