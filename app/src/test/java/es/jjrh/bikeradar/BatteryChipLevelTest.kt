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

    @Test fun theMarkerAndTheChipAgreeAtEveryPercentage() {
        // The overlay's low-battery marker and the chip's colour band are one
        // question. A chip that is not NORMAL without the marker showing (or
        // the reverse) is the disagreement this pairing exists to prevent.
        for (threshold in listOf(0, 1, 10, 20, 30, 99, 100)) {
            for (pct in 0..100) {
                assertEquals(
                    "pct=$pct threshold=$threshold",
                    batteryIsLow(pct, threshold),
                    batteryChipLevel(pct, threshold) != BatteryChipLevel.NORMAL,
                )
            }
        }
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
