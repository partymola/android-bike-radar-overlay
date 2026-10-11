// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import es.jjrh.bikeradar.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = Prefs(ctx)
        val started = ServiceAutoStart.startIfAllowed(ctx, prefs)
        val permitted = Permissions.hasRequiredForService(ctx)
        val line = "$action: started=$started first_run_complete=${prefs.firstRunComplete}" +
            " service_enabled=${prefs.serviceEnabled} permitted=$permitted"
        // A rider set up and scanning but left without the service is the
        // outcome worth seeing in a log; the rest is routine.
        if (!permitted && prefs.firstRunComplete && prefs.serviceEnabled) Log.w(TAG, line) else Log.d(TAG, line)
    }

    companion object {
        private const val TAG = "BikeRadar.Boot"
    }
}
