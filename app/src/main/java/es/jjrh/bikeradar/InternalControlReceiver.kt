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
 * Kept off the exported [RemoteControlReceiver] because a peer app could
 * otherwise silently pause the overlay or dismiss the walk-away alarm via
 * `am broadcast`. None of these actions leak data, but degrading
 * safety-critical UX is itself a finding.
 *
 * All callers target this receiver explicitly via PendingIntent component,
 * so no intent-filter is required.
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
        ctx.startService(Intent(ctx, BikeRadarService::class.java).apply {
            action = BikeRadarService.ACTION_UPDATE_NOTIF
        })
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
