// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Read-only reader for the Bosch smart-system proprietary live-data stream.
 *
 * The official eBike (eb20/eb21) cannot work on a phone that also runs Bosch
 * Flow: eBike needs the bike to be GAP central to the phone, but Flow holds the
 * single phone<->bike link as phone-central (BLE allows one link per device
 * pair). So instead we attach a GATT client to that existing bonded link and
 * subscribe to the same proprietary status characteristic Flow uses
 * ([Uuids.CHAR_EBIKE_STATUS]). Android ref-counts notification subscriptions
 * per client, so the bike's datapoint stream fans out to us too - **with no
 * extra write reaching the bike and no interference with Flow.**
 *
 * Strictly read-only: the only GATT write is the standard CCCD enable
 * (`0x2902 = 0100`). We never write to the bike's command channel (the data
 * only flows because Flow is also running and polling). Frames are parsed by
 * [EBikeStatusDecoder] into the shared [LiveDataSnapshot]; [onSnapshot] feeds
 * the same downstream pipeline as the (now-defunct) eb21 path.
 *
 * Mirrors the camera-light connection pattern in [BikeRadarService]: one
 * [BleOpQueue], a forwarding [BluetoothGattCallback], `connectGatt`
 * (autoConnect = true so it idles until the bonded bike + Flow are up), and a
 * reconnect loop with backoff.
 */
