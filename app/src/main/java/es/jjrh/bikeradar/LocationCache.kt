// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * In-process cache of the rider's approximate location for sunrise / sunset
 * computation in front-light auto-mode.
 *
 * Refreshed on each successful BLE handshake (radar or front camera) per ride
 * session via [refreshIfStale], gated by a staleness threshold so quick
 * stop-and-go reconnects do not pile up `getLastKnownLocation` calls.
 *
 * Reads `ACCESS_COARSE_LOCATION` only - city-block accuracy is sufficient
 * since a 10 km position error shifts computed sunrise by ~1 minute. No
 * continuous tracking, no foreground polling, no background work. If the
 * permission is denied or no last-known fix is available, [current] returns
 * null and callers fall back to `SunsetCalculator`'s London defaults.
 *
 * Cache is in-process only - service restart clears it; the next handshake
 * repopulates. No SharedPreferences persistence is intentional: a stale
 * cached location surviving a several-hour service kill would defeat the
 * "fresh at ride start" guarantee.
 */
object LocationCache {

    private const val TAG = "BikeRadar.LocCache"
    private const val DEFAULT_MAX_AGE_MS = 60L * 60L * 1000L // 60 minutes

    @Volatile private var cachedLat: Double? = null

    @Volatile private var cachedLon: Double? = null

    @Volatile private var cachedAtMs: Long = 0L

    /** Most recent (lat, lon) pair, or null if never acquired or permission denied. */
    fun current(): Pair<Double, Double>? {
        val lat = cachedLat ?: return null
        val lon = cachedLon ?: return null
        return lat to lon
    }

    /** Timestamp of the last successful refresh, or 0 if never refreshed. */
    fun lastFetchMs(): Long = cachedAtMs

    /**
     * Refresh the cache if the last successful read is older than [maxAgeMs]
     * (default 60 min). No-op if `ACCESS_COARSE_LOCATION` is not granted or
     * no last-known fix is available.
     *
     * Returns true when [current] is non-null after the call; false when the
     * cache is still empty (permission denied, or no provider returned a
     * fix).
     */
    fun refreshIfStale(ctx: Context, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean {
        val now = System.currentTimeMillis()
        if (cachedLat != null && now - cachedAtMs < maxAgeMs) return true

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "ACCESS_COARSE_LOCATION not granted; skipping refresh")
            return cachedLat != null
        }

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.w(TAG, "LocationManager unavailable")
            return cachedLat != null
        }

        val loc = bestLastKnown(lm)
        if (loc == null) {
            Log.d(TAG, "no last-known location available across providers")
            return cachedLat != null
        }

        cachedLat = loc.latitude
        cachedLon = loc.longitude
        cachedAtMs = now
        Log.i(
            TAG,
            "refreshed lat=${"%.3f".format(loc.latitude)} " +
                "lon=${"%.3f".format(loc.longitude)} provider=${loc.provider} " +
                "ageMs=${now - loc.time}",
        )
        return true
    }

    @Suppress("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        var best: Location? = null
        for (p in providers) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null // provider unknown on this device
            }
            if (l == null) continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    /** Test-only seam. */
    internal fun overrideForTest(lat: Double?, lon: Double?, atMs: Long = 0L) {
        cachedLat = lat
        cachedLon = lon
        cachedAtMs = atMs
    }
}
