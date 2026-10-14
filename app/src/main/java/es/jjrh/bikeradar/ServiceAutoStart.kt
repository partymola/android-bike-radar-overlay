// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs

/**
 * The one rule for starting [BikeRadarService] on the rider's behalf: setup
 * finished, the service not stopped by the rider, and Nearby devices granted
 * ([Permissions.hasRequiredForService]). Every activity resume, the home
 * screen on entry and on a change in what is missing, the end of setup, boot
 * and package replace, and the home screen's Start button all go through
 * [startIfAllowed].
 *
 * The [Prefs.serviceEnabled] gate is what keeps "Stop scanning" stopped across
 * app opens; drop it and every resume restarts a service the rider turned off
 * (`ServiceAutoStartTest`, `MainActivitySmokeTest`,
 * `MainScreenPermissionsHeroTest`). Starts that carry an
 * action (a scan wake, the eBike setup step, a notification button, End ride,
 * the dev tools) do not come through here; the scan wake checks
 * [Prefs.serviceEnabled] itself (`BatteryScanReceiverTest`).
 *
 * Every caller today is exempt from Android 12's background-start limit: an
 * activity on screen (resumed, or composing the home screen), boot, or a
 * package replace. A caller from a worker or alarm would need to catch
 * `ForegroundServiceStartNotAllowedException`, as `BatteryScanReceiver` does.
 */
object ServiceAutoStart {

    /** Start the service if the rule allows; true when it did. */
    fun startIfAllowed(ctx: Context, prefs: Prefs): Boolean {
        if (!(prefs.firstRunComplete && prefs.serviceEnabled && Permissions.hasRequiredForService(ctx))) return false
        ContextCompat.startForegroundService(ctx, Intent(ctx, BikeRadarService::class.java))
        return true
    }
}
