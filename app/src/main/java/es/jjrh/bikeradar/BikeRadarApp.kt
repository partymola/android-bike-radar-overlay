// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.util.Log

/**
 * Application entry point. It installs [CrashLogger] as early as possible, so
 * an uncaught exception on ANY thread - including one during MainActivity /
 * Compose startup, before the foreground service ever runs - is recorded to
 * app storage for later diagnosis. It also trims debug screenshots to their
 * cap, which the Privacy screen states.
 */
class BikeRadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // A throw here would crash every start, so a failed trim is logged and dropped.
        runCatching { ScreenshotCaptureService.pruneSaved(getExternalFilesDir(null)) }
            .onFailure { Log.w("BikeRadar.App", "screenshot trim failed: $it") }
    }
}
