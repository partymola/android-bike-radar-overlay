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
import android.os.SystemClock
import android.util.Log
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the front camera/light BLE link: the start/reconnect lifecycle, the GATT
 * connect -> AMV handshake (FRONT_CAMERA variant) -> mode-state-notify loop, and
 * the time-of-day light auto-mode (the same pure [LightAutoModeDecider] the
 * radar tail-light uses). A parallel, near-mirror of [RadarLinkController];
 * unlike the radar it owns no shared service state - its only outward read is
 * the radar's off-time [radarOffSinceMs], used as the "bike parked" proxy for
 * the long-offline reconnect-backoff cap (shared with the radar loop).
 *
 * The service keeps `scope`; this controller launches its connection coroutine
 * on the injected scope, so the service's onDestroy scope.cancel() tears it down.
 * Optional accessory: a rider with no Vue front camera simply never has this
 * link start (gated by the dashcam pairing + autoLightModeEnabled).
 */
@SuppressLint("MissingPermission")
internal class CameraLightLinkController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    /** Provider, not an instance - see OverlayPipeline: the service
     *  rebuilds its HaClient when stored credentials change. */
    private val ha: () -> HaClient,
    private val haPublisher: HaPublisher,
    private val notifications: ServiceNotifications,
    private val macToSlug: MutableMap<String, String>,
    private val slug: (String) -> String,
    private val radarOffSinceMs: () -> Long?,
    /** Always-on link-event sink ([LinkEventJournal]); this link has no
     *  capture log at all, so the journal is its only persistent trace. */
    private val journal: (String) -> Unit,
    /** Monotonic clock for the camera-light override deadband; elapsedRealtime
     *  so a wall-clock jump can't mis-clear the rider's manual override. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    // The connection coroutine; single-slot, guarded by start()'s @Synchronized.
    @Volatile private var cameraLightJob: Job? = null

    @Volatile private var cameraLightGattActive = false

    @Volatile private var cameraLightUserOverride = false

    @Volatile private var cameraLightLastWrittenMode: CameraLightMode? = null

    @Volatile private var cameraLightOffSinceMs: Long? = null

    private val frontModeDiscoveredSlugs = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun start(name: String, mac: String) {
        if (cameraLightJob?.isActive == true) return
        Log.i(TAG, "starting camera light link to $name $mac")
        journal("camera link start $name")
        cameraLightJob = scope.launch { runCameraLightConnection(mac, name) }
    }

    /** True while the connection coroutine is live. */
    fun isActive(): Boolean = cameraLightJob?.isActive == true

    /** True while a front-camera GATT link is up (post service-discovery). */
    fun isGattActive(): Boolean = cameraLightGattActive

    /** Cancel the connection coroutine on service destroy (scope.cancel() would
     *  also stop it; this matches the original explicit onDestroy cancel). */
    fun stop() {
        cameraLightJob?.cancel()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runCameraLightConnection(mac: String, name: String) {
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val device = try {
            btMgr.adapter?.getRemoteDevice(mac)
        } catch (_: Throwable) {
            null
        } ?: return

        var backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
        try {
            while (true) {
                if (!prefs.autoLightModeEnabled) {
                    Log.i(TAG, "auto light mode disabled; exiting link")
                    return
                }
                Log.i(TAG, "connect attempt to $name $mac")
                val quickReconnect = connectAndRunCameraLight(device, name)
                cameraLightGattActive = false
                val delayMs = ReconnectLoopPlanner.nextDelayMs(backoffMs, quickReconnect)
                Log.i(TAG, "reconnecting in ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
                backoffMs = if (!quickReconnect) {
                    ReconnectLoopPlanner.grow(
                        backoffMs = backoffMs,
                        nowMs = clock(),
                        offSinceMs = radarOffSinceMs(),
                        longOfflineThresholdMs = prefs.radarLongOfflineThresholdMinutes * 60_000L,
                        longOfflineCapMs = prefs.radarLongOfflineCapSec * 1_000L,
                    )
                } else {
                    RADAR_RECONNECT_BACKOFF_INITIAL_MS
                }
            }
        } finally {
            cameraLightGattActive = false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndRunCameraLight(device: BluetoothDevice, name: String): Boolean {
        val notifyChannel = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        val queue = BleOpQueue()
        val servicesReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var gatt: BluetoothGatt? = null
        var gattClosed = false
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
                journal("camera conn state status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        cameraLightGattActive = false
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

            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                queue.onCharacteristicWrite(ch, status)
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                queue.onMtuChanged(mtu, status)
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
            journal("camera connectGatt returned null")
            return false
        }

        val queueJob = scope.launch { queue.run() }

        var sunsetJob: Job? = null
        var sunriseJob: Job? = null
        return try {
            val ok = servicesReady.await()
            if (!ok) {
                Log.w(TAG, "service discovery failed")
                journal("camera services discovery failed")
                return false
            }

            cameraLightGattActive = true
            val offSince = cameraLightOffSinceMs
            if (CameraLightOverrideDecider.shouldClearOverride(offSince, clock(), CAMERA_LIGHT_OVERRIDE_DEADBAND_MS)) {
                cameraLightUserOverride = false
                Log.i(TAG, "override cleared after ${(clock() - (offSince ?: 0)) / 1000}s off")
            }
            cameraLightOffSinceMs = null
            Log.i(TAG, "connected, running handshake")

            val handshakeOk = RadarUnlock.runHandshake(
                gatt,
                queue,
                notifyChannel,
                DeviceVariant.FRONT_CAMERA,
            ) { msg -> Log.d(TAG, msg) }

            if (!handshakeOk) {
                Log.w(TAG, "handshake failed; closing for quick reconnect")
                journal("camera handshake failed (quick reconnect)")
                gatt.disconnect()
                return true
            }

            Log.i(TAG, "handshake complete; subscribing mode-state notify")
            journal("camera handshake complete")
            // Refresh the location cache for sunrise/sunset before scheduling
            // the dusk/dawn flips below. Idempotent with the radar-side
            // refresh: if either device handshook first, the cache is warm.
            LocationCache.refreshIfStale(context)
            val ch14 = gatt.getService(Uuids.SVC_CONTROL)?.getCharacteristic(Uuids.SETTINGS_14)
            if (ch14 != null) queue.writeCccd(gatt, ch14)

            val lightSlugEarly = slug(name)
            val lightMacEarly = gatt.device?.address
            if (lightMacEarly != null) macToSlug[lightMacEarly] = lightSlugEarly
            if (ha().isConfigured() && frontModeDiscoveredSlugs.add(lightSlugEarly)) {
                if (!ha().publishFrontModeDiscovery(lightSlugEarly, name)) {
                    frontModeDiscoveredSlugs.remove(lightSlugEarly)
                }
            }

            val controller = CameraLightController(gatt, queue)
            val nowMs = System.currentTimeMillis()
            val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
            val loc = LocationCache.current()
            val (sunriseMs, sunsetMs) = if (loc != null) {
                SunsetCalculator.sunriseEpochMs(today, loc.first, loc.second) to
                    SunsetCalculator.sunsetEpochMs(today, loc.first, loc.second)
            } else {
                // No location available (permission denied, or no last-known
                // fix yet). Fall back to SunsetCalculator's London defaults.
                SunsetCalculator.sunriseEpochMs(today) to SunsetCalculator.sunsetEpochMs(today)
            }
            val isNight = SunsetCalculator.isNight(nowMs, sunriseMs, sunsetMs)
            // Provenance only - never the coordinates. This line goes to release
            // logcat via Log.i; 2-decimal lat/lon would localise the rider's
            // ride-start to a ~1 km cell for anything with READ_LOGS.
            val locLog = if (loc != null) "gps" else "London-fallback"
            val sunsetLog = if (sunsetMs != null) "${sunsetMs - nowMs}ms away ($locLog)" else "unknown ($locLog)"

            // Same time-of-day scheduling as the radar tail light, via the shared
            // pure LightAutoModeDecider: DAY -> cameraLightDayMode, NIGHT ->
            // cameraLightNightMode. A manual side-button override suppresses both
            // the initial apply and the scheduled flip; the flip is also
            // re-checked at fire time in case the override or link changed.
            val plan = LightAutoModeDecider.plan(nowMs, sunriseMs, sunsetMs, isNight, cameraLightUserOverride)
            Log.i(TAG, "auto-mode: night=$isNight override=$cameraLightUserOverride sunset=$sunsetLog")
            suspend fun applyPhase(phase: LightAutoModeDecider.Phase, label: String) {
                val mode = if (phase == LightAutoModeDecider.Phase.NIGHT) {
                    prefs.cameraLightNightMode
                } else {
                    prefs.cameraLightDayMode
                }
                val ok = applyWithRetry { controller.setMode(mode) }
                Log.i(TAG, "$label mode=$mode applied=$ok")
                if (ok) {
                    cameraLightLastWrittenMode = mode
                    if (ha().isConfigured()) ha().publishFrontModeState(lightSlugEarly, mode.name)
                } else {
                    postLightModeFailNotification(mode)
                }
            }
            if (plan.initial != null) {
                applyPhase(plan.initial, "initial")
            } else {
                Log.i(TAG, "initial mode skipped (manual override active)")
            }
            val flipAt = plan.flipAtMs
            val flipTo = plan.flipTo
            if (flipAt != null && flipTo != null) {
                val job = scope.launch {
                    kotlinx.coroutines.delay(flipAt - nowMs)
                    if (cameraLightGattActive && !cameraLightUserOverride) {
                        applyPhase(
                            flipTo,
                            if (flipTo == LightAutoModeDecider.Phase.NIGHT) "sunset" else "sunrise",
                        )
                    }
                }
                if (flipTo == LightAutoModeDecider.Phase.NIGHT) sunsetJob = job else sunriseJob = job
            }

            for ((uuid, bytes) in notifyChannel) {
                when (uuid) {
                    Uuids.CHAR_BATTERY -> {
                        val pct = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                        BatteryStateBus.update(BatteryEntry(lightSlugEarly, name, pct))
                        if (!prefs.isPaused) haPublisher.maybePublishBatteryToHa(name, pct)
                    }
                    Uuids.SETTINGS_14 -> {
                        val mode = CameraLightController.parseModeStateNotify(bytes) ?: continue
                        Log.d(TAG, "mode-state notify: $mode")
                        val expected = cameraLightLastWrittenMode
                        if (!cameraLightUserOverride && CameraLightOverrideDecider.isOverride(expected, mode)) {
                            cameraLightUserOverride = true
                            Log.i(TAG, "override detected: expected=$expected device=$mode")
                        }
                        if (ha().isConfigured()) ha().publishFrontModeState(lightSlugEarly, mode.name)
                    }
                }
            }
            false
        } finally {
            cameraLightGattActive = false
            if (cameraLightOffSinceMs == null) cameraLightOffSinceMs = clock()
            sunsetJob?.cancel()
            sunriseJob?.cancel()
            queueJob.cancel()
            queue.cancel()
            closeOnce()
        }
    }

    private suspend fun postLightModeFailNotification(mode: CameraLightMode) {
        val modeName = context.getString(
            when (mode) {
                CameraLightMode.HIGH -> R.string.settings_lightmode_high
                CameraLightMode.MEDIUM -> R.string.settings_lightmode_medium
                CameraLightMode.LOW -> R.string.settings_lightmode_low
                CameraLightMode.NIGHT_FLASH -> R.string.settings_lightmode_night_flash
                CameraLightMode.DAY_FLASH -> R.string.settings_lightmode_day_flash
                CameraLightMode.OFF -> R.string.settings_lightmode_off
            },
        )

        notifications.postLightFail(modeName)

        // Descending two-tone NACK beep; released in finally so cancellation cannot leak the handle.
        var tg: android.media.ToneGenerator? = null
        try {
            tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 400)
            kotlinx.coroutines.delay(600)
        } catch (_: Exception) {
        } finally {
            tg?.release()
        }
    }

    companion object {
        private const val TAG = "BikeRadar.Light"

        // Override detection: a front link that has been down longer than this
        // clears the manual side-button override on the next connect.
        private const val CAMERA_LIGHT_OVERRIDE_DEADBAND_MS = 120_000L
    }
}
