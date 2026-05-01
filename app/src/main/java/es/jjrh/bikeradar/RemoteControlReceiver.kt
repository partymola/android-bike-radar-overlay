// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs

/**
 * Exported receiver for adb-driven dev actions only (replay + synthetic
 * scenario). Both branches gate on [Prefs.devModeUnlocked] so broadcasting
 * at a non-dev install is a no-op beyond a log warning.
 *
 * Notification-driven Pause/Resume and walk-away dismiss/snooze live on
 * the non-exported [InternalControlReceiver] so peer apps cannot reach
 * them.
 */
class RemoteControlReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "received $action")
        val prefs = Prefs(ctx)

        when (action) {
            ACTION_DEV_REPLAY -> {
                if (!prefs.devModeUnlocked) { Log.w(TAG, "dev mode locked, ignoring $action"); return }
                stopService(ctx, SyntheticScenarioService::class.java)
                startFg(ctx, DebugOverlayService::class.java)
                startFg(ctx, ReplayService::class.java)
            }
            ACTION_DEV_SYNTH -> {
                if (!prefs.devModeUnlocked) { Log.w(TAG, "dev mode locked, ignoring $action"); return }
                stopService(ctx, ReplayService::class.java)
                startFg(ctx, DebugOverlayService::class.java)
                startFg(ctx, SyntheticScenarioService::class.java)
            }
            else -> Log.w(TAG, "unknown action $action")
        }
    }

    private fun startFg(ctx: Context, cls: Class<*>) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, cls))
    }

    private fun stopService(ctx: Context, cls: Class<*>) {
        ctx.stopService(Intent(ctx, cls))
    }

    companion object {
        private const val TAG = "BikeRadar.Remote"

        const val ACTION_DEV_REPLAY = "es.jjrh.bikeradar.DEV_REPLAY"
        const val ACTION_DEV_SYNTH = "es.jjrh.bikeradar.DEV_SYNTH"
    }
}
