// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DashcamProbe.shouldLaunch] at its edges, on literal values. */
class DashcamProbeTest {

    private fun launches(
        radarGattActive: Boolean = false,
        radarOffSinceMs: Long? = 1_000_000L,
        nowMs: Long = 1_010_000L,
        shownAgeMs: Long = 20_000L,
        heardAgeMs: Long = 0L,
        armed: Boolean = false,
        lastProbeMs: Long? = null,
        failures: Int = 0,
        alarmSoundedAtMs: Long? = null,
        awaitingSound: Boolean = false,
    ) = DashcamProbe.shouldLaunch(radarGattActive, radarOffSinceMs, nowMs, shownAgeMs, heardAgeMs, armed, lastProbeMs, failures, alarmSoundedAtMs, awaitingSound)

    @Test
    fun aCameraReadTwentySecondsAgoIsReadAgain() {
        assertTrue(launches(shownAgeMs = 20_000L))
        assertFalse(launches(shownAgeMs = 19_999L))
    }

    @Test
    fun aCameraHeardTwentySecondsAgoIsReadAgainWhateverASightingShows() {
        // A sighting refreshes the shown reading with no contact.
        assertTrue(launches(shownAgeMs = 0L, heardAgeMs = 20_000L))
        assertFalse(launches(shownAgeMs = 0L, heardAgeMs = 19_999L))
    }

    @Test
    fun aCameraNeverReadIsRead() {
        assertTrue(launches(shownAgeMs = Long.MAX_VALUE))
    }

    @Test
    fun nothingIsReadWithTheIdleGateClosedAndNoAlarmSounded() {
        assertFalse(launches(radarOffSinceMs = null))
        assertFalse(launches(radarOffSinceMs = 1_000_000L, nowMs = 1_900_000L))
        assertTrue(launches(radarOffSinceMs = 1_000_000L, nowMs = 1_899_999L))
        assertFalse(launches(radarOffSinceMs = 1_000_000L, nowMs = 1_900_000L, heardAgeMs = 60_000L, armed = true))
    }

    @Test
    fun readsGoOnPastTheIdleGateWhileTheAlarmsToneCanStillBeSounding() {
        // The gate closed at 1 900 000; the tone runs 60 s from the sounding.
        assertTrue(launches(nowMs = 1_949_999L, heardAgeMs = 2_000L, armed = true, alarmSoundedAtMs = 1_890_000L))
        assertFalse(launches(nowMs = 1_950_000L, heardAgeMs = 2_000L, armed = true, alarmSoundedAtMs = 1_890_000L))
        assertFalse(launches(nowMs = 1_949_999L, heardAgeMs = 1_999L, armed = true, alarmSoundedAtMs = 1_890_000L))
    }

    @Test
    fun readsGoOnPastTheIdleGateForARepeatDueWithinThirtyFiveSecondsOfIt() {
        // The gate closed at 1 900 000 and a repeat is due 300 s after the
        // last sounding: 35 s past the close is the latest it can still sound.
        assertTrue(launches(nowMs = 1_900_000L, heardAgeMs = 2_000L, armed = true, alarmSoundedAtMs = 1_635_000L))
        assertFalse(launches(nowMs = 1_900_000L, heardAgeMs = 2_000L, armed = true, alarmSoundedAtMs = 1_635_001L))
        assertTrue(launches(nowMs = 1_900_000L, heardAgeMs = 2_000L, armed = true, alarmSoundedAtMs = 1_600_001L))
        // Due as the gate closes: it sounds on the window's own reads.
        assertFalse(launches(nowMs = 1_900_000L, heardAgeMs = 2_000L, armed = true, alarmSoundedAtMs = 1_600_000L))
        // Only until it is due, at 1 935 000, and two ticks more: a repeat that
        // never sounds does not hold the reads.
        assertTrue(launches(nowMs = 1_939_000L, heardAgeMs = 2_000L, armed = true, alarmSoundedAtMs = 1_635_000L))
        assertFalse(launches(nowMs = 1_939_001L, heardAgeMs = 2_000L, armed = true, alarmSoundedAtMs = 1_635_000L))
        // A failing camera still backs off while the radar's link is open.
        assertFalse(launches(radarGattActive = true, armed = true, heardAgeMs = 2_000L, nowMs = 1_903_999L, lastProbeMs = 1_900_000L, failures = 1, alarmSoundedAtMs = 1_890_000L))
        assertEquals(35_000L, DashcamProbe.REPEAT_AFTER_GATE_MS)
        assertEquals(300_000L, WalkAwayDecider.Config(enabled = true, thresholdMs = 0L).rateLimitMs)
    }

