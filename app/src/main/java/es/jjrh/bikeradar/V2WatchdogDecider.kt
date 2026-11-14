// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
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
 * The caller stamps [lastFrameMs] when its decode loop starts, so a radar that
 * completes the handshake and never streams is still torn down
 * (`RadarLinkControllerHarnessTest.watchdogTearsDownSilentV2Stream`). A 0 stamp
 * reads as fresh: production never passes one, since elapsedRealtime is past
 * zero by then, and a harness clock fixed at 0 relies on it to keep the
 * watchdog off. The boundary is strictly-greater.
 */
object V2WatchdogDecider {
    fun isStale(nowMs: Long, lastFrameMs: Long, stallMs: Long): Boolean = lastFrameMs != 0L && nowMs - lastFrameMs > stallMs
}
