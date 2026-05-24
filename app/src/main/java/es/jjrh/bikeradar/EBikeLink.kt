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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 *      [BluetoothLeAdvertiser] with service-solicitation carrying [SVC_LDI],
 *      plus a scan response carrying the phone's Bluetooth name so the
 *      bike's "Add accessory" list shows an identifiable entry.
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
 * @param onBondedAddress Invoked once per [start] session when the bond is
 *   confirmed by real LDI data (the [LdiOutcome.Paired] transition), with
 *   the bike's BLE address. NOT fired on a bare inbound connection, so a
 *   passing BLE central is never recorded as the bike. Caller persists the
 *   address (e.g. into Prefs) for the later [releaseBond] reverse-lookup.
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

    private val _outcome = MutableStateFlow<LdiOutcome>(LdiOutcome.Idle)

    /**
     * Live state-machine outcome. Onboarding UIs and Settings -> eBike
     * collect this to render the right card / copy / CTA for the user.
     *
     * Transitions (happy path):
     *   Idle -> Advertising -> Connecting -> Paired
     *
     * Failure transitions:
     *   start() denied (SecurityException)        -> PermissionsDenied
     *   adapter off / advertiser unavailable      -> AdapterUnavailable
     *   GATT services discovered, no LDI service  -> NoServiceFound
     *   GATT disconnect with auth status 137 / 5  -> SlotConflict
     *   90s in Advertising, no inbound LL         -> TimeoutNoInbound
     *   90s in Connecting, no Paired              -> TimeoutPairingNotConfirmed
     *
     * Recovery: callers should invoke [stop] then [start] to reset to
     * Advertising. Same-state-cycle calls are no-ops.
     */
    val outcome: StateFlow<LdiOutcome> = _outcome

    /** True between [start] and [stop] when the advertiser is up. */
    @Volatile
    private var started: Boolean = false

    /** Tracks whether [onBondedAddress] has fired for the current
     *  session. Reset on [stop]. */
    @Volatile
    private var bondedAddressReported: Boolean = false

    /** Most recently observed inbound BLE address, captured at the
     *  STATE_CONNECTED edge on [serverCallback]. Passed into
     *  [LdiOutcome.Paired] when the first valid snapshot arrives so the
     *  UI can render "Paired with bike at <address>" without re-reading
     *  Prefs. Cleared on [stop]. */
    @Volatile
    private var lastInboundAddress: String? = null

    /** Internal scope for the 90s connect-timeout coroutine. */
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Live handle to the 90s timeout job. Cancelled on stop / Paired. */
    @Volatile
    private var connectTimeoutJob: Job? = null

    /**
     * Update the outcome flow and log the transition. Single-source-of-
     * truth helper so every emission also produces a logcat line.
     */
    private fun setOutcome(next: LdiOutcome) {
        val prev = _outcome.value
        if (prev == next) return
        _outcome.value = next
        Log.i(TAG, "outcome: $prev -> $next")
    }

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
            setOutcome(LdiOutcome.AdapterUnavailable)
            return false
        }
        val server = try {
            btManager?.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "openGattServer denied: ${e.message}")
            setOutcome(LdiOutcome.PermissionsDenied)
            return false
        }
        if (server == null) {
            Log.w(TAG, "openGattServer returned null")
            setOutcome(LdiOutcome.AdapterUnavailable)
            return false
        }
        gattServer = server

        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            Log.w(TAG, "bluetoothLeAdvertiser unavailable")
            server.close()
            gattServer = null
            setOutcome(LdiOutcome.AdapterUnavailable)
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

        // Scan response carries the device name so the bike's accessory
        // scan lists an identifiable entry instead of an unnamed address.
        // Android has no API to advertise a custom local name - AdvertiseData
        // only exposes setIncludeDeviceName, which uses the adapter's own
        // name - so the bike shows the phone's Bluetooth name. Kept in the
        // scan response, not the main advert, so the 128-bit solicitation
        // UUID + tx power don't overflow the 31-byte legacy advertisement.
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
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
            adv.startAdvertising(settings, data, scanResponse, cb)
            started = true
            setOutcome(LdiOutcome.Advertising)
            armConnectTimeout()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startAdvertising denied: ${e.message}")
            server.close()
            gattServer = null
            advertiser = null
            advertiseCallback = null
            setOutcome(LdiOutcome.PermissionsDenied)
            false
        }
    }

    /**
     * Arm the 90s connect-timeout. The timer fires once after the start;
     * if the outcome at that point is still Advertising or Connecting the
     * timer publishes the appropriate Timeout. Paired / NoServiceFound /
     * SlotConflict cancel the timer implicitly (they replace the
     * outcome and the timer checks the outcome at fire time).
     *
     * Sized for the observed pair flow: roughly 10 s to reach Connect
     * device in Flow, 20 s for the bike to surface its confirm prompt,
     * 30 s for the rider to press Confirm, 10 s for SMP / MTU /
     * discovery / initial READ. 90 s leaves a margin.
     */
    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = timerScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            when (_outcome.value) {
                LdiOutcome.Advertising -> setOutcome(LdiOutcome.NoInbound)
                LdiOutcome.Connecting -> setOutcome(LdiOutcome.PairPromptDeclined)
                else -> Unit
            }
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
        if (!started && gattServer == null && clientGatt == null) {
            // Already torn down, but a prior start() may have left a
            // terminal failure outcome (AdapterUnavailable etc). Reset
            // to Idle so the UI's collector clears the failure card.
            setOutcome(LdiOutcome.Idle)
            return
        }
        started = false
        bondedAddressReported = false
        lastInboundAddress = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        try { advertiseCallback?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) {}
        advertiseCallback = null
        advertiser = null
        try { clientGatt?.disconnect() } catch (_: Exception) {}
        try { clientGatt?.close() } catch (_: Exception) {}
        clientGatt = null
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        _snapshot.value = LiveDataSnapshot()
        setOutcome(LdiOutcome.Idle)
    }

    /**
     * Tear down all internal scope. Called when the owning service is
     * destroyed permanently. After this the link cannot be restarted;
     * the owner must construct a new EBikeLink.
     */
    fun shutdown() {
        stop()
        timerScope.cancel()
    }

    /**
     * Forget the eBike on this device. Stops the subsystem,
     * deletes the local bond via reflection-based removeBond(), and zeroes
     * the snapshot. The bike's own LDI slot is cleared by the rider in
     * Flow: open the bike -> gear icon -> Components, then remove the
     * accessory.
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
                    // Capture the address but do NOT persist it as the bonded
                    // bike yet - any passing BLE central can connect to our
                    // solicitation advert. We only record the bond once real
                    // LDI data confirms it's the eBike (see [mergeAndPublish]).
                    lastInboundAddress = device.address
                    setOutcome(LdiOutcome.Connecting)
                    try {
                        clientGatt = device.connectGatt(
                            context,
                            /* autoConnect = */ false,
                            clientCallback,
                            BluetoothDevice.TRANSPORT_LE,
                        )
                    } catch (e: SecurityException) {
                        Log.w(TAG, "connectGatt denied: ${e.message}")
                        setOutcome(LdiOutcome.PermissionsDenied)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "inbound LL disconnected from ${device.address}, status=$status")
                    try { clientGatt?.close() } catch (_: Exception) {}
                    clientGatt = null
                    // Don't clear _snapshot - downstream disarm gate
                    // reads system_locked across radar reconnects; the
                    // last-known value remains usable until [stop] zeros
                    // it on shutdown.
                    //
                    // Disconnect status surfaces slot-conflict explicitly.
                    // GATT_INSUFFICIENT_AUTHENTICATION = 137 is the
                    // canonical reading; some vendors return 5. If we
                    // already reached Paired, ignore the disconnect (the
                    // bike may simply be powering down or out of range).
                    if (_outcome.value !is LdiOutcome.Paired) {
                        if (status == STATUS_INSUFFICIENT_AUTH ||
                            status == STATUS_GATT_ERROR_VENDOR_AUTH) {
                            setOutcome(LdiOutcome.SlotConflict)
                        } else if (_outcome.value == LdiOutcome.Connecting) {
                            // Plain disconnect mid-pair without auth status:
                            // bike vanished. Drop back to Advertising so the
                            // 90s timer can attribute correctly.
                            setOutcome(LdiOutcome.Advertising)
                        }
                    }
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
            val ch = if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(SVC_LDI)?.getCharacteristic(CHAR_LIVE_DATA)
            } else {
                null
            }
            if (ch == null) {
                // No usable LDI characteristic on this connection. Stray BLE
                // centrals routinely connect to our solicitation advert and
                // complete discovery with a real (non-LDI) GATT server, so the
                // decision is delegated to the pure [classifyMissingLdi].
                val bonded = try {
                    gatt.device.bondState == BluetoothDevice.BOND_BONDED
                } catch (_: Exception) { false }
                when (classifyMissingLdi(status, bonded)) {
                    MissingLdi.OLD_FIRMWARE -> {
                        Log.w(TAG, "bonded bike lacks LDI service - firmware probably <v19")
                        setOutcome(LdiOutcome.NoServiceFound)
                    }
                    MissingLdi.NOT_THE_BIKE -> {
                        Log.w(TAG, "no LDI on ${gatt.device.address} (status=$status, bonded=$bonded); not the bike, still advertising")
                        try { gatt.close() } catch (_: Exception) {}
                        if (clientGatt === gatt) clientGatt = null
                        if (_outcome.value !is LdiOutcome.Paired) setOutcome(LdiOutcome.Advertising)
                    }
                }
                return
            }
            gatt.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD)?.let { desc -> enableNotifications(gatt, desc) }
            gatt.readCharacteristic(ch)
        }

        /**
         * Subscribe to CHAR_LIVE_DATA notifications by writing the CCCD.
         * API 33+ passes the value as an argument and returns a status
         * code; the descriptor object is no longer mutated, which removes
         * the shared-value race of the legacy two-step form. Pre-33 devices
         * fall back to the deprecated set-then-write path. Best-effort: the
         * write completes asynchronously on the GATT thread - here we only
         * log when the request can't even be queued.
         */
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        private fun enableNotifications(gatt: BluetoothGatt, desc: BluetoothGattDescriptor) {
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
            } else {
                desc.value = value
                gatt.writeDescriptor(desc)
            }
            if (!queued) Log.w(TAG, "CCCD enable write not queued")
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
            val merged = _snapshot.value
            // First snapshot with a non-zero, non-null `time` field
            // confirms a working bond and a bike with a sensible RTC.
            // Transition to Paired once on the rising edge, then leave
            // it - subsequent snapshots are just data refreshes.
            if (_outcome.value !is LdiOutcome.Paired) {
                val timeSec = merged.timeSec
                if (timeSec != null && timeSec > 0L) {
                    val addr = lastInboundAddress ?: ""
                    setOutcome(LdiOutcome.Paired(addr))
                    // Persist the bonded bike address only now - real LDI data
                    // proves this connection is the eBike, not a stray central.
                    if (!bondedAddressReported && addr.isNotEmpty()) {
                        bondedAddressReported = true
                        try { onBondedAddress(addr) } catch (_: Exception) {}
                    }
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                }
            }
            try {
                onSnapshot(merged)
            } catch (e: Exception) {
                Log.w(TAG, "onSnapshot callback threw: ${e.message}")
            }
        }
    }
}

