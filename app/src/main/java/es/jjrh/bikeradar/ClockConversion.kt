// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Convert a monotonic-clock instant (`SystemClock.elapsedRealtime`) to a
 * wall-clock epoch (`System.currentTimeMillis`), for the rare case a monotonic
 * timestamp must be persisted or displayed as an absolute time - e.g. the radar
 * off-instant (kept monotonic so a wall-clock jump can't corrupt the elapsed
 * timers) written into ride history, which stores a wall epoch rendered as a
 * date. The wall time of [monotonicInstantMs] is [nowWallMs] minus the elapsed
 * time since it ([nowMonotonicMs] - [monotonicInstantMs]); the two `now` reads
 * are taken at effectively the same moment, so the residual skew is negligible.
 */
object ClockConversion {
    fun monotonicToWallMs(monotonicInstantMs: Long, nowMonotonicMs: Long, nowWallMs: Long): Long = nowWallMs - (nowMonotonicMs - monotonicInstantMs)
}
