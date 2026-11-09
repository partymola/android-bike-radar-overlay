// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleGateTest {

    @Test
    fun radarConnectedReturnsTrue() {
        assertTrue(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = true,
                radarOffSinceMs = null,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun radarConnectedIgnoresOffStamp() {
        // radarGattActive should win regardless of a stale off-stamp.
        assertTrue(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = true,
                radarOffSinceMs = 0L,
                nowMs = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun neverConnectedReturnsFalse() {
        assertFalse(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = null,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun justDisconnectedReturnsTrue() {
        assertTrue(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 1_000L,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun withinWindowReturnsTrue() {
        assertTrue(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 1_000L,
                nowMs = 1_000L + 599_999L,
                windowMs = 600_000L,
            ),
        )
    }

    @Test
    fun atBoundaryReturnsFalse() {
        // strict < semantics
        assertFalse(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 1_000L,
                nowMs = 1_000L + 600_000L,
                windowMs = 600_000L,
            ),
        )
    }

    @Test
    fun pastWindowReturnsFalse() {
        assertFalse(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 1_000L,
                nowMs = 1_000L + 600_001L,
                windowMs = 600_000L,
            ),
        )
    }

    @Test
    fun clockSkewNegativeDeltaReturnsTrue() {
        // The production caller now feeds the monotonic clock, which never
        // steps backward, so a negative delta should not arise from an NTP
        // correction. The decider still treats any negative delta as < windowMs
        // and keeps refreshing - retained as a safe invariant of the pure
        // function rather than a path expected in production.
        assertTrue(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 2_000L,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun customWindowHonoured() {
        assertFalse(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 1_000L,
                nowMs = 1_500L,
                windowMs = 400L,
            ),
        )
    }

    @Test
    fun customWindowZeroAlwaysFalseWhenDisconnected() {
        assertFalse(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 1_000L,
                nowMs = 1_000L,
                windowMs = 0L,
            ),
        )
    }

    @Test
    fun dashcamWindowLetsTheLongestThresholdSoundTwice() {
        // A camera only the probe hears stops being heard soon after the
        // window closes, which ends its walk-away alarm. At the Settings
        // slider's longest threshold (120 s) it still sounds at the threshold
        // and again 5 min later.
        assertTrue(IdleGate.DASHCAM_POST_DISCONNECT_WINDOW_MS >= 120_000L + 300_000L)
        assertEquals(900_000L, IdleGate.DASHCAM_POST_DISCONNECT_WINDOW_MS)
    }
}