/**
 * State-machine outcome surfaced by [EBikeLink.outcome]. UIs collect
 * this flow to render the right card / copy / CTA. See
 * [EBikeLink.outcome] for the transition table.
 */
sealed class LdiOutcome {
    /** Subsystem not started. Either the experimental flag is off or
     *  [EBikeLink.stop] has been called. */
    object Idle : LdiOutcome()
    /** Advertising with service-solicitation; no eBike has connected
     *  yet this session. */
    object Advertising : LdiOutcome()
    /** Inbound LL connection from the bike, opening the GATT client.
     *  Service discovery and SMP have not yet completed. */
    object Connecting : LdiOutcome()
    /** First non-zero `time` snapshot received. The bond is fully up
     *  and data is flowing.
     *
     *  @param shortAddress the bike's BLE address as observed at the
     *    inbound LL connection. UIs may truncate for display; empty
     *    string when the address was unobservable (defensive default;
     *    in practice the inbound callback populates it before Paired
     *    is emitted). */
    data class Paired(val shortAddress: String) : LdiOutcome()
    /** Service discovery completed without LDI service present. Most
     *  likely the bike's firmware is older than v19.54. */
    object NoServiceFound : LdiOutcome()
    /** GATT disconnected with [STATUS_INSUFFICIENT_AUTH] (137) or
     *  [STATUS_GATT_ERROR_VENDOR_AUTH] (5), i.e. another accessory is
     *  holding the bike's single LDI slot. */
    object SlotConflict : LdiOutcome()
    /** Runtime permission (BLUETOOTH_CONNECT or BLUETOOTH_ADVERTISE)
     *  was denied at the SecurityException level. */
    object PermissionsDenied : LdiOutcome()
    /** BLE adapter is off or the advertiser is null. */
    object AdapterUnavailable : LdiOutcome()
    /** 90s elapsed in Advertising without any inbound connection. The
     *  bike never reached the phone; most likely powered off or out of
     *  range. Distinct from [NoServiceFound] (firmware too old) and
     *  [PairPromptDeclined] (rider rejected on the controller). */
    object NoInbound : LdiOutcome()
    /** 90s elapsed in Connecting without reaching Paired. The bike
     *  connected and discovered the service but the SMP pairing never
     *  completed; most likely the rider declined the confirm prompt
     *  on the bike's controller, or the controller never surfaced it. */
    object PairPromptDeclined : LdiOutcome()
}

