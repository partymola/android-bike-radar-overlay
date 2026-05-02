// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure gate predicate for the dashcam refresh ticker. Extracted so it
 * can be unit-tested without spinning up a Service. The gate stops
 * the ticker from issuing GATT reads when the radar is disconnected
 * AND we are past the walk-away monitoring window. At that point the
 * dashcam liveness signal has no consumer and the reads are wasted.
 */
internal object IdleGate {
    /**
     * Window opens when the radar disconnects (`radarOffSinceMs` set
     * by `markRadarDisconnected`). Long enough to cover the walk-away
     * alarm's full lifetime: threshold (max 120 s per the Settings
     * slider) + auto-dismiss-after-fire (10 min default in
     * `WalkAwayDecider.Config`) + margin. The constants sanity test
     * `IdleGateTest.dashcamWindowCoversWalkAwayLifetime` fails loud
     * if the walk-away cover ever drifts past this window.
     */
    const val DASHCAM_POST_DISCONNECT_WINDOW_MS: Long = 15 * 60 * 1000L

    /**
     * Returns true when the dashcam ticker should issue a battery read
     * on this iteration.
     *
     * - Radar connected: always refresh (UI freshness during the ride).
     * - Radar disconnected within the window: refresh (walk-away
     *   monitoring may still need fresh dashcam state).
     * - Radar never connected (`radarOffSinceMs == null` and not
     *   active): skip. The dashcam is logically attached to the bike;
     *   no radar = no bike-in-range = no consumer for the read.
     */
    fun shouldRefreshDashcam(
        radarGattActive: Boolean,
        radarOffSinceMs: Long?,
        nowMs: Long,
        windowMs: Long = DASHCAM_POST_DISCONNECT_WINDOW_MS,
    ): Boolean = radarGattActive ||
        (radarOffSinceMs != null && nowMs - radarOffSinceMs < windowMs)
}
