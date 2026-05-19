// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Bosch eBike Live Data Interface (LDI) v1.0 client.
 *
 * Wire format and UUIDs from the public Bosch Live Data Interface v1.0
 * specification (Apache-2.0), distributed at
 * https://www.bosch-ebike.com/en/business/live-data-interface . Used
 * here under GPL-3.0-or-later per FSF licence-compatibility guidance
 * (Apache-2.0 -> GPLv3+ one-way compatible). The Apache patent grant
 * survives the relicense; the NOTICE-style attribution is honoured in
 * the in-app licences screen.
 *
 * Topology: the eBike acts as BLE central + GATT server; this client
 * runs as BLE peripheral + GATT client on the phone. Pairing flow:
 *
 *   1. [start] registers a minimal [BluetoothGattServer] (no hosted
 *      services - we need the callback only, not a service) and starts
 *      [BluetoothLeAdvertiser] with service-solicitation carrying [SVC_LDI].
 *   2. The eBike sees the solicitation, connects as central. Android
 *      fires [BluetoothGattServerCallback.onConnectionStateChange] on
 *      the empty server.
 *   3. We immediately call [BluetoothDevice.connectGatt] on the inbound
 *      device to bind a GATT client to the established LL link.
 *   4. Pair (Just-Works LESC, NoInputNoOutput, bonded - Android handles
 *      this on the platform side; the user confirms on the bike's
 *      controller, not the phone).
 *   5. Request MTU 247, discover services, find [CHAR_LIVE_DATA], write
 *      0x0001 to its CCCD.
 *   6. The eBike sends an initial READ with the full snapshot; subsequent
 *      NOTIFYs contain only changed fields. We merge into [_snapshot].
 *
 * One-slot constraint: the eBike maintains a single LDI bonding slot.
 * If a Garmin Edge or sports watch is bonded first, we get
 * GATT_INSUFFICIENT_AUTHENTICATION. The rider then has the choice of
 * unpairing their other accessory in Flow, or leaving the LDI slot to
 * the other device and disabling [ldiEnabled] in Settings.
 *
 * Graceful degradation: when the feature flag is off, [start] is never
 * called; [snapshot] stays at its initial empty value (all fields null).
 * Downstream consumers (AlertDecider stationary override, walk-away
 * disarm gate) treat null fields as "no LDI signal available" and fall
 * back to their existing GPS-derived paths.
 *
 * @param context Service context for BLE system services. Held weakly
 *   only via direct system-service lookups; this class does not retain
 *   the context beyond its constructor and start/stop calls.
 * @param onSnapshot Invoked on the BLE callback thread for every NOTIFY
 *   merge AND for the initial READ snapshot. Callers must marshal to the
 *   main thread if they touch UI.
 * @param onBondedAddress Invoked once per [start] session on the first
 *   inbound LL connection, with the bike's BLE address. Caller persists
 *   it (e.g. into Prefs) for the later [releaseBond] reverse-lookup.
 *   Invoked on the BLE callback thread.
 */
