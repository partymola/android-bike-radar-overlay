// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs

/**
 * Receiver for adb-driven dev actions only (replay + synthetic scenario).
 * NOT exported: a peer app must never start the debug overlay/replay FGSes,
 * even on a device where the user has flipped [Prefs.devModeUnlocked] (which
 * each branch still checks, so a non-dev install is a no-op beyond a log
 * warning). adb reaches it with an explicit component, which shell may
 * deliver to a non-exported receiver:
 *   am broadcast -n es.jjrh.bikeradar/.RemoteControlReceiver \
 *                -a es.jjrh.bikeradar.DEV_REPLAY
 *
 * Notification-driven Pause/Resume and walk-away dismiss/snooze live on
 * the (also non-exported) [InternalControlReceiver].
 */
class RemoteControlReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "received $action")
        val prefs = Prefs(ctx)

        when (action) {
            ACTION_DEV_REPLAY -> {
                if (!prefs.devModeUnlocked) {
                    Log.w(TAG, "dev mode locked, ignoring $action")
                    return
                }
                stopService(ctx, SyntheticScenarioService::class.java)
                startFg(ctx, DebugOverlayService::class.java)
                startFg(ctx, ReplayService::class.java)
            }
            ACTION_DEV_SYNTH -> {
                if (!prefs.devModeUnlocked) {
                    Log.w(TAG, "dev mode locked, ignoring $action")
                    return
                }
                stopService(ctx, ReplayService::class.java)
                startFg(ctx, DebugOverlayService::class.java)
                startFg(ctx, SyntheticScenarioService::class.java)
            }
            ACTION_DEV_RADAR_LIGHT_WRITE -> {
                // Debug radar tail-light mode-set write-probe. Double-gated:
                // dev mode AND the radar-settings probe toggle must both be on.
                // Forwarded to the service, which owns the live radar GATT.
                if (!prefs.devModeUnlocked || !prefs.radarSettingsProbeEnabled) {
                    Log.w(
                        TAG,
                        "radar-light write ignored (dev=${prefs.devModeUnlocked} probe=${prefs.radarSettingsProbeEnabled})",
                    )
                    return
                }
                val nn = intent.getIntExtra(EXTRA_NN, -1)
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, BikeRadarService::class.java).apply {
                        this.action = BikeRadarService.ACTION_RADAR_LIGHT_PROBE_WRITE
                        putExtra(BikeRadarService.EXTRA_RADAR_LIGHT_NN, nn)
                    },
                )
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

        /** Debug radar light-mode write-probe: `--ei nn <0..255>` writes
         *  `07 00 NN` to the radar's 6a4e2f11. Dev-mode + probe-toggle gated. */
        const val ACTION_DEV_RADAR_LIGHT_WRITE = "es.jjrh.bikeradar.DEV_RADAR_LIGHT_WRITE"
        const val EXTRA_NN = "nn"
    }
}
