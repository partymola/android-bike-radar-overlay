// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs

/**
 * Receives advertisement-batch broadcasts from the OS BLE scanner.
 *
 * BikeRadarService registers a PendingIntent scan with a hardware filter on
 * Garmin's 16-bit company UUID 0000fe1f. The BT controller does the matching
 * off-host; we only get woken when a Vue or RearVue actually starts advertising.
 *
 * For each matching result we hand off to BikeRadarService via ACTION_READ_DEVICE;
 * the service throttles per-device so rapid-fire adverts don't trigger rapid-fire
 * GATT reads.
 *
 * The scan outlives both the process and the permission grant, so a sighting
 * can arrive with nothing running - the service start is then refused - or with
 * BLUETOOTH_CONNECT revoked, when the device read is denied. Neither is an
 * error state: one is the app being closed, the other the rider taking a
 * permission back. Each is journalled rather than thrown.
 */
class BatteryScanReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, NO_ERROR_SENTINEL)
        if (errorCode != NO_ERROR_SENTINEL && errorCode != 0) {
            Log.w(TAG, "scan error code=$errorCode")
            return
        }

        val callbackType = intent.getIntExtra(
            BluetoothLeScanner.EXTRA_CALLBACK_TYPE,
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
        )

        if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
            val lost = sightings(ctx, extractResults(intent)) ?: return
            lost.forEach { Log.i(TAG, "match-lost ${it.name ?: "?"} ${LogRedaction.mac(it.mac)}") }
            return
        }

        val results = extractResults(intent)
        if (results.isEmpty()) return
        val seen = sightings(ctx, results) ?: return

        // Escape hatch for radars the name heuristic doesn't know: a sighting
        // of the explicitly pinned radar MAC passes even with a foreign name,
        // so a pinned unit can reach the link path at all.
        val pinnedRadarMac = Prefs(ctx).radarMac

        for (s in seen) {
            val name = s.name ?: continue
            val mac = s.mac ?: continue
            val pinned = pinnedRadarMac != null && mac.equals(pinnedRadarMac, ignoreCase = true)
            if (!matchesVariaName(name) && !pinned) continue
            // Defence-in-depth: only act on devices the user has paired
            // with through the system. Without this gate, a peer
            // advertising the Garmin company UUID + a name matching the
            // heuristic could trigger GATT churn or BatteryEntry slug
            // injection.
            if (!s.bonded) {
                Log.d(TAG, "skip $name: not bonded")
                continue
            }
            Log.i(TAG, "match $name ${LogRedaction.mac(mac)} cbType=$callbackType")

            val i = Intent(ctx, BikeRadarService::class.java).apply {
                action = BikeRadarService.ACTION_READ_DEVICE
                putExtra(BikeRadarService.EXTRA_NAME, name)
                putExtra(BikeRadarService.EXTRA_MAC, mac)
            }
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Every other result in this batch would be refused the same way.
                Log.i(TAG, "service start refused, app not running: $e")
                if (refusalThrottle.shouldLog(SystemClock.elapsedRealtime())) {
                    LinkEventJournal({ ctx.getExternalFilesDir(null) })
                        .log("scan wake ignored: app not running, service start refused ($name)")
                }
                return
            }
        }
    }

    private class Sighting(val name: String?, val mac: String?, val bonded: Boolean)

    /**
     * Reads every result's device fields up front, or null when the read is
     * denied - which ends the batch, since the denial is app-wide.
     *
     * Reading is what a revoked grant denies, so the reads sit inside the try
     * and the service start deliberately does not: a SecurityException from
     * there is treated as a bug rather than a revocation, so it propagates
     * instead of being filed under the rider's permission. The catch is pinned
     * narrow from both sides: `aSecurityExceptionFromTheServiceStartStillPropagates`
     * for the start, `aNonSecurityFailureOnTheDeviceReadStillPropagates` for the read.
     */
    @SuppressLint("MissingPermission")
    private fun sightings(ctx: Context, results: List<ScanResult>): List<Sighting>? = try {
        results.map { r ->
            Sighting(
                name = r.scanRecord?.deviceName ?: r.device?.name,
                mac = r.device?.address,
                bonded = r.device?.bondState == BluetoothDevice.BOND_BONDED,
            )
        }
    } catch (_: SecurityException) {
        // No exception text - it is boilerplate.
        Log.i(TAG, "device read denied, Bluetooth permission revoked")
        if (revocationThrottle.shouldLog(SystemClock.elapsedRealtime())) {
            LinkEventJournal({ ctx.getExternalFilesDir(null) })
                .log(
                    "scan wake ignored: Bluetooth permission revoked since the scan started" +
                        " - re-grant ${ctx.getString(R.string.permission_nearby_title)}" +
                        " to restore the radar link",
                )
        }
        null
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun extractResults(intent: Intent): List<ScanResult> {
        val key = BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(key, ScanResult::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra<ScanResult>(key) ?: emptyList()
        }
    }

    /**
     * Rate-limiter for a journal line whose cause repeats every wake.
     *
     * A refused start or a denied read says the same thing on every wake for as
     * long as its cause lasts. Unthrottled, a ride's worth of them would push
     * the link history out of the size-capped journal - the history being the
     * reason the journal exists.
     *
     * Per-process and best-effort: a process death resets it. In either case
     * that process is an empty one the OS reaps early, so a wake after the
     * reap starts from a clean slate and writes again.
     *
     * One instance per cause: sharing one would let whichever fired first hide
     * the other, and the two say different things.
     *
     * Instances live on the receiver's companion because the OS builds a fresh
     * receiver per broadcast; read and written only from the main thread that
     * dispatches them.
     */
    internal class JournalLogThrottle {
        private var lastLogMs: Long? = null

        /** Consumes the window when it says yes. */
        fun shouldLog(nowMs: Long): Boolean {
            val last = lastLogMs
            if (last != null && nowMs - last < JOURNAL_LOG_WINDOW_MS) return false
            lastLogMs = nowMs
            return true
        }
    }

    companion object {
        private const val TAG = "BikeRadar.Scan"
        const val ACTION_SCAN_RESULT = "es.jjrh.bikeradar.BATTERY_SCAN_RESULT"
        private const val NO_ERROR_SENTINEL = Int.MIN_VALUE

        /** How often each condition may write a journal line, counted per
         *  condition rather than shared between them. */
        @VisibleForTesting
        internal const val JOURNAL_LOG_WINDOW_MS = 15 * 60 * 1000L

        @VisibleForTesting
        internal var refusalThrottle = JournalLogThrottle()

        @VisibleForTesting
        internal var revocationThrottle = JournalLogThrottle()

        fun matchesVariaName(n: String): Boolean = DeviceNameMatcher.isKnownAccessory(n)
    }
}
