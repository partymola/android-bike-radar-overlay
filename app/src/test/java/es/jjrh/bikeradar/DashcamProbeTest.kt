// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DashcamProbe.shouldLaunch] at its edges, on literal values. */
class DashcamProbeTest {

    private fun launches(
        radarGattActive: Boolean = false,
        radarOffSinceMs: Long? = 1_000_000L,
        nowMs: Long = 1_010_000L,
        dashcamAgeMs: Long = 20_000L,
        lastProbeMs: Long? = null,
        failures: Int = 0,
    ) = DashcamProbe.shouldLaunch(radarGattActive, radarOffSinceMs, nowMs, dashcamAgeMs, lastProbeMs, failures)

    @Test
    fun aCameraReadTwentySecondsAgoIsReadAgain() {
        assertTrue(launches(dashcamAgeMs = 20_000L))
        assertFalse(launches(dashcamAgeMs = 19_999L))
    }

    @Test
    fun aCameraNeverReadIsRead() {
        assertTrue(launches(dashcamAgeMs = Long.MAX_VALUE))
    }

    @Test
    fun nothingIsReadWithTheIdleGateClosed() {
        assertFalse(launches(radarOffSinceMs = null))
        assertFalse(launches(radarOffSinceMs = 1_000_000L, nowMs = 1_900_000L))
        assertTrue(launches(radarOffSinceMs = 1_000_000L, nowMs = 1_899_999L))
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
}
