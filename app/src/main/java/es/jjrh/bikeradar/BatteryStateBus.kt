// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class BatteryEntry(
    val slug: String,
    val name: String,
    val pct: Int,
    val readAtMs: Long = System.currentTimeMillis(),
)

object BatteryStateBus {
    private val _entries = MutableStateFlow<Map<String, BatteryEntry>>(emptyMap())
    val entries: StateFlow<Map<String, BatteryEntry>> = _entries

    // Atomic update — concurrent callers cannot drop each other's writes.
    fun update(entry: BatteryEntry) {
        _entries.update { it + (entry.slug to entry) }
    }

    /** Refresh readAtMs on an existing entry without changing pct.
     *  Used when we see the device in an advert but the throttle is
     *  skipping the actual GATT read — the advert itself proves liveness.
     *  No-op if the slug has no prior entry (first sighting still goes
     *  through a full read + [update]). */
    fun markSeen(slug: String, nowMs: Long = System.currentTimeMillis()) {
        _entries.update { prev ->
            val existing = prev[slug] ?: return@update prev
            prev + (slug to existing.copy(readAtMs = nowMs))
        }
    }
}
