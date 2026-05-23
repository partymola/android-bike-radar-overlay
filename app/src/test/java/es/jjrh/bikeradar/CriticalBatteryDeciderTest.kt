// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class CriticalBatteryDeciderTest {

    private val criticalPct = 10
    private val cadence = 120_000L

    private fun decide(pct: Int?, fresh: Boolean = true, now: Long, last: Long?) =
        CriticalBatteryDecider.decide(pct, fresh, now, criticalPct, cadence, last)

    @Test fun `above threshold does not fire and clears latch`() {
        val d = decide(pct = 15, now = 1_000, last = 500)
        assertEquals(CriticalBatteryDecider.Decision(false, null), d)
    }

    @Test fun `at threshold is not critical (strict less-than)`() {
        // 10 is not < 10, so pct == criticalPct must NOT fire.
        val d = decide(pct = 10, now = 1_000, last = null)
        assertEquals(CriticalBatteryDecider.Decision(false, null), d)
    }

    @Test fun `just below threshold fires on first detection`() {
        val d = decide(pct = 9, now = 1_000, last = null)
        assertEquals(CriticalBatteryDecider.Decision(true, 1_000), d)
    }

    @Test fun `critical within cadence stays silent and keeps latch`() {
        val d = decide(pct = 5, now = 1_000 + 60_000, last = 1_000)
        assertEquals(CriticalBatteryDecider.Decision(false, 1_000), d)
    }

    @Test fun `critical at exactly the cadence boundary re-fires`() {
        val d = decide(pct = 5, now = 1_000 + cadence, last = 1_000)
        assertEquals(CriticalBatteryDecider.Decision(true, 1_000 + cadence), d)
    }

    @Test fun `critical past the cadence re-fires and advances latch`() {
        val d = decide(pct = 3, now = 1_000 + cadence + 5_000, last = 1_000)
        assertEquals(CriticalBatteryDecider.Decision(true, 1_000 + cadence + 5_000), d)
    }

    @Test fun `stale reading does not fire and clears latch`() {
        // Caller computes freshness; a stale radar battery must not cue
        // (and must reset so a fresh critical reading re-fires at once).
        val d = decide(pct = 4, fresh = false, now = 1_000, last = 500)
        assertEquals(CriticalBatteryDecider.Decision(false, null), d)
    }

    @Test fun `null reading does not fire and clears latch`() {
        val d = decide(pct = null, now = 1_000, last = 500)
        assertEquals(CriticalBatteryDecider.Decision(false, null), d)
    }

    @Test fun `recovery then re-drop fires immediately`() {
        // Was critical (latch set), battery recovers above threshold (latch
        // cleared), then drops critical again -> must fire at once, not wait
        // out the old cadence window.
        val recovered = decide(pct = 20, now = 10_000, last = 5_000)
        assertEquals(CriticalBatteryDecider.Decision(false, null), recovered)
        val reDropped = decide(pct = 8, now = 10_500, last = recovered.lastCueMs)
        assertEquals(CriticalBatteryDecider.Decision(true, 10_500), reDropped)
    }
}
