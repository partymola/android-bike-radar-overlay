// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
 *
 * A button can outlive its service: the walk-away alert is a plain
 * notification, so it can survive an unclean death, and swiping it counts as
 * Dismiss. So no action here starts a service once its onDestroy has run.
 * With none running, the walk-away buttons clear the alert and restore an
 * alarm volume it left at maximum, and say so in the link journal as the
 * service's own dismiss does; a running service always gets them, since it
 * may be sounding the alarm (`InternalControlReceiverTest`).
 */
class InternalControlReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "received $action")
        val prefs = Prefs(ctx)
        // Set in the service's onCreate and cleared in its onDestroy
        // (`onCreatePublishesTheLinkStateAndDestroyRetractsIt`). Not
        // serviceRunningMarker, which an unclean death leaves set.
        val serviceRunning = BikeRadarService.radarLinkStateForUi != null

        when (action) {
            ACTION_PAUSE_1H -> {
                prefs.pausedUntilEpochMs = System.currentTimeMillis() + 3_600_000L
                Log.i(TAG, "paused until ${prefs.pausedUntilEpochMs}")
                if (serviceRunning) updateServiceNotification(ctx)
            }
            ACTION_RESUME -> {
                prefs.pausedUntilEpochMs = 0L
                Log.i(TAG, "resumed")
                if (serviceRunning) updateServiceNotification(ctx)
            }
            ACTION_WALKAWAY_DISMISS -> walkAway(ctx, prefs, serviceRunning, BikeRadarService.ACTION_WALKAWAY_DISMISS)
            ACTION_WALKAWAY_SNOOZE -> walkAway(ctx, prefs, serviceRunning, BikeRadarService.ACTION_WALKAWAY_SNOOZE)
            else -> Log.w(TAG, "unknown action $action")
        }
    }

    private fun walkAway(ctx: Context, prefs: Prefs, serviceRunning: Boolean, serviceAction: String) {
        if (serviceRunning) {
            forwardToService(ctx, serviceAction)
            return
        }
        Log.i(TAG, "walk-away alert cleared; no service running")
        ServiceNotifications(ctx) { prefs }.cancelWalkAway()
        val journal = LinkEventJournal({ ctx.getExternalFilesDir(null) })
        journal.log("walk-away alarm cleared by rider; no service running")
        if (WalkAwayAlarm.repairLeakedOverride(ctx, prefs)) {
            journal.log("walk-away alarm volume restored; the service stopped mid-alarm")
        }
    }

    private fun updateServiceNotification(ctx: Context) {
        ctx.startService(
            Intent(ctx, BikeRadarService::class.java).apply {
                action = BikeRadarService.ACTION_UPDATE_NOTIF
            },
        )
    }

    // Reached only with the service running and already in the foreground.
    private fun forwardToService(ctx: Context, serviceAction: String) {
        ctx.startService(Intent(ctx, BikeRadarService::class.java).apply { action = serviceAction })
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
