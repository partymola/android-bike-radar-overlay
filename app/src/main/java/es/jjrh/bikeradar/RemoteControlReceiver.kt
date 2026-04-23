// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs

/**
 * adb control + notification Pause/Resume actions.
 *
 * Dev actions (DEV_REPLAY, DEV_SYNTH) are gated on prefs.devModeUnlocked so
 * broadcasting at a non-dev install is a no-op beyond a log warning.
 *
 * Pause/Resume come from the foreground-service notification action, not adb.
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
            ACTION_PAUSE_1H -> {
                prefs.pausedUntilEpochMs = System.currentTimeMillis() + 3_600_000L
                Log.i(TAG, "paused until ${prefs.pausedUntilEpochMs}")
                updateServiceNotification(ctx)
            }
            ACTION_RESUME -> {
                prefs.pausedUntilEpochMs = 0L
                Log.i(TAG, "resumed")
                updateServiceNotification(ctx)
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

    private fun updateServiceNotification(ctx: Context) {
        ctx.startService(Intent(ctx, BikeRadarService::class.java).apply {
            action = BikeRadarService.ACTION_UPDATE_NOTIF
        })
    }

    companion object {
        private const val TAG = "BikeRadar.Remote"

        const val ACTION_DEV_REPLAY = "es.jjrh.bikeradar.DEV_REPLAY"
        const val ACTION_DEV_SYNTH = "es.jjrh.bikeradar.DEV_SYNTH"
        const val ACTION_PAUSE_1H = "es.jjrh.bikeradar.PAUSE_1H"
        const val ACTION_RESUME = "es.jjrh.bikeradar.RESUME"
    }
}
