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
 * The scan outlives the process, so a sighting can arrive with nothing running
 * and the service start is then refused. That is not an error state - it is the
 * app being closed - so it leaves a journal line rather than an uncaught
 * exception.
 */
class BatteryScanReceiver : BroadcastReceiver() {

    // Reads BluetoothDevice.name (BLUETOOTH_CONNECT). This receiver fires only
    // from the scan PendingIntent the service registers while holding
    // BLUETOOTH_SCAN + _CONNECT; the scan outlives the grant as well as the
    // process, so a revoked _CONNECT would surface here rather than be caught.
    @SuppressLint("MissingPermission")
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
            extractResults(intent).forEach { r ->
                val n = r.scanRecord?.deviceName ?: r.device?.name ?: "?"
                Log.i(TAG, "match-lost $n ${LogRedaction.mac(r.device?.address)}")
            }
            return
        }

        val results = extractResults(intent)
        if (results.isEmpty()) return

        // Escape hatch for radars the name heuristic doesn't know: a sighting
        // of the explicitly pinned radar MAC passes even with a foreign name,
        // so a pinned unit can reach the link path at all.
        val pinnedRadarMac = Prefs(ctx).radarMac

        for (r in results) {
            val name = r.scanRecord?.deviceName ?: r.device?.name ?: continue
            val mac = r.device?.address ?: continue
            val pinned = pinnedRadarMac != null && mac.equals(pinnedRadarMac, ignoreCase = true)
            if (!matchesVariaName(name) && !pinned) continue
            // Defence-in-depth: only act on devices the user has paired
            // with through the system. Without this gate, a peer
            // advertising the Garmin company UUID + a name matching the
            // heuristic could trigger GATT churn or BatteryEntry slug
            // injection.
            if (!isBonded(r)) {
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

    @SuppressLint("MissingPermission")
    private fun isBonded(r: ScanResult): Boolean = r.device?.bondState == BluetoothDevice.BOND_BONDED

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
     * Rate-limiter for the refused-start journal line.
     *
     * A refusal repeats for as long as the app stays closed, and each repeat
     * says the same thing. Unthrottled, a ride's worth of them would push the
     * link history out of the size-capped journal - the history being the
     * reason the journal exists.
     *
     * Per-process and best-effort: a refusal means nothing is running, so the
     * process it throttles is an empty one and the first the OS reaps. A wake
     * after that reap starts from a clean slate and writes again.
     */
    internal class RefusalLogThrottle {
        private var lastLogMs: Long? = null

        /** Consumes the window when it says yes. */
        fun shouldLog(nowMs: Long): Boolean {
            val last = lastLogMs
            if (last != null && nowMs - last < REFUSAL_LOG_WINDOW_MS) return false
            lastLogMs = nowMs
            return true
        }
    }

    companion object {
        private const val TAG = "BikeRadar.Scan"
        const val ACTION_SCAN_RESULT = "es.jjrh.bikeradar.BATTERY_SCAN_RESULT"
        private const val NO_ERROR_SENTINEL = Int.MIN_VALUE

        /** How often a refused start may write a journal line. */
        @VisibleForTesting
        internal const val REFUSAL_LOG_WINDOW_MS = 15 * 60 * 1000L

        /** Shared because the OS builds a fresh receiver per broadcast; read
         *  and written only from the main thread that dispatches them. */
        @VisibleForTesting
        internal var refusalThrottle = RefusalLogThrottle()

        fun matchesVariaName(n: String): Boolean = DeviceNameMatcher.isKnownAccessory(n)
    }
}
