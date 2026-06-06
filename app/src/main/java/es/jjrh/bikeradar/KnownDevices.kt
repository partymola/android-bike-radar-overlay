// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.SharedPreferences

/**
 * The name<->MAC cache for devices the app has seen (radar, dashcam). Persisted
 * as a `name|mac` string set so a battery advert can be attributed to a display
 * name, and so the ride-summary publish can label its HA entity. Pure
 * SharedPreferences I/O - extracted from [BikeRadarService] so the HA publisher
 * and the dashcam read path share one store instead of reaching back into the
 * service.
 */
internal class KnownDevices(private val prefs: SharedPreferences) {

    fun load(): List<Pair<String, String>> {
        val raw = prefs.getStringSet(KEY_KNOWN, emptySet()) ?: emptySet()
        return raw.mapNotNull {
            val p = it.split("|", limit = 2)
            if (p.size == 2) p[0] to p[1] else null
        }
    }

    fun save(devs: List<Pair<String, String>>) {
        prefs.edit().putStringSet(KEY_KNOWN, devs.map { "${it.first}|${it.second}" }.toSet()).apply()
    }

    companion object {
        private const val KEY_KNOWN = "known_devices"
    }
}
