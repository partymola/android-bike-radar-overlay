// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs

/**
 * Non-exported receiver for safety-relevant UX actions (Pause/Resume from
 * the foreground-service notification, walk-away alarm dismiss/snooze).
 *
 * These live here, separate from the dev-only [RemoteControlReceiver], and
 * are reached only by an explicit PendingIntent component (no intent-filter).
 * Both receivers are non-exported, so no peer app can reach either; keeping
 * the safety actions off the dev receiver is defense-in-depth, so a future
 * re-export of that receiver could never let `am broadcast` silently pause
 * the overlay or dismiss a walk-away alarm.
 */
class InternalControlReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "received $action")
        val prefs = Prefs(ctx)

        when (action) {
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
            ACTION_WALKAWAY_DISMISS -> {
                forwardToService(ctx, BikeRadarService.ACTION_WALKAWAY_DISMISS)
            }
            ACTION_WALKAWAY_SNOOZE -> {
                forwardToService(ctx, BikeRadarService.ACTION_WALKAWAY_SNOOZE)
            }
            else -> Log.w(TAG, "unknown action $action")
        }
    }

    private fun updateServiceNotification(ctx: Context) {
        ctx.startService(
            Intent(ctx, BikeRadarService::class.java).apply {
                action = BikeRadarService.ACTION_UPDATE_NOTIF
            },
        )
    }

    private fun forwardToService(ctx: Context, serviceAction: String) {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, BikeRadarService::class.java).apply { action = serviceAction },
        )
    }

    companion object {
        private const val TAG = "BikeRadar.Internal"

        const val ACTION_PAUSE_1H = "es.jjrh.bikeradar.PAUSE_1H"
        const val ACTION_RESUME = "es.jjrh.bikeradar.RESUME"

        // Walk-away action strings deliberately match the same-named
        // constants in BikeRadarService — the receiver forwards the
        // intent through to the service unchanged. Different entry
        // points, same wire format; do not "deduplicate" by referencing
        // one from the other.
        const val ACTION_WALKAWAY_DISMISS = "es.jjrh.bikeradar.WALKAWAY_DISMISS"
        const val ACTION_WALKAWAY_SNOOZE = "es.jjrh.bikeradar.WALKAWAY_SNOOZE"
    }
}
