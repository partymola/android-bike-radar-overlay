// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Backoff schedule for the dashcam liveness probe. Pure so the schedule is
 * unit-tested without a Service or a live GATT.
 *
 * Why it exists: when the dashcam (front camera/light) is powered off but still
 * bonded, every liveness probe `connectGatt` burns its full timeout, fails, and
 * - because a failed read never refreshes the cached entry - the age-gated
 * ticker re-fires immediately. The result is a continuous connect storm that
 * contends with the safety-critical radar BLE link. This spaces the
 * probe out as consecutive failures accumulate; the caller resets the failure
 * count on a successful read and at the start of each ride.
 *
 * IMPORTANT: the caller applies this ONLY while the radar is connected. While
 * the radar is disconnected the walk-away alarm consumes the same liveness
 * freshness signal, so the probe must keep running at its base cadence there,
 * never starved by backoff. See `BikeRadarService.launchDashcamRefresh`.
 */
internal object BatteryProbeBackoff {
    /**
     * Minimum gap required before the next probe, given how many consecutive
     * reads have failed. Zero (or negative) failures → [baseMs]; each further
     * failure doubles the gap, clamped to [capMs]. Doubling stops the moment the
     * cap is reached, so the multiply can never overflow for any failure count
     * (including [Int.MAX_VALUE]). If [baseMs] already exceeds [capMs] the cap
     * wins.
     */
    fun minIntervalMs(consecutiveFailures: Int, baseMs: Long, capMs: Long): Long {
        var interval = baseMs.coerceAtMost(capMs)
        var remaining = consecutiveFailures
        while (remaining > 0 && interval < capMs) {
            interval = (interval * 2).coerceAtMost(capMs)
            remaining--
        }
        return interval
    }

    /**
     * True when [nowMs] is at least [minIntervalMs] past [lastAttemptMs]. A null
     * [lastAttemptMs] (never probed) always permits an attempt.
     */
    fun shouldAttempt(
        nowMs: Long,
        lastAttemptMs: Long?,
        consecutiveFailures: Int,
        baseMs: Long,
        capMs: Long,
    ): Boolean {
        if (lastAttemptMs == null) return true
        return nowMs - lastAttemptMs >= minIntervalMs(consecutiveFailures, baseMs, capMs)
    }

    /**
     * Whether the dashcam liveness probe should run on this tick. Backoff
     * applies ONLY while the radar is connected; while it is disconnected the
     * walk-away alarm consumes the same liveness freshness, so the probe must
     * always run (its base age-gated cadence is enforced by the caller). The
     * radar-disconnected bypass is the branch that protects the alarm, so it
     * lives in a pure function that is unit-tested rather than read.
     */
    fun shouldProbe(
        radarConnected: Boolean,
        nowMs: Long,
        lastAttemptMs: Long?,
        consecutiveFailures: Int,
        baseMs: Long,
        capMs: Long,
    ): Boolean {
        if (!radarConnected) return true
        return shouldAttempt(nowMs, lastAttemptMs, consecutiveFailures, baseMs, capMs)
    }
}
