// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import es.jjrh.bikeradar.RadarLeftOnDecider.Drop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLeftOnDeciderTest {

    // ── the gap ──────────────────────────────────────────────────────────────

    private fun gap(ms: Long, status: Int) = RadarLeftOnDecider.classify(LinkLoss(ms, status))

    @Test
    fun theRadarEndingItsLinkPromptlyIsASwitchOff() {
        assertEquals(Drop.SWITCHED_OFF, gap(0L, status = 19))
        assertEquals(Drop.SWITCHED_OFF, gap(999L, status = 257))
        assertEquals(Drop.UNCLEAR, gap(1_000L, status = 19))
        assertEquals("the radar ended a link that was already silent", Drop.UNCLEAR, gap(3_500L, status = 257))
    }

    @Test
    fun aTimeoutTwoSecondsAfterTheLastFrameIsAWalkAway() {
        assertEquals(Drop.WALKED_AWAY, gap(2_000L, status = 147))
        assertEquals(Drop.WALKED_AWAY, gap(3_990L, status = 8))
        assertEquals(Drop.WALKED_AWAY, gap(3_500L, status = 34))
        assertEquals(Drop.UNCLEAR, gap(1_999L, status = 147))
        assertEquals("a timeout on a link still streaming", Drop.UNCLEAR, gap(300L, status = 8))
    }

    @Test
    fun noGapIsNoDrop() {
        assertNull(RadarLeftOnDecider.classify(null))
    }

    @Test
    fun anyOtherStatusIsNoDrop() {
        // Bluetooth switched off, the bond removed, a stack error: neither a
        // switch-off to learn from nor a walk-away, whatever the gap.
        for (status in listOf(0, 22, 59, 133)) {
            assertNull("status $status", gap(300L, status))
            assertNull("status $status", gap(3_500L, status))
        }
    }

    // ── calibration ──────────────────────────────────────────────────────────

    @Test
    fun aCleanSwitchOffCountsAndAnUnclearEndingStartsAgain() {
        assertEquals(1, RadarLeftOnDecider.switchOffsAfter(Drop.SWITCHED_OFF, 0))
        assertEquals(0, RadarLeftOnDecider.switchOffsAfter(Drop.UNCLEAR, 2))
        assertEquals(0, RadarLeftOnDecider.switchOffsAfter(Drop.UNCLEAR, 3))
        assertEquals(3, RadarLeftOnDecider.switchOffsAfter(Drop.WALKED_AWAY, 3))
        assertEquals(2, RadarLeftOnDecider.switchOffsAfter(null, 2))
    }

    @Test
    fun theCountStopsAtThree() {
        assertEquals(3, RadarLeftOnDecider.switchOffsAfter(Drop.SWITCHED_OFF, 2))
        assertEquals(3, RadarLeftOnDecider.switchOffsAfter(Drop.SWITCHED_OFF, 3))
    }

    // ── what qualifies a drop ────────────────────────────────────────────────

    private fun qualifies(
        drop: Drop? = Drop.WALKED_AWAY,
        cleanSwitchOffs: Int = 3,
        armed: Boolean = true,
        ridingAtDrop: Boolean = false,
        radarBatteryPct: Int? = 60,
        streamedMs: Long = 600_000L,
        dismissedUntilRide: Boolean = false,
    ) = RadarLeftOnDecider.qualifies(drop, cleanSwitchOffs, armed, ridingAtDrop, radarBatteryPct, streamedMs, dismissedUntilRide)

    @Test
    fun aWalkAwayFromACalibratedRadarQualifies() {
        assertTrue(qualifies())
    }

    @Test
    fun onlyAWalkAwayQualifies() {
        assertFalse(qualifies(drop = Drop.SWITCHED_OFF))
        assertFalse(qualifies(drop = Drop.UNCLEAR))
        assertFalse(qualifies(drop = null))
    }

    @Test
    fun aRadarNotYetCalibratedStaysSilent() {
        assertFalse(qualifies(cleanSwitchOffs = 2))
        assertTrue(qualifies(cleanSwitchOffs = 3))
    }

    @Test
    fun theArmingGateAndRidingBothVeto() {
        assertFalse("bike freshly unlocked at the drop", qualifies(armed = false))
        assertFalse("riding just before the drop", qualifies(ridingAtDrop = true))
    }

    @Test
    fun aLowRadarBatteryVetoesButAnUnknownOneDoesNot() {
        assertFalse(qualifies(radarBatteryPct = 9))
        assertTrue(qualifies(radarBatteryPct = 10))
        assertTrue(qualifies(radarBatteryPct = null))
    }

    @Test
    fun aLinkThatStreamedUnderFifteenSecondsDoesNotQualify() {
        assertFalse(qualifies(streamedMs = 14_999L))
        assertTrue(qualifies(streamedMs = 15_000L))
    }

    @Test
    fun aDismissHoldsUntilTheNextRide() {
        assertFalse(qualifies(dismissedUntilRide = true))
    }

    // ── when it sounds ───────────────────────────────────────────────────────

    private fun fires(
        enabled: Boolean = true,
        qualified: Boolean = true,
        nowMs: Long = 120_000L,
        offSinceMs: Long? = 100_000L,
        adapterOff: Boolean = false,
        fired: Boolean = false,
        dismissed: Boolean = false,
        otherAlarmFired: Boolean = false,
    ) = RadarLeftOnDecider.shouldFire(enabled, qualified, nowMs, offSinceMs, adapterOff, fired, dismissed, otherAlarmFired)

    @Test
    fun itSoundsTwentySecondsAfterAQualifyingDrop() {
        assertFalse(fires(nowMs = 119_999L))
        assertTrue(fires(nowMs = 120_000L))
    }

    @Test
    fun itSoundsOncePerEpisode() {
        assertFalse(fires(fired = true))
        assertFalse(fires(dismissed = true))
        assertFalse("the dashcam alarm already owns this episode", fires(otherAlarmFired = true))
    }

    @Test
    fun nothingSoundsWhenSwitchedOffUnqualifiedOrWithBluetoothGone() {
        assertFalse(fires(enabled = false))
        assertFalse(fires(qualified = false))
        assertFalse(fires(adapterOff = true))
        assertFalse("the radar is back", fires(offSinceMs = null))
    }
}
