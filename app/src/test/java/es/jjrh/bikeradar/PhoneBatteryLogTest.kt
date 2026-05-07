// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ── shouldLogPhoneBattery throttle ──────────────────────────────────

    @Test fun throttle_logsOnFirstSampleSinceSentinel() {
        // Initial state: lastLevelPct=-1 sentinel; first reading differs,
        // so we log even though 0 ms has elapsed since lastLogMs=0.
        assertTrue(BikeRadarService.shouldLogPhoneBattery(
            now = 0L, lastLogMs = 0L, lastLevelPct = -1, currentLevelPct = 87,
            periodMs = 60_000L,
        ))
    }

    @Test fun throttle_skipsWhenLevelSameAndPeriodNotElapsed() {
        // Same level at 30 s after last log: heartbeat hasn't fired.
        assertFalse(BikeRadarService.shouldLogPhoneBattery(
            now = 30_000L, lastLogMs = 0L, lastLevelPct = 87, currentLevelPct = 87,
            periodMs = 60_000L,
        ))
    }

    @Test fun throttle_logsAtHeartbeatBoundary() {
        // Same level, exactly periodMs elapsed: heartbeat fires (>=).
        assertTrue(BikeRadarService.shouldLogPhoneBattery(
            now = 60_000L, lastLogMs = 0L, lastLevelPct = 87, currentLevelPct = 87,
            periodMs = 60_000L,
        ))
    }

    @Test fun throttle_logsOnLevelChangeBeforeHeartbeat() {
        // Level dropped 1% before heartbeat would fire: log immediately.
        assertTrue(BikeRadarService.shouldLogPhoneBattery(
            now = 5_000L, lastLogMs = 0L, lastLevelPct = 87, currentLevelPct = 86,
            periodMs = 60_000L,
        ))
    }

    @Test fun throttle_logsOnLevelChangeEvenJustAfterPriorLog() {
        // Edge: same instant a heartbeat fired, the next sample sees a
        // level change; should still log without waiting for next period.
        assertTrue(BikeRadarService.shouldLogPhoneBattery(
            now = 100L, lastLogMs = 100L, lastLevelPct = 87, currentLevelPct = 86,
            periodMs = 60_000L,
        ))
    }
}
