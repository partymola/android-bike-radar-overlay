// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single-slot persistence for the current needs-attention feed.
 *
 * The service writes the freshly derived items once per ride end (see
 * `BikeRadarService.maybePostRideSummary`); the home screen reads them back
 * so the attention card survives a process death - the rider's recovery
 * path when the post-ride notification is dismissed by mistake. One
 * mutable slot, not an append-only log, so [save] fully replaces the set:
 * this ride's items are the whole truth, and an empty list clears the card.
 *
 * Stored as a compact JSON array of `{k, v}` objects (kind ordinal name +
 * optional value) in its own SharedPreferences file. Corrupt or
 * unknown-kind entries are skipped on read rather than failing the load.
 */
internal class AttentionStore(private val prefs: SharedPreferences) {

    fun save(items: List<AttentionItem>) {
        val arr = JSONArray()
        for (item in items) {
            val o = JSONObject().put(KEY_KIND, item.kind.name)
            if (item.value != null) o.put(KEY_VALUE, item.value)
            arr.put(o)
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun load(): List<AttentionItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { AttentionKind.valueOf(o.getString(KEY_KIND)) }.getOrNull()
                    ?: return@mapNotNull null
                val value = if (o.has(KEY_VALUE) && !o.isNull(KEY_VALUE)) o.getInt(KEY_VALUE) else null
                AttentionItem(kind, value)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        const val PREFS_NAME = "attention"
        private const val KEY_ITEMS = "items"
        private const val KEY_KIND = "k"
        private const val KEY_VALUE = "v"
    }
}
