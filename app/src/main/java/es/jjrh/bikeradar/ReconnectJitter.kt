// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.random.Random

/**
 * Apply +/-20% uniform jitter to a reconnect-backoff value. Returns a value in
 * `[baseMs * 0.8, baseMs * 1.2]`. Pass a seeded [Random] for deterministic
 * tests; production callers use [Random.Default].
 *
 * The rear radar, the front camera/light and the eBike status reader all run
 * independent reconnect loops with the same backoff schedule (1 s -> 2 s -> 4 s
 * -> 8 s, capped). Without jitter, a group ride that briefly takes everyone's
 * radios out of range (e.g. a station's RF cage) leaves every rider's three
 * BLE stacks retrying on the same millisecond grid for the rest of the ride.
 * Jitter dephases the retries so two riders riding the same backoff schedule
 * don't keep colliding.
 *
 * Non-positive inputs pass through unchanged - jittering zero or a negative
 * delay would put us in the past.
 */
internal fun jittered(baseMs: Long, random: Random = Random.Default): Long {
    if (baseMs <= 0L) return baseMs
    val jitterRangeMs = (baseMs * 0.2).toLong()
    if (jitterRangeMs <= 0L) return baseMs
    return baseMs + random.nextLong(-jitterRangeMs, jitterRangeMs + 1)
}

// Reconnect backoff: starts fast, doubles on each consecutive failure, caps at
// 8 s. Resets to the initial value once a connection reaches the V2 decode loop.
// Quick-reconnect (post-handshake-ABORT) bypasses backoff entirely. Shared by
// the rear-radar, front-camera, and eBike-status reconnect loops (the "RADAR_"
// prefix is historical - all three run the same schedule).
internal const val RADAR_RECONNECT_BACKOFF_INITIAL_MS = 1_000L
internal const val RADAR_RECONNECT_BACKOFF_MAX_MS = 8_000L
internal const val RADAR_QUICK_RECONNECT_MS = 1_500L

/**
 * The reconnect-backoff ceiling for the current moment. After the device has
 * been offline past [longOfflineThresholdMs] the cap relaxes to
 * [longOfflineCapMs]: at the steady-state 8 s ceiling a parked-overnight bike
 * would otherwise trigger ~10,800 GATT opens per 24 h; the longer cap lets the
 * radio idle while still picking the device up within one cycle of return.
 */
@androidx.annotation.VisibleForTesting
internal fun reconnectBackoffCap(
    now: Long,
    offSinceMs: Long?,
    longOfflineThresholdMs: Long,
    longOfflineCapMs: Long,
): Long {
    if (offSinceMs == null) return RADAR_RECONNECT_BACKOFF_MAX_MS
    return if (now - offSinceMs > longOfflineThresholdMs) {
        longOfflineCapMs
    } else {
        RADAR_RECONNECT_BACKOFF_MAX_MS
    }
}

/**
 * Pure backoff-schedule arithmetic shared by the rear-radar, front-camera, and
 * eBike-status reconnect loops. Each loop keeps its own distinct reset trigger -
 * the radar resets the backoff on a healthy V2 decode, the camera on a quick
 * post-ABORT reconnect, the eBike on a subscribed session - so only the two
 * stateless steps live here: how long to wait before the next attempt, and how
 * to grow the backoff after a failure.
 */
internal object ReconnectLoopPlanner {
    /**
     * Delay before the next connect attempt. A quick (post-ABORT) reconnect
     * bypasses backoff with a fixed short wait; every other path jitters the
     * current backoff so concurrent riders dephase their retries ([jittered]).
     */
    fun nextDelayMs(
        backoffMs: Long,
        quickReconnect: Boolean,
        random: Random = Random.Default,
    ): Long = if (quickReconnect) RADAR_QUICK_RECONNECT_MS else jittered(backoffMs, random)

    /**
     * Grow the backoff after a non-quick failure: double it, clamped to the
     * moment's ceiling ([reconnectBackoffCap], which relaxes once the device has
     * been offline a long time). Callers gate this on `!quickReconnect`.
     */
    fun grow(
        backoffMs: Long,
        nowMs: Long,
        offSinceMs: Long?,
        longOfflineThresholdMs: Long,
        longOfflineCapMs: Long,
    ): Long = (backoffMs * 2).coerceAtMost(
        reconnectBackoffCap(
            now = nowMs,
            offSinceMs = offSinceMs,
            longOfflineThresholdMs = longOfflineThresholdMs,
            longOfflineCapMs = longOfflineCapMs,
        ),
    )
}
