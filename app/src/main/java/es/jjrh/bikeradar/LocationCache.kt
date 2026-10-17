// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs
import java.io.File
import kotlin.math.roundToInt

/**
 * The rider's approximate area for the front- and radar-light auto-modes'
 * sunrise and sunset, rounded to tenths of a degree (about 11 km by 7 km in
 * the UK, under a minute of sunset error up to about 60 degrees north).
 *
 * Android grants a while-in-use location only while one of the app's screens
 * is visible, never to the `connectedDevice` radar service, so the read at a
 * radar or camera handshake is refused whenever the app is off screen. A
 * refused read returns null like "no fix". So [refreshIfStale] is also called
 * when an app screen resumes, and each fix it gets is kept in ONE saved slot
 * under [Context.getNoBackupFilesDir], which Android keeps out of backups and
 * device transfer. A later read with no live fix falls back to that slot.
 *
 * The slot exists only while it can be used: location granted, the radar
 * light's auto-mode on or the front light's with a front camera selected, and
 * no manual coordinates (which win in [RideLocationResolver]).
 * Every refresh checks that first and deletes the slot otherwise. The app
 * start runs [syncWithSettings], and each change to those settings runs a
 * refresh (`LocationCacheRefreshTest`). The coordinate is never logged.
 */
object LocationCache {

    private const val TAG = "BikeRadar.LocCache"
    private const val DEFAULT_MAX_AGE_MS = 60L * 60L * 1000L // 60 minutes

    /** The saved slot's file name, under [Context.getNoBackupFilesDir]. */
    internal const val FILE_NAME = "approx-location"

    /** A fix in tenths of a degree, and when this process read it live; 0 for
     *  one loaded from the saved slot, which never counts as fresh. One
     *  immutable value, so the radar and camera handshakes and a resuming
     *  screen cannot interleave a latitude from one fix with another's
     *  longitude. */
    private data class Fix(val latTenths: Int, val lonTenths: Int, val liveAtMs: Long)

    @Volatile private var fix: Fix? = null

    /** The area, or null when none is held. */
    fun current(): Pair<Double, Double>? = fix?.let { it.latTenths / 10.0 to it.lonTenths / 10.0 }

    /** When the held area was read live, or 0 if it was not. */
    fun lastFetchMs(): Long = fix?.liveAtMs ?: 0L

    /**
     * Read the area if the held one was not read live within [maxAgeMs]
     * (default 60 min), saving a live fix and falling back to the saved slot
     * when no live one comes. Deletes both when the area is not [wanted].
     *
     * Returns true when [current] is non-null afterwards. Shares a lock with
     * [syncWithSettings], so a read that passed the check cannot save after a
     * settings change deleted the slot.
     */
    @Synchronized
    fun refreshIfStale(ctx: Context, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean {
        if (!wanted(ctx)) {
            forget(ctx)
            return false
        }
        val now = System.currentTimeMillis()
        val held = fix
        if (held != null && now - held.liveAtMs < maxAgeMs) return true

        val loc = (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.let(::bestLastKnown)
        if (loc != null) {
            val live = Fix(tenths(loc.latitude), tenths(loc.longitude), now)
            fix = live
            val changed = held == null || held.latTenths != live.latTenths || held.lonTenths != live.lonTenths
            // A save that failed earlier is retried while the slot is missing.
            if (changed || !slot(ctx).baseFile.exists()) save(ctx, live)
            // Provider and age only, never the coordinate: this runs in release
            // builds, and logcat is read by bugreports and anyone with adb.
            Log.i(TAG, "refreshed provider=${loc.provider} ageMs=${now - loc.time}")
            return true
        }
        if (held == null) fix = load(ctx)
        Log.d(TAG, if (fix != null) "no live fix; using the saved area" else "no live fix and no saved area")
        return fix != null
    }

    /** Delete the held and saved area unless it is still [wanted]. */
    @Synchronized
    fun syncWithSettings(ctx: Context) {
        if (!wanted(ctx)) forget(ctx)
    }

    private fun wanted(ctx: Context): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val prefs = Prefs(ctx)
        // The front light's auto-mode drives nothing without a front camera.
        val frontInUse = prefs.autoLightModeEnabled && prefs.activeDashcamMac != null
        if (!frontInUse && !prefs.radarLightAutoModeEnabled) return false
        return RideLocationResolver.validManualLocation(prefs.manualLocationLat, prefs.manualLocationLon) == null
    }

    private fun forget(ctx: Context) {
        fix = null
        slot(ctx).delete()
    }

    private fun tenths(degrees: Double): Int = (degrees * 10).roundToInt()

    private fun slot(ctx: Context) = AtomicFile(File(ctx.noBackupFilesDir, FILE_NAME))

    private fun save(ctx: Context, f: Fix) {
        val file = slot(ctx)
        val out = try {
            file.startWrite()
        } catch (t: Throwable) {
            Log.w(TAG, "could not save the area: $t")
            return
        }
        try {
            out.write("${f.latTenths} ${f.lonTenths}".toByteArray())
            file.finishWrite(out)
        } catch (t: Throwable) {
            file.failWrite(out)
            Log.w(TAG, "could not save the area: $t")
        }
    }

    /** The saved slot, or null; a slot that does not parse to an in-range
     *  area is deleted. */
    private fun load(ctx: Context): Fix? {
        val file = slot(ctx)
        val parts = try {
            String(file.readFully()).trim().split(' ')
        } catch (_: java.io.FileNotFoundException) {
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the saved area: $t")
            return null
        }
        val lat = parts.getOrNull(0)?.toIntOrNull()
        val lon = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 2 || lat == null || lon == null || lat !in -900..900 || lon !in -1800..1800) {
            file.delete()
            return null
        }
        return Fix(lat, lon, 0L)
    }

    @Suppress("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        var best: Location? = null
        for (p in providers) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (_: RuntimeException) {
                // A refused grant, a provider this device lacks, or a dead
                // system server; this runs on the main thread from a screen.
                null
            }
            // A mock provider can report NaN, which would throw in the rounding.
            if (l == null || !RideLocationResolver.isValidLat(l.latitude) || !RideLocationResolver.isValidLon(l.longitude)) continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    /** Test-only seam: sets the held area, rounded as a live read would be. */
    internal fun overrideForTest(lat: Double?, lon: Double?, atMs: Long = 0L) {
        fix = if (lat != null && lon != null) Fix(tenths(lat), tenths(lon), atMs) else null
    }
}
