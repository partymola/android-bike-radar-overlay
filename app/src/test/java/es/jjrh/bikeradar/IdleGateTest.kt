// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

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
            )
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
            )
        )
    }

    @Test
    fun neverConnectedReturnsFalse() {
        assertFalse(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = null,
                nowMs = 1_000L,
            )
        )
    }

    @Test
    fun justDisconnectedReturnsTrue() {
        assertTrue(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 1_000L,
                nowMs = 1_000L,
            )
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
            )
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
            )
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
            )
        )
    }

    @Test
    fun clockSkewNegativeDeltaReturnsTrue() {
        // If the wall clock steps backward (NTP correction), nowMs -
        // offMs can go negative. Negative is < windowMs so we keep
        // refreshing — safer than going dark on a transient skew.
        assertTrue(
            IdleGate.shouldRefreshDashcam(
                radarGattActive = false,
                radarOffSinceMs = 2_000L,
                nowMs = 1_000L,
            )
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
            )
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
            )
        )
    }

    @Test
    fun dashcamWindowCoversWalkAwayLifetime() {
        // Walk-away threshold is capped at 120 s by the Settings slider
        // (Prefs.kt). After the alarm fires, WalkAwayDecider keeps the
        // notification live for autoDismissAfterFireMs (default 10 min)
        // before auto-dismissing. The dashcam ticker must keep running
        // for that full lifetime; if it stops earlier the dashcam
        // entry's readAtMs goes stale (DASHCAM_FRESH_MS = 20 s) and
        // the decider auto-dismisses for the wrong reason ("dashcam
        // went silent" rather than the intended timeout).
        val maxThresholdMs = 120_000L
        // Config has required fields enabled / thresholdMs without defaults;
        // their values are irrelevant — we only read autoDismissAfterFireMs.
        val cfg = WalkAwayDecider.Config(enabled = true, thresholdMs = 0L)
        val coverNeeded = maxThresholdMs + cfg.autoDismissAfterFireMs
        assertTrue(
            "DASHCAM_POST_DISCONNECT_WINDOW_MS=${IdleGate.DASHCAM_POST_DISCONNECT_WINDOW_MS} must " +
                "be >= walk-away cover ($coverNeeded ms = ${coverNeeded / 1000} s)",
            IdleGate.DASHCAM_POST_DISCONNECT_WINDOW_MS >= coverNeeded,
        )
    }
}
