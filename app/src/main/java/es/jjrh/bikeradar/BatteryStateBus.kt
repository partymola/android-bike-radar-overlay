// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class BatteryEntry(
    val slug: String,
    val name: String,
    val pct: Int,
    val readAtMs: Long = System.currentTimeMillis(),
    /** Monotonic (elapsedRealtime) sibling of [readAtMs]. Control-loop staleness
     *  that must survive a wall-clock jump - the walk-away alarm's dashcam-advert
     *  freshness - reads this; [readAtMs] stays wall for UI display. */
    val lastSeenElapsedMs: Long = SystemClock.elapsedRealtime(),
)

/**
 * Widest age at which a stored battery read may still be shown as a live
 * reading. Matches the window the radar and dashcam sub-screens already apply.
 *
 * The bus is never cleared in production - the service owns its lifetime - so
 * an entry read once at the start of a ride sits there for the rest of the
 * process. Any surface that renders an entry without checking this is claiming
 * a device is connected on the strength of a reading that may be hours old.
 */
const val BATTERY_UI_FRESH_MS = 30_000L

/** True when [readAtMs] is recent enough to render as a live reading. */
fun batteryReadIsFresh(
    readAtMs: Long,
    nowMs: Long,
    windowMs: Long = BATTERY_UI_FRESH_MS,
): Boolean = nowMs - readAtMs < windowMs

object BatteryStateBus {
    private val _entries = MutableStateFlow<Map<String, BatteryEntry>>(emptyMap())
    val entries: StateFlow<Map<String, BatteryEntry>> = _entries

    // Atomic update - concurrent callers cannot drop each other's writes.
    fun update(entry: BatteryEntry) {
        _entries.update { it + (entry.slug to entry) }
    }

    /** Refresh readAtMs (wall, UI) and lastSeenElapsedMs (monotonic, the
     *  control-loop freshness the walk-away alarm reads) on an existing entry
     *  without changing pct. Used when we see the device in an advert but the
     *  throttle is skipping the actual GATT read - the advert proves liveness.
     *  No-op if the slug has no prior entry (first sighting still goes through
     *  a full read + [update]). */
    fun markSeen(
        slug: String,
        nowMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        _entries.update { prev ->
            val existing = prev[slug] ?: return@update prev
            prev + (slug to existing.copy(readAtMs = nowMs, lastSeenElapsedMs = elapsedMs))
        }
    }

    /** Empty the bus so this process-wide singleton doesn't leak entries
     *  between tests. Production has no need to clear (the service owns the
     *  lifetime); only the unit suite calls this from tearDown. */
    @VisibleForTesting
    fun clearForTest() {
        _entries.value = emptyMap()
    }
}
