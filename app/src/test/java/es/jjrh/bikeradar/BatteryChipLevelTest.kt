// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryChipLevelTest {

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