/**
 * Disposition when a connected device finishes discovery WITHOUT exposing the
 * LDI characteristic. Extracted as a pure function ([classifyMissingLdi]) so
 * the connection-trust decision is unit-testable without a live BluetoothGatt.
 */
internal enum class MissingLdi { OLD_FIRMWARE, NOT_THE_BIKE }

/**
 * Decide what a missing LDI characteristic means. Only a successful discovery
 * on a BONDED device (the paired eBike) genuinely implies old firmware. A
 * stray unbonded central probing our solicitation advert, or a failed/aborted
 * discovery, is "not the bike" and must never be reported as old firmware.
 */
internal fun classifyMissingLdi(status: Int, bonded: Boolean): MissingLdi =
    if (status == BluetoothGatt.GATT_SUCCESS && bonded) {
        MissingLdi.OLD_FIRMWARE
    } else {
        MissingLdi.NOT_THE_BIKE
    }

/** GATT_INSUFFICIENT_AUTHENTICATION; bike rejected the bond. */
internal const val STATUS_INSUFFICIENT_AUTH: Int = 137

/** Vendor-quirk synonym for auth failure observed on some Bosch
 *  firmware revisions. Kept distinct in case future telemetry shows
 *  it's wrong and we need to drop it without touching the canonical
 *  [STATUS_INSUFFICIENT_AUTH] handling. */
internal const val STATUS_GATT_ERROR_VENDOR_AUTH: Int = 5

/** 90 seconds: pair-flow budget covering Flow navigation, controller
 *  confirm prompt, rider tap, and SMP / MTU / discovery / initial READ. */
internal const val CONNECT_TIMEOUT_MS: Long = 90_000L

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