class EBikeStatusReader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mac: String,
    private val onSnapshot: (LiveDataSnapshot) -> Unit,
    private val log: (String) -> Unit = {},
    /** Optional sink for marker-`0x30` records whose object ID is not yet
     *  mapped in [EBikeStatusDecoder]. Null (default) skips extraction
     *  entirely; when non-null the reader extracts and forwards per frame,
     *  and the caller is expected to gate the actual side effect (so the
     *  decision to log can be live-toggleable without rebuilding the
     *  reader). The service wires this for the Debug "log unknown eBike
     *  object IDs" pinning workflow. */
    private val onUnknownRecord: ((Int, Long) -> Unit)? = null,
) {
    private var loopJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (loopJob != null) return
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val device = try {
            mgr?.adapter?.getRemoteDevice(mac)
        } catch (_: Throwable) {
            // SecurityException if BLUETOOTH_CONNECT was revoked between
            // hasBlePermissions() and here; adapter may also be off. Bail to
            // graceful no-op - the caller treats null as "no eBike present".
            null
        }
        if (device == null) {
            // No MAC in logs - it's device-identifying. The address came from
            // the bonded-device list, so a null here means no adapter.
            Log.w(TAG, "no adapter / bad eBike address; not starting")
            return
        }
        loopJob = scope.launch { runLoop(device) }
    }

    fun shutdown() {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun runLoop(device: BluetoothDevice) {
        // Same reconnect schedule as the radar + camera loops via the shared,
        // tested ReconnectLoopPlanner. A subscribed session is the quick-reconnect
        // + backoff-reset path; a failed attempt jitters the current backoff and
        // then doubles it toward the cap. The eBike loop tracks no off-instant, so
        // it passes offSinceMs = null (no long-offline relaxation; autoConnect
        // idles the link between attempts anyway).
        var backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
        while (true) {
            log("eBike status: connect attempt")
            val subscribed = connectAndRun(device)
            val delayMs = ReconnectLoopPlanner.nextDelayMs(backoffMs, quickReconnect = subscribed)
            backoffMs = if (subscribed) {
                RADAR_RECONNECT_BACKOFF_INITIAL_MS
            } else {
                ReconnectLoopPlanner.grow(
                    backoffMs = backoffMs,
                    nowMs = 0L,
                    offSinceMs = null,
                    longOfflineThresholdMs = 0L,
                    longOfflineCapMs = 0L,
                )
            }
            delay(delayMs)
        }
    }

    /** Returns true if we reached a subscribed/streaming state before the link
     *  dropped (so the caller reconnects quickly), false on a failed attempt. */
    @SuppressLint("MissingPermission")
    private suspend fun connectAndRun(device: BluetoothDevice): Boolean {
        val notifyChannel = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        val queue = BleOpQueue()
        val servicesReady = CompletableDeferred<Boolean>()
        var gatt: BluetoothGatt? = null
        var gattClosed = false
        fun closeOnce() {
            if (gattClosed) return
            gattClosed = true
            val g = gatt ?: return
            // Best-effort teardown of a possibly-failing GATT: any throw here
            // is benign (the link is on its way down regardless) and must not
            // propagate, or we'd skip the close() below and leak the handle.
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
                        queue.cancel()
                        notifyChannel.close()
                        if (!servicesReady.isCompleted) servicesReady.complete(false)
                        closeOnce()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (!servicesReady.isCompleted) servicesReady.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                queue.onDescriptorWrite(d, status)
            }

            @Deprecated("API < 33 compat")
            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                queue.onCharacteristicRead(ch, ch.value ?: ByteArray(0), status)
            }

            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                queue.onCharacteristicRead(ch, value, status)
            }

            @Deprecated("API < 33 compat")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                notifyChannel.trySend(ch.uuid to (ch.value ?: ByteArray(0)))
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                notifyChannel.trySend(ch.uuid to value)
            }
        }

        gatt = connectGattLe(context, device, true, cb)
        if (gatt == null) {
            Log.w(TAG, "connectGatt returned null")
            return false
        }
        val queueJob = scope.launch { queue.run() }

        return try {
            if (!servicesReady.await()) {
                log("eBike status: service discovery failed")
                return false
            }
            val ch = gatt.getService(Uuids.SVC_EBIKE_STATUS)?.getCharacteristic(Uuids.CHAR_EBIKE_STATUS)
            if (ch == null) {
                // Bike not present, or Flow hasn't established the proprietary
                // service yet. Retry on backoff.
                log("eBike status: status characteristic not found")
                return false
            }
            if (!queue.writeCccd(gatt, ch)) {
                log("eBike status: CCCD subscribe failed")
                return false
            }
            log("eBike status: subscribed; streaming")

            // Read-only steady state: parse each notification into the running
            // snapshot and publish. The bike pushes datapoints (driven by
            // Flow's polling); we only listen. Loop ends when the link drops
            // (notifyChannel closed in onConnectionStateChange).
            var snapshot = LiveDataSnapshot()
            for ((uuid, value) in notifyChannel) {
                if (uuid != Uuids.CHAR_EBIKE_STATUS) continue
                snapshot = EBikeStatusDecoder.mergeInto(snapshot, value)
                onSnapshot(snapshot)
                onUnknownRecord?.let { sink ->
                    EBikeStatusDecoder.extractUnknownObjectIds(value)
                        .forEach { (objId, v) -> sink(objId, v) }
                }
            }
            true
        } catch (e: CancellationException) {
            // Cooperative cancellation (shutdown()) - structured concurrency
            // says re-throw so the launching scope sees the cancellation, not
            // a normal return. The finally below still tears the GATT down.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "eBike status read loop error: ${e.message}")
            false
        } finally {
            queueJob.cancel()
            queue.cancel()
            closeOnce()
        }
    }

    companion object {
        private const val TAG = "BikeRadar.EBikeStatus"

        /**
         * Find the bonded eBike's BLE address among the adapter's bonded
         * devices by name. The bike advertises its local name as
         * "smart system eBike"; match loosely so minor naming variants still
         * resolve. Returns null when no bonded eBike is present (e.g. the rider
         * has no Bosch bike) so the caller skips starting the reader.
         */
        @SuppressLint("MissingPermission")
        fun findBondedEBikeMac(context: Context): String? {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bonded = try {
                mgr?.adapter?.bondedDevices
            } catch (_: Throwable) {
                // SecurityException without BLUETOOTH_CONNECT, or adapter off.
                // Return null so the caller skips starting the reader.
                null
            } ?: return null
            return bonded.firstOrNull { dev ->
                val name = try {
                    dev.name
                } catch (_: Throwable) {
                    // Per-device permission can revoke between bondedDevices
                    // and dev.name. Skip this entry, keep scanning the rest.
                    null
                } ?: ""
                name.contains("ebike", ignoreCase = true) || name.contains("smart system", ignoreCase = true)
            }?.address
        }
    }
}
