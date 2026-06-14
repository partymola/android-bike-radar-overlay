// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide bus exposing the eBike live-data snapshot (and its freshness)
 * to UI surfaces outside the [BikeRadarService]. The service-owned
 * [EBikeStatusReader] mirrors each decoded frame here via [setSnapshot]; the
 * SYSTEM-card eBike row, Settings -> eBike and the onboarding step read it.
 *
 * Same pattern as [HaHealthBus] and [BatteryStateBus]: a MutableStateFlow per
 * signal, kept current by the producer, read via the read-only StateFlow. When
 * the service isn't running these stay at their last value; [reset] (called on
 * service destroy) clears them.
 */
object EBikeStateBus {
    private val _snapshot = MutableStateFlow(LiveDataSnapshot())
    val snapshot: StateFlow<LiveDataSnapshot> = _snapshot

    // Monotonic (elapsedRealtime) ms of the last snapshot update, so UI can tell
    // live from stale: the snapshot fields stay populated after Flow closes, so
    // freshness is the only honest "is data still flowing" signal. 0 = never.
    private val _lastUpdatedElapsedMs = MutableStateFlow(0L)
    val lastUpdatedElapsedMs: StateFlow<Long> = _lastUpdatedElapsedMs

    fun setSnapshot(value: LiveDataSnapshot) {
        _snapshot.value = value
        _lastUpdatedElapsedMs.value = SystemClock.elapsedRealtime()
    }

    /** Restore default state. Called on service destroy so UI surfaces see a
     *  clean empty state after the rider stops the service. */
    fun reset() {
        _snapshot.value = LiveDataSnapshot()
        _lastUpdatedElapsedMs.value = 0L
    }
}

/** A live-data frame newer than this counts as "receiving"; older is stale
 *  (Flow likely closed or the bike off). */
const val EBIKE_DATA_FRESH_MS = 6_000L

/**
 * Whether live eBike data is currently flowing, from the bus's
 * [EBikeStateBus.lastUpdatedElapsedMs] and now (both `elapsedRealtime`).
 * Pure (now is injectable) so UI freshness is unit-testable. `lastUpdated`
 * of 0 means no frame has ever arrived this session -> not fresh.
 */
fun eBikeDataIsFresh(
    lastUpdatedElapsedMs: Long,
    nowMs: Long = SystemClock.elapsedRealtime(),
    windowMs: Long = EBIKE_DATA_FRESH_MS,
): Boolean = lastUpdatedElapsedMs > 0L && (nowMs - lastUpdatedElapsedMs) in 0 until windowMs
