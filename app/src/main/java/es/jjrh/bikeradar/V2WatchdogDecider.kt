// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure staleness decision for the rear-radar V2 data-flow watchdog.
 *
 * Why it exists: when the radar BLE link silently dies the GATT stack can still
 * believe it is connected, so no frame arrives but no disconnect callback fires
 * either - a dead radar then reads identically to a clear road. The watchdog
 * forces a teardown once frames stop, so the reconnect loop re-establishes the
 * link (and the radar-drop audio cue can fire).
 *
 * [nowMs] and [lastFrameMs] are taken on the MONOTONIC clock
 * (`SystemClock.elapsedRealtime()`, injected at the call site), never the wall
 * clock: a backward NTP/DST step on `currentTimeMillis()` would shrink
 * `now - lastFrame` and let a silently-dead radar look alive. elapsedRealtime
 * advances across deep sleep and never jumps backward.
 *
 * [lastFrameMs] == 0 means "no frame yet this connection": the watchdog gives
 * the first frame a fair chance to arrive and never tears down before one lands.
 * The boundary is strictly-greater, matching the prior inline `ageMs > stall`.
 */
object V2WatchdogDecider {
    fun isStale(nowMs: Long, lastFrameMs: Long, stallMs: Long): Boolean = lastFrameMs != 0L && nowMs - lastFrameMs > stallMs
}
