// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import es.jjrh.bikeradar.data.Prefs

/**
 * Application entry point. It installs [CrashLogger] as early as possible, so
 * an uncaught exception on ANY thread - including one during MainActivity /
 * Compose startup, before the foreground service ever runs - is recorded to
 * app storage for later diagnosis. It also trims debug screenshots to their
 * cap, which the Privacy screen states, runs the one-time settings migrations
 * the service relies on, and keeps the saved approximate area in
 * step with the settings: read when one starts needing it, deleted when none
 * does ([LocationCache]).
 */
class BikeRadarApp : Application() {

    private var locationSettingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // A throw here would crash every start, so a failed trim is logged and dropped.
        runCatching { ScreenshotCaptureService.pruneSaved(getExternalFilesDir(null)) }
            .onFailure { Log.w("BikeRadar.App", "screenshot trim failed: $it") }
        // Before the service can start, so its first tick reads the result.
        runCatching { Prefs(this).migrateWalkAwayAlarmOffTheOverlayWarning() }
            .onFailure { Log.w("BikeRadar.App", "walk-away alarm migration failed: $it") }
        // Revoking location kills the process, so a start is where that is seen.
        runCatching { LocationCache.syncWithSettings(this) }
            .onFailure { Log.w("BikeRadar.App", "location sync failed: $it") }
        // A refresh both deletes an area no longer used and reads one just
        // switched on, which the rider may never resume a screen to trigger.
        locationSettingsListener = Prefs(this).onLocationSettingsChange { LocationCache.refreshIfStale(this) }
    }
}