class EBikeLink(
    private val context: Context,
    private val onSnapshot: (LiveDataSnapshot) -> Unit = {},
    private val onBondedAddress: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "BikeRadar.EBikeLink"

        // From the public Bosch LDI v1.0 spec, §2 UUIDs.
        val SVC_LDI: UUID = UUID.fromString("0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4")
        val CHAR_LIVE_DATA: UUID = UUID.fromString("0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // BLE appearance 0x0480 = "Generic Cycling" per Bluetooth assigned
        // numbers. Bridge author chose this and the spec doesn't constrain.
        private const val APPEARANCE_CYCLING: Int = 0x0480
    }

    private val btManager = context.getSystemService(BluetoothManager::class.java)
    private val btAdapter: BluetoothAdapter? = btManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var clientGatt: BluetoothGatt? = null

    private val _snapshot = MutableStateFlow(LiveDataSnapshot())

    /** Live LDI snapshot; null fields = "not yet observed". */
    val snapshot: StateFlow<LiveDataSnapshot> = _snapshot

    /** True between [start] and [stop] when the advertiser is up. */
    @Volatile
    private var started: Boolean = false

    /** Tracks whether [onBondedAddress] has fired for the current
     *  session. Reset on [stop]. */
    @Volatile
    private var bondedAddressReported: Boolean = false

    /**
     * Begin advertising for the eBike to connect. Idempotent: a second
     * call while already started is a no-op. Returns true if advertising
     * has started or was already up, false if the BLE adapter is off /
     * unavailable.
     */
    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    ])
    fun start(): Boolean {
        if (started) return true
        val adapter = btAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "BLE adapter unavailable or disabled; cannot start")
            return false
        }
        val server = try {
            btManager?.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "openGattServer denied: ${e.message}")
            return false
        }
        if (server == null) {
            Log.w(TAG, "openGattServer returned null")
            return false
        }
        gattServer = server

        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            Log.w(TAG, "bluetoothLeAdvertiser unavailable")
            server.close()
            gattServer = null
            return false
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            // LOW_POWER throughout: rider takes 1-2 minutes to set up the
            // bike, so a 5-10 s connect-time window is well within the
            // pre-ride budget. Battery savings win.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(SVC_LDI))
            .setIncludeTxPowerLevel(true)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "advertising started")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertising failed: errorCode=$errorCode")
            }
        }
        advertiseCallback = cb

        return try {
            adv.startAdvertising(settings, data, cb)
            started = true
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startAdvertising denied: ${e.message}")
            server.close()
            gattServer = null
            advertiser = null
            advertiseCallback = null
            false
        }
    }

    /**
     * Stop advertising, close GATT client and server. Idempotent.
     */
    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    ])
    fun stop() {
        if (!started && gattServer == null && clientGatt == null) return
        started = false
        bondedAddressReported = false
        try { advertiseCallback?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) {}
        advertiseCallback = null
        advertiser = null
        try { clientGatt?.disconnect() } catch (_: Exception) {}
        try { clientGatt?.close() } catch (_: Exception) {}
        clientGatt = null
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        _snapshot.value = LiveDataSnapshot()
    }

    /**
     * Forget the eBike on this device. Stops the subsystem,
     * deletes the local bond via reflection-based removeBond(), and zeroes
     * the snapshot. The bike's own LDI slot is cleared by the rider
     * through Flow -> System -> Accessories -> Remove.
     *
     * WARNING: removeBond() is a hidden-API call on Android's deny-list;
     * subject to break in a future Android release. On any reflection
     * failure, [onFallback] is invoked so the caller can surface the
     * manual fallback path ("Forget the bike via Android Settings ->
     * Bluetooth -> Saved devices") to the rider.
     *
     * @param bondedAddress The address persisted at first bond; usually
     *   read from Prefs by the caller. Pass null to skip the removeBond
     *   step (useful when no bond has ever been established - the call
     *   is then just a stop + zero-snapshot).
     * @param onFallback Invoked when reflection-based removeBond fails or
     *   when the device isn't a bonded peer (the rider should be told to
     *   use Android Settings instead).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun releaseBond(bondedAddress: String?, onFallback: () -> Unit = {}) {
        stop()
        if (bondedAddress == null) return
        val device = try {
            btAdapter?.getRemoteDevice(bondedAddress)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid bonded address $bondedAddress: ${e.message}")
            onFallback()
            return
        }
        if (device == null) {
            onFallback()
            return
        }
        // Guard against calling removeBond on a non-bonded device - the
        // bond may have been cleared from system Settings already.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "no bond to release for $bondedAddress")
            return
        }
        try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device)
            Log.i(TAG, "removeBond invoked for $bondedAddress")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "removeBond hidden-API missing: ${e.message}")
            onFallback()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            Log.w(TAG, "removeBond threw: ${e.targetException?.message}")
            onFallback()
        } catch (e: IllegalAccessException) {
            Log.w(TAG, "removeBond access denied: ${e.message}")
            onFallback()
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "inbound LL connection from ${device.address}, opening GATT client")
                    if (!bondedAddressReported) {
                        bondedAddressReported = true
                        try {
                            onBondedAddress(device.address)
                        } catch (_: Exception) {}
                    }
                    try {
                        clientGatt = device.connectGatt(
                            context,
                            /* autoConnect = */ false,
                            clientCallback,
                            BluetoothDevice.TRANSPORT_LE,
                        )
                    } catch (e: SecurityException) {
                        Log.w(TAG, "connectGatt denied: ${e.message}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "inbound LL disconnected from ${device.address}")
                    try { clientGatt?.close() } catch (_: Exception) {}
                    clientGatt = null
                    // Don't clear _snapshot - downstream disarm gate
                    // reads system_locked across radar reconnects; the
                    // last-known value remains usable until [stop] zeros
                    // it on shutdown.
                }
            }
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(247)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(SVC_LDI)
            if (svc == null) {
                Log.w(TAG, "LDI service not advertised - firmware probably <v19")
                return
            }
            val ch = svc.getCharacteristic(CHAR_LIVE_DATA) ?: run {
                Log.w(TAG, "LDI characteristic not found")
                return
            }
            gatt.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
            gatt.readCharacteristic(ch)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (ch.uuid != CHAR_LIVE_DATA) return
            mergeAndPublish(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid != CHAR_LIVE_DATA) return
            mergeAndPublish(value)
        }

        private fun mergeAndPublish(value: ByteArray) {
            _snapshot.update { LiveDataDecoder.mergeInto(it, value) }
            try {
                onSnapshot(_snapshot.value)
            } catch (e: Exception) {
                Log.w(TAG, "onSnapshot callback threw: ${e.message}")
            }
        }
    }
}

