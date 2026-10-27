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

    private fun veto(
        drop: Drop? = Drop.WALKED_AWAY,
        cleanSwitchOffs: Int = 3,
        enabled: Boolean = true,
        armed: Boolean = true,
        ridingAtDrop: Boolean = false,
        radarBatteryPct: Int? = 60,
        streamedMs: Long = 600_000L,
        dismissedUntilRide: Boolean = false,
    ) = RadarLeftOnDecider.veto(drop, cleanSwitchOffs, enabled, armed, ridingAtDrop, radarBatteryPct, streamedMs, dismissedUntilRide)

    private fun qualifies(
        drop: Drop? = Drop.WALKED_AWAY,
        cleanSwitchOffs: Int = 3,
        enabled: Boolean = true,
        armed: Boolean = true,
        ridingAtDrop: Boolean = false,
        radarBatteryPct: Int? = 60,
        streamedMs: Long = 600_000L,
        dismissedUntilRide: Boolean = false,
    ) = veto(drop, cleanSwitchOffs, enabled, armed, ridingAtDrop, radarBatteryPct, streamedMs, dismissedUntilRide) == null

    @Test
    fun aWalkAwayFromACalibratedRadarQualifies() {
        assertTrue(qualifies())
    }

    @Test
    fun theSwitchIsReadAtTheDrop() {
        assertFalse(qualifies(enabled = false))
    }

    @Test
    fun theFirstGateThatFailsIsNamed() {
        assertEquals("not-walked-away", veto(drop = Drop.SWITCHED_OFF, cleanSwitchOffs = 0))
        assertEquals("calibrating", veto(cleanSwitchOffs = 2, enabled = false))
        assertEquals("switched-off", veto(enabled = false, armed = false))
        assertEquals("ebike-unlocked", veto(armed = false, ridingAtDrop = true))
        assertEquals("riding", veto(ridingAtDrop = true, radarBatteryPct = 5))
        assertEquals("battery-low", veto(radarBatteryPct = 9, streamedMs = 1L))
        assertEquals("short-stream", veto(streamedMs = 14_999L, dismissedUntilRide = true))
        assertEquals("dismissed-until-ride", veto(dismissedUntilRide = true))
        assertNull(veto())
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
        linkOpen: Boolean = false,
        linkClosedAtMs: Long? = 100_000L,
        adapterOff: Boolean = false,
        fired: Boolean = false,
        dismissed: Boolean = false,
        otherAlarmFired: Boolean = false,
    ) = RadarLeftOnDecider.shouldFire(
        enabled,
        qualified,
        nowMs,
        offSinceMs,
        linkOpen,
        linkClosedAtMs,
        adapterOff,
        fired,
        dismissed,
        otherAlarmFired,
    )

    @Test
    fun itSoundsTwentySecondsAfterAQualifyingDrop() {
        assertFalse(fires(nowMs = 119_999L))
        assertTrue(fires(nowMs = 120_000L))
    }

    @Test
    fun anOpenLinkHoldsIt() {
        assertFalse(fires(nowMs = 219_999L, linkOpen = true))
    }

    @Test
    fun aLinkClosedAfterTheDropRestartsTheWait() {
        assertFalse(fires(nowMs = 129_999L, linkClosedAtMs = 110_000L))
        assertTrue(fires(nowMs = 130_000L, linkClosedAtMs = 110_000L))
    }

    @Test
    fun linksHoldItForTwoMinutesAfterTheDropAtMost() {
        assertTrue(fires(nowMs = 220_000L, linkOpen = true))
        assertFalse(fires(nowMs = 219_999L, linkClosedAtMs = 210_000L))
        assertTrue(fires(nowMs = 220_000L, linkClosedAtMs = 210_000L))
    }

    @Test
    fun theCapDoesNotGetPastTheOtherGates() {
        assertFalse(fires(nowMs = 220_000L, linkOpen = true, enabled = false))
        assertFalse(fires(nowMs = 220_000L, linkOpen = true, qualified = false))
        assertFalse(fires(nowMs = 220_000L, linkOpen = true, adapterOff = true))
        assertFalse(fires(nowMs = 220_000L, linkOpen = true, fired = true))
        assertFalse(fires(nowMs = 220_000L, linkOpen = true, dismissed = true))
        assertFalse(fires(nowMs = 220_000L, linkOpen = true, otherAlarmFired = true))
    }

    @Test
    fun aLinkClosedBeforeTheDropLeavesTheWaitAtTheDrop() {
        assertFalse(fires(nowMs = 119_999L, linkClosedAtMs = 50_000L))
        assertTrue(fires(nowMs = 120_000L, linkClosedAtMs = 50_000L))
        assertTrue(fires(nowMs = 120_000L, linkClosedAtMs = null))
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
