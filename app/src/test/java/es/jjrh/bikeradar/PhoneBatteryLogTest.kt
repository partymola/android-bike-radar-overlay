// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the phone-battery capture-log line shape: comment-prefixed
 * (so existing decoders skip it) and structured key=value pairs that
 * survive a `grep "# phone"` post-ride.
 */
class PhoneBatteryLogTest {

    @Test fun normalReading() {
        val line = BikeRadarService.formatPhoneBatteryLog(
            unixMs = 1709123456789L,
            level = 87,
            scale = 100,
            tempDc = 285,
            plugged = 0,
        )
        assertEquals("# phone t=1709123456789 level=87 temp_dc=285 charging=false", line)
    }

    @Test fun chargingTrue() {
        val line = BikeRadarService.formatPhoneBatteryLog(
            unixMs = 1L,
            level = 50,
            scale = 100,
            tempDc = 250,
            plugged = 2,
        )
        assertEquals("# phone t=1 level=50 temp_dc=250 charging=true", line)
    }

    @Test fun nonStandardScale() {
        // Some manufacturers report scale=200 for half-percent resolution.
        val line = BikeRadarService.formatPhoneBatteryLog(
            unixMs = 1L,
            level = 100,
            scale = 200,
            tempDc = 280,
            plugged = 0,
        )
        assertEquals("# phone t=1 level=50 temp_dc=280 charging=false", line)
    }

    @Test fun unreadLevelEmitsMinusOne() {
        val line = BikeRadarService.formatPhoneBatteryLog(
            unixMs = 1L,
            level = -1,
            scale = 100,
            tempDc = 280,
            plugged = 0,
        )
        assertEquals("# phone t=1 level=-1 temp_dc=280 charging=false", line)
    }

    @Test fun zeroScaleEmitsMinusOne() {
        val line = BikeRadarService.formatPhoneBatteryLog(
            unixMs = 1L,
            level = 50,
            scale = 0,
            tempDc = 280,
            plugged = 0,
        )
        assertEquals("# phone t=1 level=-1 temp_dc=280 charging=false", line)
    }

    @Test fun missingTemperaturePropagated() {
        // EXTRA_TEMPERATURE absent → caller passes Int.MIN_VALUE; line still
        // emits so cross-reference timestamps don't drop frames.
        val line = BikeRadarService.formatPhoneBatteryLog(
            unixMs = 1L,
            level = 50,
            scale = 100,
            tempDc = Int.MIN_VALUE,
            plugged = 0,
        )
        assertEquals("# phone t=1 level=50 temp_dc=${Int.MIN_VALUE} charging=false", line)
    }
}
