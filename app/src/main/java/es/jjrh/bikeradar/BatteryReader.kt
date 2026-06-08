// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** SharedPreferences store name (shared by the known-device cache and the
 *  per-device battery-read throttle) and the throttle key prefix. Top-level so
 *  both BikeRadarService (scheduleRead / onCreate) and [BatteryReader] resolve
 *  them without a cross-class qualifier. */
internal const val PREFS_THROTTLE = "bike_radar_throttle"
internal const val KEY_LAST_TS = "last_ts"

/**
 * One-shot GATT battery reads for the rear radar and the front camera/dashcam.
 * Connects out, reads the standard 0x2A19 battery characteristic, publishes to
 * [BatteryStateBus] + HA, then disconnects. BikeRadarService.scheduleRead (the
 * BLE-scan dispatch hub) owns the 5-minute SharedPrefs throttle and calls
 * [launch] when a read is due; this class owns only the in-flight cooldown and
 * the actual GATT read. The dashcam-probe-failure counter is shared with the
 * service's dashcam-refresh loop, so it is injected rather than owned.
 */
@SuppressLint("MissingPermission")
internal class BatteryReader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val knownDevices: KnownDevices,
    private val haPublisher: HaPublisher,
    private val macToSlug: MutableMap<String, String>,
    private val slug: (String) -> String,
    private val dashcamProbeFailures: MutableMap<String, Int>,
) {
    // Per-mac in-flight guard: a read launched within ATTEMPT_COOLDOWN_MS is
    // skipped so back-to-back fires from different paths don't stack.
    private val attemptInFlight = ConcurrentHashMap<String, Long>()

    /**
     * Real GATT battery read, bypassing the SharedPrefs throttle but still
     * gated by [ATTEMPT_COOLDOWN_MS] so back-to-back fires from different
     * paths don't stack. Use this for liveness probes (e.g. dashcam
     * periodic refresh) where scheduleRead's `markSeen` shortcut
     * would falsely keep the entry fresh without an actual sighting.
     */
    fun launch(name: String, mac: String) {
        val now = System.currentTimeMillis()
        val lastAttempt = attemptInFlight[mac] ?: 0L
        if (now - lastAttempt < ATTEMPT_COOLDOWN_MS) {
            Log.d(TAG, "skip $name (attempt in flight)")
            return
        }
        attemptInFlight[mac] = now
        scope.launch {
            try {
                doReadBattery(name, mac)
            } finally {
                attemptInFlight.remove(mac)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun doReadBattery(name: String, mac: String) {
        val sp = context.getSharedPreferences(PREFS_THROTTLE, Context.MODE_PRIVATE)

        val known = knownDevices.load().toMutableList()
        if (known.none { it.second == mac }) {
            known.removeAll { it.first == name }
            known.add(name to mac)
            knownDevices.save(known)
        }

        val pct = readBattery(mac) ?: run {
            Log.w(TAG, "battery read failed: $name $mac")
            // Count the consecutive failure so the dashcam ticker can back its
            // probe off. Scoped to the dashcam mac (the only one the ticker reads)
            // so this stays in sync with the map's name; only the readBattery==null
            // branch counts - an HA publish failure below is not a read failure.
            if (prefs.dashcamMac.equals(mac, ignoreCase = true)) {
                dashcamProbeFailures[mac] = (dashcamProbeFailures[mac] ?: 0) + 1
            }
            return
        }
        // Successful read: the device answered, so clear any backoff.
        dashcamProbeFailures.remove(mac)
        Log.i(TAG, "battery $name: $pct%")
        val s = slug(name)
        macToSlug[mac] = s
        if (prefs.dashcamMac.equals(mac, ignoreCase = true) && prefs.dashcamDisplayName != name) {
            prefs.dashcamDisplayName = name
        }
        BatteryStateBus.update(BatteryEntry(s, name, pct))

        // Only arm the 5-min throttle if publish succeeded (or HA isn't
        // configured at all). On a transient HA failure we want the next
        // advert to retry within ATTEMPT_COOLDOWN_MS rather than waiting
        // 5 minutes with no HA state update.
        if (haPublisher.publishBatteryToHa(name, pct)) {
            sp.edit().putLong("${KEY_LAST_TS}_$s", System.currentTimeMillis()).apply()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readBattery(mac: String, timeoutMs: Long = 15_000): Int? {
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = btMgr.adapter ?: return null
        if (!adapter.isEnabled) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (_: Throwable) {
            return null
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var done = false
                var gattClosed = false
                var gatt: BluetoothGatt? = null
                fun closeOnce() {
                    if (gattClosed) return
                    gattClosed = true
                    val g = gatt ?: return
                    try {
                        g.disconnect()
                    } catch (_: Throwable) {}
                    try {
                        g.close()
                    } catch (_: Throwable) {}
                }
                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                closeOnce()
                                if (!done) {
                                    done = true
                                    cont.resume(null)
                                }
                            }
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        val ch = g.getService(Uuids.SVC_BATTERY)?.getCharacteristic(Uuids.CHAR_BATTERY)
                        if (ch == null) {
                            g.disconnect()
                            return
                        }
                        g.readCharacteristic(ch)
                    }

                    @Deprecated("API < 33 compat")
                    override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                        @Suppress("DEPRECATION")
                        finishRead(g, ch, ch.value ?: ByteArray(0), status)
                    }

                    override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                        finishRead(g, ch, value, status)
                    }

                    private fun finishRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                        if (ch.uuid == Uuids.CHAR_BATTERY && status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
                            if (!done) {
                                done = true
                                cont.resume(value[0].toInt() and 0xFF)
                            }
                        }
                        g.disconnect()
                    }
                }
                gatt = connectGattLe(context, device, false, cb)
                if (gatt == null) {
                    if (!done) {
                        done = true
                        cont.resume(null)
                    }
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { closeOnce() }
            }
        }
    }

    companion object {
        private const val TAG = "BikeRadar"

        // Minimum gap between battery-read attempts for one device, across all
        // trigger paths (advert sighting, dashcam refresh ticker).
        const val ATTEMPT_COOLDOWN_MS = 30 * 1000L
    }
}
