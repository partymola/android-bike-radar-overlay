// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application

/**
 * Application entry point. Its only job is to install [CrashLogger] as early as
 * possible, so an uncaught exception on ANY thread - including one during
 * MainActivity / Compose startup, before the foreground service ever runs - is
 * recorded to app storage for later diagnosis.
 */
class BikeRadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