/**
 * Running snapshot of LDI fields. Nullable means "not yet observed"; the
 * decoder merges, preserving any field absent from the current payload.
 * Field numbers map to the Bosch LDI v1.0 spec proto3 tags.
 */
data class LiveDataSnapshot(
    /** #1, raw 1/100 km/h. Compute m/s as `raw / 360f`. */
    val speedRaw: Int? = null,
    /** #2, rpm. Two's-complement signed (NOT sint32 zig-zag). */
    val cadence: Int? = null,
    /** #5, watts (rider effort only, NOT motor assist). */
    val riderPower: Int? = null,
    /** #9, raw 1/1000 lux. Compute lux as `raw / 1000f`. */
    val ambientBrightnessRaw: Int? = null,
    /** #10, percent 0-100, drive-system battery. */
    val batterySoc: Int? = null,
    /** #11, seconds since epoch (NOT ms). */
    val timeSec: Long? = null,
    /** #12, raw metres (NOT km). Always log delta-since-session-start;
     *  never absolute. Rider-identifying under GDPR Recital 30. */
    val odometerM: Long? = null,
    /** #17, enum: 0=invalid, 1=off, 2=on. */
    val bikeLight: Int? = null,
    /** #21, true when the eBike's anti-theft lock is engaged. */
    val systemLocked: Boolean? = null,
    /** #22, true when the mains charger is plugged in. */
    val chargerConnected: Boolean? = null,
    /** #23, true when the bike has cut headlight power to protect the
     *  drive battery (rider just lost their primary front light). */
    val lightReserve: Boolean? = null,
    /** #24, true when a dealer service tool is connected. */
    val diagnosisActive: Boolean? = null,
    /** #25, true when the wheel is at rest. Ground-truth standstill. */
    val bikeNotDriving: Boolean? = null,
)

/**
 * Hand-rolled proto3 varint decoder for the Bosch LDI LiveData message.
 * Pure Kotlin; no protobuf-javalite dependency (zero new ProGuard/R8
 * surface, no annotation-processor cost). NOTIFY payloads carry only the
 * changed fields per proto3 field presence semantics; merge preserves
 * every previously-seen value absent from the current payload.
 */
object LiveDataDecoder {

    /**
     * Merge a wire-format payload into [prev], returning the updated
     * snapshot. On a malformed frame (truncated varint, unrecognised wire
     * type), returns [prev] unchanged - the caller keeps the prior known-
     * good snapshot rather than committing partial state.
     */
    fun mergeInto(prev: LiveDataSnapshot, bytes: ByteArray): LiveDataSnapshot {
        var next = prev
        var i = 0
        try {
            while (i < bytes.size) {
                val (tagWire, after) = readVarint(bytes, i)
                i = after
                val field = (tagWire ushr 3).toInt()
                when ((tagWire and 7L).toInt()) {
                    0 -> {
                        // varint payload
                        val (v, end) = readVarint(bytes, i); i = end
                        next = when (field) {
                            1  -> next.copy(speedRaw = v.toInt())
                            2  -> next.copy(cadence = v.toInt())
                            5  -> next.copy(riderPower = v.toInt())
                            9  -> next.copy(ambientBrightnessRaw = v.toInt())
                            10 -> next.copy(batterySoc = v.toInt())
                            11 -> next.copy(timeSec = v)
                            12 -> next.copy(odometerM = v)
                            17 -> next.copy(bikeLight = v.toInt())
                            21 -> next.copy(systemLocked = v != 0L)
                            22 -> next.copy(chargerConnected = v != 0L)
                            23 -> next.copy(lightReserve = v != 0L)
                            24 -> next.copy(diagnosisActive = v != 0L)
                            25 -> next.copy(bikeNotDriving = v != 0L)
                            else -> next   // forward-compat: skip unknown tags
                        }
                    }
                    2 -> {
                        // length-delimited: read length varint, skip payload
                        val (len, end) = readVarint(bytes, i); i = end + len.toInt()
                        if (i > bytes.size) return prev
                    }
                    1 -> i += 8   // 64-bit fixed
                    5 -> i += 4   // 32-bit fixed
                    else -> return prev   // malformed; keep prior snapshot
                }
            }
        } catch (_: IllegalArgumentException) {
            return prev
        }
        return next
    }

    /**
     * Read one varint starting at [start]. Returns the value and the
     * index just past the varint's last byte. Throws
     * [IllegalArgumentException] on truncated input or a varint exceeding
     * 64 bits (10 bytes).
     */
    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int> {
        var v = 0L
        var shift = 0
        var i = start
        while (true) {
            require(i < b.size) { "varint truncated" }
            require(shift < 64) { "varint > 64 bits" }
            val byte = b[i].toInt() and 0xff
            v = v or ((byte and 0x7f).toLong() shl shift)
            i++
            if (byte and 0x80 == 0) return v to i
            shift += 7
        }
    }
}
