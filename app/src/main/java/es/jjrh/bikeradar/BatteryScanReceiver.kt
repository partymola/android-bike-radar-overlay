// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

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
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES
        )

        if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
            extractResults(intent).forEach { r ->
                val n = r.scanRecord?.deviceName ?: r.device?.name ?: "?"
                Log.i(TAG, "match-lost $n ${r.device?.address}")
            }
            return
        }

        val results = extractResults(intent)
        if (results.isEmpty()) return

        for (r in results) {
            val name = r.scanRecord?.deviceName ?: r.device?.name ?: continue
            val mac = r.device?.address ?: continue
            if (!matchesVariaName(name)) continue
            // Defence-in-depth: only act on devices the user has paired
            // with through the system. Without this gate, a peer
            // advertising the Garmin company UUID + a name matching the
            // heuristic could trigger GATT churn or BatteryEntry slug
            // injection.
            if (!isBonded(r)) {
                Log.d(TAG, "skip $name: not bonded")
                continue
            }
            Log.i(TAG, "match $name $mac cbType=$callbackType")

            val i = Intent(ctx, BikeRadarService::class.java).apply {
                action = BikeRadarService.ACTION_READ_DEVICE
                putExtra(BikeRadarService.EXTRA_NAME, name)
                putExtra(BikeRadarService.EXTRA_MAC, mac)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(ctx, i)
            } else {
                ctx.startService(i)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun isBonded(r: ScanResult): Boolean =
        r.device?.bondState == BluetoothDevice.BOND_BONDED

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun extractResults(intent: Intent): List<ScanResult> {
        val key = BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(key, ScanResult::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra<ScanResult>(key) ?: emptyList()
        }
    }

    companion object {
        private const val TAG = "BikeRadar.Scan"
        const val ACTION_SCAN_RESULT = "es.jjrh.bikeradar.BATTERY_SCAN_RESULT"
        private const val NO_ERROR_SENTINEL = Int.MIN_VALUE

        fun matchesVariaName(n: String): Boolean {
            val l = n.lowercase()
            return l.contains("varia") || l.contains("vue") ||
                l.contains("rearvue") || l.contains("rtl") || l.contains("garmin")
        }
    }
}
