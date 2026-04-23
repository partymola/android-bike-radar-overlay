// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = Prefs(ctx)
        if (!prefs.firstRunComplete) {
            Log.d(TAG, "$action: first_run_complete=false, not starting")
            return
        }
        if (!prefs.serviceEnabled) {
            Log.d(TAG, "$action: service_enabled=false, not starting")
            return
        }
        if (!Permissions.hasRequiredForService(ctx)) {
            Log.w(TAG, "$action: required perms missing, not starting")
            return
        }
        Log.d(TAG, "$action: starting BikeRadarService")
        val i = Intent(ctx, BikeRadarService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(ctx, i)
        } else {
            ctx.startService(i)
        }
    }

    companion object {
        private const val TAG = "BikeRadar.Boot"
    }
}