    @Test
    fun readsGoOnPastTheIdleGateWhileASoundingIsAwaited() {
        // The gate closed at 1 900 000.
        assertTrue(launches(nowMs = 1_990_000L, heardAgeMs = 2_000L, armed = true, awaitingSound = true))
        assertFalse(launches(nowMs = 1_990_000L, heardAgeMs = 2_000L, armed = true, awaitingSound = false))
        assertFalse(launches(nowMs = 1_990_000L, heardAgeMs = 1_999L, armed = true, awaitingSound = true))
    }

    @Test
    fun failedReadsBackOffOnlyWhileTheRadarLinkIsOpen() {
        // One failure doubles the 20 s gap to 40 s.
        assertFalse(launches(radarGattActive = true, nowMs = 1_039_999L, lastProbeMs = 1_000_000L, failures = 1))
        assertTrue(launches(radarGattActive = true, nowMs = 1_040_000L, lastProbeMs = 1_000_000L, failures = 1))
        assertTrue(launches(radarGattActive = false, nowMs = 1_001_000L, lastProbeMs = 1_000_000L, failures = 5))
    }

    @Test
    fun withTheRadarLinkOpenAReadIsTwentySecondsAfterTheLastLaunch() {
        assertFalse(launches(radarGattActive = true, nowMs = 1_019_999L, lastProbeMs = 1_000_000L))
        assertTrue(launches(radarGattActive = true, nowMs = 1_020_000L, lastProbeMs = 1_000_000L))
    }

    // ── while the dashcam alarm is armed ────────────────────────────────────

    @Test
    fun anArmedProbeReadsACameraHeardTwoSecondsAgo() {
        assertTrue(launches(armed = true, shownAgeMs = 0L, heardAgeMs = 2_000L))
        assertFalse(launches(armed = true, shownAgeMs = 60_000L, heardAgeMs = 1_999L))
    }

    @Test
    fun anArmedProbeGoesByWhenTheCameraWasHeardNotByTheBattery() {
        // Armed, a shown reading going stale does not hurry the read: a camera
        // whose light link is up is heard now.
        assertFalse(launches(armed = true, shownAgeMs = 60_000L, heardAgeMs = 0L))
        assertTrue(launches(armed = true, shownAgeMs = 0L, heardAgeMs = 2_000L))
    }

    @Test
    fun anArmedProbeWithTheRadarLinkOpenBacksOffFromTwoSeconds() {
        // A failing radar attempt holds the link open during the episode.
        assertFalse(launches(radarGattActive = true, armed = true, heardAgeMs = 2_000L, nowMs = 1_001_999L, lastProbeMs = 1_000_000L))
        assertTrue(launches(radarGattActive = true, armed = true, heardAgeMs = 2_000L, nowMs = 1_002_000L, lastProbeMs = 1_000_000L))
        assertFalse(launches(radarGattActive = true, armed = true, heardAgeMs = 2_000L, nowMs = 1_003_999L, lastProbeMs = 1_000_000L, failures = 1))
        assertTrue(launches(radarGattActive = true, armed = true, heardAgeMs = 2_000L, nowMs = 1_004_000L, lastProbeMs = 1_000_000L, failures = 1))
    }

    @Test
    fun anArmedProbeKeepsACameraThatAnswersInsideTheAlarmsWindow() {
        // The next read starts by ARMED_REFRESH_MS plus one tick after the
        // last answer and ends within the read timeout: under the alarm's
        // 20 s, so a camera that answers never reads stale.
        assertTrue(DashcamProbe.ARMED_REFRESH_MS + BikeRadarService.DASHCAM_TICK_MS + BatteryReader.READ_TIMEOUT_MS < 20_000L)
        assertEquals(20_000L, WalkAwayDecider.Config(enabled = true, thresholdMs = 0L).dashcamFreshMs)
    }

    @Test
    fun theShownCameraStaysFreshForOneWholeProbeCycle() {
        assertEquals(37_000L, BikeRadarService.DASHCAM_FRESH_MS)
        assertEquals(37_000L, RadarLinkCoordinator.RADAR_LEFT_ON_CAMERA_HEARD_MS)
        assertEquals(15_000L, BatteryReader.READ_TIMEOUT_MS)
    }
}
