// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * [launch] when a read is due; this class owns only the in-flight guard and
 * the actual GATT read. The dashcam-probe-failure counter is shared with the
 * service's dashcam-refresh loop, and the instant the selected dashcam last
 * answered with the walk-away alarm, so both are injected rather than owned.
 *
 * The HA publish ([publishBattery]) and the GATT read ([readBatteryFn]) are
 * injected as function seams: production wires them to the real
 * [HaPublisher.publishBatteryIfChanged] and the private [readBattery], while tests
 * pass fakes so the whole post-read path (known-device upsert, failure counter,
 * display-name sync, publish-gated throttle) is exercisable without a device.
 * The clocks are injected so a test can run reads in virtual time.
 */
@SuppressLint("MissingPermission")
internal class BatteryReader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val knownDevices: KnownDevices,
    @get:androidx.annotation.VisibleForTesting
    internal val publishBattery: suspend (String, Int) -> Boolean,
    private val macToSlug: MutableMap<String, String>,
    private val slug: (String) -> String,
    private val dashcamProbeFailures: MutableMap<String, Int>,
    /** Monotonic instant each dashcam last answered a read, by upper-case
     *  address: when the read ended, not when the bus entry was built. */
    @get:androidx.annotation.VisibleForTesting
    internal val dashcamAnsweredAtMs: MutableMap<String, Long>,
    /** Called as that instant is written, so the walk-away tick can see the
     *  hearing while the answer's callback still has the phone awake. */
    @get:androidx.annotation.VisibleForTesting
    internal val onDashcamAnswered: () -> Unit,
    private val readBatteryFn: (suspend (String) -> Int?)? = null,
    private val monoClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    /** Opens the read's GATT client; a test drives its callbacks
     *  (`BatteryReaderGattTest`). */
    private val connectGatt: (BluetoothDevice, BluetoothGattCallback) -> BluetoothGatt? =
        { device, cb -> connectGattLe(context, device, false, cb) },
) {
    // Per-mac in-flight guard, holding a token per read so one read's teardown
    // never clears a later read's guard (`aLaunchInTheSameMillisecondIsGuardedToo`).
    private val attemptInFlight = ConcurrentHashMap<String, Any>()

    /**
     * Real GATT battery read, bypassing the SharedPrefs throttle but skipped
     * while a read of the same device is in flight, so fires from different
     * paths don't stack. Use this for liveness probes (e.g. dashcam
     * periodic refresh) where scheduleRead's `markSeen` shortcut
     * would falsely keep the entry fresh without an actual sighting. Returns
     * whether the read started.
     *
     * The guard ends with the GATT read, not the HA publish after it, so the
     * dashcam alarm's armed probe can start the next read at once; two
     * publishes for one device can then overlap
     * (`aSlowPublishDoesNotHoldTheNextRead`), and an older value can land
     * after a newer one until the value next changes or the 5 min heartbeat
     * sends it again.
     */
    fun launch(name: String, mac: String): Boolean {
        val token = Any()
        if (attemptInFlight.putIfAbsent(mac, token) != null) {
            Log.d(TAG, "skip $name (attempt in flight)")
            return false
        }
        scope.launch {
            try {
                doReadBattery(name, mac) { attemptInFlight.remove(mac, token) }
            } finally {
                attemptInFlight.remove(mac, token)
            }
        }
        return true
    }

    /** A radar, or the camera the rider is using. Nothing else the name matcher
     *  lets through is read here: a camera switched off, cleared, replaced or
     *  never chosen, or any other accessory with a name the app recognises.
     *  Stated as what MAY be read so that a new way of not choosing a camera
     *  is refused by default.
     *
     *  The radar test is [DeviceNameMatcher.isUnambiguousRadar] and no wider,
     *  because that is the test the dashcam picker excludes on, so no name
     *  both passes here and is offered there. The wider `isRearAdvert` would
     *  read a switched-off camera called "RearView". Pinned by
     *  aSwitchedOffCameraWithARadarLikeNameIsNotRead here and by
     *  noNameIsBothOfferedAsTheCameraAndReadAsARadar across the two files. */
    private fun mayRead(name: String, mac: String): Boolean = DeviceNameMatcher.isUnambiguousRadar(name) ||
        prefs.radarMac.equals(mac, ignoreCase = true) ||
        prefs.activeDashcamMac.equals(mac, ignoreCase = true)

    @SuppressLint("MissingPermission")
    internal suspend fun doReadBattery(name: String, mac: String, onReadEnded: () -> Unit = {}) {
        // Devices reach this path by advert NAME, so nothing upstream has
        // consulted what the rider chose and the refusal has to be here. Before
        // the GATT read, not after: the point is that this read stops touching
        // the device, not that it stops reporting what it found.
        if (!mayRead(name, mac)) {
            Log.d(TAG, "skip $name (not a radar, and not the camera in use)")
            return
        }

        val sp = context.getSharedPreferences(PREFS_THROTTLE, Context.MODE_PRIVATE)

        val known = knownDevices.load().toMutableList()
        if (known.none { it.second == mac }) {
            known.removeAll { it.first == name }
            known.add(name to mac)
            knownDevices.save(known)
        }

        val readFn = readBatteryFn ?: { m -> readBattery(m) }
        val read = readFn(mac)
        val answeredAtMs = monoClock()
        onReadEnded()
        val pct = read ?: run {
            Log.w(TAG, "battery read failed: $name ${LogRedaction.mac(mac)}")
            // Count the consecutive failure so the dashcam ticker can back its
            // probe off. Scoped to the dashcam mac (the only one the ticker reads)
            // so this stays in sync with the map's name; only the readBattery==null
            // branch counts - an HA publish failure below is not a read failure.
            if (prefs.activeDashcamMac.equals(mac, ignoreCase = true)) {
                dashcamProbeFailures[mac] = (dashcamProbeFailures[mac] ?: 0) + 1
            }
            return
        }
        // Successful read: the device answered, so clear any backoff.
        dashcamProbeFailures.remove(mac)
        Log.d(TAG, "battery $name: $pct%")
        val s = slug(name)
        macToSlug[mac] = s
        if (prefs.activeDashcamMac.equals(mac, ignoreCase = true)) {
            dashcamAnsweredAtMs[mac.uppercase(Locale.ROOT)] = answeredAtMs
            onDashcamAnswered()
            if (prefs.dashcamDisplayName != name) prefs.dashcamDisplayName = name
        }
        BatteryStateBus.update(BatteryEntry(s, name, pct, readAtMs = wallClock(), lastSeenElapsedMs = answeredAtMs))

        // Only arm the 5-min throttle if publish succeeded, had nothing new to
        // send, or HA isn't configured at all. On a transient HA failure the
        // next advert retries rather than waiting 5 minutes with no HA state
        // update.
        if (publishBattery(name, pct)) {
            sp.edit().putLong("${KEY_LAST_TS}_$s", wallClock()).apply()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readBattery(mac: String, timeoutMs: Long = READ_TIMEOUT_MS): Int? {
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
                // The callbacks run on a binder thread and can end the read
                // before connectGatt has returned the client.
                val done = AtomicBoolean(false)
                fun finish(pct: Int?) {
                    if (done.compareAndSet(false, true)) cont.resume(pct)
                }
                val gattClose = CloseOnce<BluetoothGatt> { g ->
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
                                gattClose.close()
                                finish(null)
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
                            finish(value[0].toInt() and 0xFF)
                        }
                        g.disconnect()
                    }
                }
                val gatt = connectGatt(device, cb)
                if (gatt == null) {
                    finish(null)
                    return@suspendCancellableCoroutine
                }
                gattClose.attach(gatt)
                cont.invokeOnCancellation { gattClose.close() }
            }
        }
    }

    companion object {
        private const val TAG = "BikeRadar"

        /** The longest one GATT battery read waits for an answer. */
        const val READ_TIMEOUT_MS = 15_000L
    }
}

/**
 * Closes a client exactly once, whichever comes first: the close, or the
 * client being known. A close asked for before [attach] closes the client as
 * it is attached, so a read ended before `connectGatt` returned does not leak
 * it (`CloseOnceTest`).
 */
internal class CloseOnce<T : Any>(private val closeFn: (T) -> Unit) {
    private var target: T? = null
    private var closed = false

    fun attach(t: T) {
        val closeNow = synchronized(this) {
            target = t
            closed
        }
        if (closeNow) closeFn(t)
    }

    fun close() {
        val t = synchronized(this) {
            if (closed) return
            closed = true
            target
        } ?: return
        closeFn(t)
    }
}
