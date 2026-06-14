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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The boundary to the [RadarLinkState] owned by [RadarLinkCoordinator] (the sole
 * writer of the state flow). The controller signals connect/disconnect through
 * this gateway and reads a snapshot for the two fields its connection loop needs
 * (radarGattActive for the light-flip guard, radarOffSinceMs for the
 * reconnect-backoff cap).
 */
internal interface RadarLinkStateGateway {
    fun markConnected()

    fun markDisconnected()

    fun snapshot(): RadarLinkState
}

/**
 * Owns the rear-radar BLE link: the bond-state watch, the start/reconnect
 * lifecycle, the GATT connect -> AMV handshake -> notify->decode->RadarStateBus
 * loop, the data-flow watchdog, the radar tail-light auto-mode, and the
 * debug-only write-probe. Extracted from BikeRadarService last, because it is
 * the alert hot path and the most service-coupled subsystem.
 *
 * NOT to be confused with [RadarLightController], which only issues tail-light
 * mode-set writes over the link this class owns.
 *
 * The service keeps ownership of `scope` and the warm `AlertBeeper`, and
 * [RadarLinkCoordinator] owns the link state; all are reached here only through
 * injected collaborators (scope passed in, the alert path lives in
 * [OverlayPipeline], the link state via [RadarLinkStateGateway]).
 */
@SuppressLint("MissingPermission")
internal class RadarLinkController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val captureLog: CaptureLogManager,
    private val overlayPipeline: OverlayPipeline,
    private val haPublisher: HaPublisher,
    private val notifications: ServiceNotifications,
    private val linkState: RadarLinkStateGateway,
    private val macToSlug: MutableMap<String, String>,
    private val slug: (String) -> String,
    /** Always-on link-event sink ([LinkEventJournal]); unlike the capture
     *  log it records the attempts that never produced a connection. */
    private val journal: (String) -> Unit,
    /** Monotonic clock for the V2 data-flow watchdog and the radar-light
     *  override deadband. Injected as elapsedRealtime so an NTP/DST wall-clock
     *  jump can't make a silently-dead radar look alive or mis-clear the
     *  rider's manual light override. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** GATT opener seam, defaulting to the shared LE-transport [connectGattLe].
     *  Injected only by the Robolectric harness so a test can capture the
     *  connection's [BluetoothGattCallback] and drive the callbacks by hand;
     *  production keeps the real connect path unchanged. */
    private val openGatt: (Context, BluetoothDevice, Boolean, BluetoothGattCallback) -> BluetoothGatt? = ::connectGattLe,
) {
    // The connection coroutine; single-slot, guarded by start()'s @Synchronized.
    @Volatile private var radarJob: Job? = null

    // MAC currently driven by the link, exposed read-only so the bond receiver
    // matches the right device and the overlay/HA lambdas can resolve the slug.
    @Volatile var currentRadarMac: String? = null
        private set

    // True when the user un-paired the radar in system settings. The reconnect
    // loop bails out instead of looping forever; cleared on re-pair / restart.
    @Volatile private var bondLost = false

    // Last time the V2 stream produced a frame (watchdog clock); 0 = none yet.
    @Volatile private var lastV2FrameMs: Long = 0L

    // Set true when the current connection reaches the V2 decode loop; read
    // after connectAndRun returns to decide whether to reset the backoff.
    @Volatile private var lastConnectionReachedDecode = false

    // Radar tail-light auto-mode state (the radar light shares the radar GATT
    // link). Override is detected from 2f14 slot changes against a per-connect
    // baseline; it persists across brief reconnects, cleared only past a deadband.
    @Volatile private var radarLightUserOverride = false

    @Volatile private var radarLightBaselineKey: Int? = null

    @Volatile private var radarLightOffSinceMs: Long? = null

    // Live radar GATT + queue, set after the V2 handshake and cleared in the
    // connection finally. Used ONLY by the debug-only write-probe.
    @Volatile private var liveRadarGatt: BluetoothGatt? = null

    @Volatile private var liveRadarQueue: BleOpQueue? = null

    private var bondReceiverRegistered = false
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val mac = device?.address ?: return
            val expected = currentRadarMac ?: return
            if (!mac.equals(expected, ignoreCase = true)) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            when (state) {
                BluetoothDevice.BOND_NONE -> onRadarBondLost(mac)
                BluetoothDevice.BOND_BONDED -> {
                    if (bondLost) {
                        Log.i(TAG, "radar re-paired ($mac); allowing reconnect")
                        journal("radar re-paired")
                        bondLost = false
                    }
                }
            }
        }
    }

    fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(bondReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        try {
            context.unregisterReceiver(bondReceiver)
        } catch (_: Throwable) {}
        bondReceiverRegistered = false
    }

    /**
     * Called when the radar's bond is removed in system Bluetooth settings.
     * Stops the reconnect loop (which would otherwise spin forever against a
     * peer that will refuse the LESC handshake) and posts a notification so
     * the user knows why the link went silent.
     */
    private fun onRadarBondLost(mac: String) {
        Log.w(TAG, "radar bond removed ($mac); stopping reconnect loop")
        journal("radar bond removed; reconnect loop stopped")
        bondLost = true
        radarJob?.cancel()
        radarJob = null
        linkState.markDisconnected()
        currentRadarMac = null
        notifications.postBondLost()
    }

    @Synchronized
    fun start(name: String, mac: String) {
        if (radarJob?.isActive == true) return
        if (bondLost) {
            Log.d(TAG, "skip radar link start: bond lost, waiting for re-pair")
            return
        }
        Log.i(TAG, "starting radar link to $name $mac")
        journal("radar link start $name")
        radarJob = scope.launch { runRadarConnection(mac, name) }
    }

    /** True while the connection coroutine is live. */
    fun isActive(): Boolean = radarJob?.isActive == true

    /** Drop the current connection; the reconnect loop opens a fresh one. */
    fun forceReconnect() {
        radarJob?.cancel()
    }

    /** Tear down the bond-state watch on service destroy. The connection
     *  coroutine rides the injected scope, so scope.cancel() in the service
     *  stops it - this only unregisters the receiver. */
    fun shutdown() {
        unregisterBondReceiver()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runRadarConnection(mac: String, name: String) {
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val device = try {
            btMgr.adapter?.getRemoteDevice(mac)
        } catch (_: Throwable) {
            null
        } ?: return

        currentRadarMac = mac
        var backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
        try {
            while (true) {
                if (bondLost) {
                    Log.i(TAG, "bond lost for $mac; reconnect loop suspended")
                    return
                }
                Log.i(TAG, "connect attempt to $name $mac")
                lastConnectionReachedDecode = false
                val quickReconnect = connectAndRun(device, name)
                linkState.markDisconnected()
                if (bondLost) {
                    Log.i(TAG, "bond lost during attempt; exiting reconnect loop")
                    return
                }
                if (lastConnectionReachedDecode) {
                    // Healthy session - reset the backoff so the next reconnect
                    // is fast.
                    backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
                }
                val delayMs = ReconnectLoopPlanner.nextDelayMs(backoffMs, quickReconnect)
                val tag = when {
                    quickReconnect -> " (post-ABORT)"
                    else -> " (backoff=${backoffMs}ms)"
                }
                Log.i(TAG, "reconnecting in ${delayMs}ms$tag")
                journal("radar reconnect in ${delayMs}ms$tag")
                kotlinx.coroutines.delay(delayMs)
                if (!quickReconnect) {
                    backoffMs = ReconnectLoopPlanner.grow(
                        backoffMs = backoffMs,
                        nowMs = clock(),
                        offSinceMs = linkState.snapshot().radarOffSinceMs,
                        longOfflineThresholdMs = prefs.radarLongOfflineThresholdMinutes * 60_000L,
                        longOfflineCapMs = prefs.radarLongOfflineCapSec * 1_000L,
                    )
                }
            }
        } finally {
            currentRadarMac = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndRun(device: BluetoothDevice, name: String): Boolean {
        val notifyChannel = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        val queue = BleOpQueue()
        val servicesReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var gatt: BluetoothGatt? = null
        var overlayJob: Job? = null
        var watchdogJob: Job? = null
        var cacheRefreshed = false
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
                captureLog.clog("# conn state: status=$status newState=$newState")
                journal("radar conn state status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        linkState.markDisconnected()
                        queue.cancel()
                        notifyChannel.close()
                        if (!servicesReady.isCompleted) servicesReady.complete(false)
                        closeOnce()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                captureLog.clog("# services discovered status=$status services=${g.services.size}")
                val ok = status == BluetoothGatt.GATT_SUCCESS
                val radarPresent = g.getService(Uuids.SVC_RADAR) != null
                if (ok && !cacheRefreshed && (g.services.isEmpty() || !radarPresent)) {
                    // Stale GATT cache from a prior session can leave the
                    // service list empty or missing the radar service even
                    // when the connection is healthy. Clear the cache and
                    // re-discover once before reporting failure.
                    cacheRefreshed = true
                    val refreshed = refreshGattCache(g)
                    captureLog.clog("# stale cache detected, refresh=$refreshed, retrying discoverServices")
                    if (refreshed) {
                        g.discoverServices()
                        return
                    }
                }
                if (!servicesReady.isCompleted) {
                    servicesReady.complete(ok)
                }
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
                captureLog.clog("# MTU: $mtu status=$status")
                queue.onMtuChanged(mtu, status)
            }

            @Deprecated("API < 33 compat")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val bytes = ch.value ?: ByteArray(0)
                captureLog.clogPacket(ch.uuid, bytes)
                notifyChannel.trySend(ch.uuid to bytes)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                captureLog.clogPacket(ch.uuid, value)
                notifyChannel.trySend(ch.uuid to value)
            }
        }

        gatt = openGatt(context, device, true, cb)
        if (gatt == null) {
            captureLog.clog("# connectGatt returned null")
            journal("radar connectGatt returned null")
            return false
        }

        val queueJob = scope.launch { queue.run() }

        // Radar tail-light dusk/dawn flip jobs - declared here so the finally can
        // cancel them (scheduling happens post-handshake when auto-mode is on).
        var radarSunsetJob: Job? = null
        var radarSunriseJob: Job? = null

        return try {
            val ok = servicesReady.await()
            if (!ok) {
                captureLog.clog("# services discovery failed")
                journal("radar services discovery failed")
                return false
            }

            linkState.markConnected()
            Log.i(TAG, "connected, running handshake")
            journal("radar connected, running handshake")

            val handshakeOk = RadarUnlock.runHandshake(gatt, queue, notifyChannel) { msg ->
                captureLog.clog("# script: $msg")
            }

            if (!handshakeOk) {
                captureLog.clog("# handshake aborted - closing gatt for quick reconnect")
                journal("radar handshake aborted (quick reconnect)")
                gatt.disconnect()
                return true
            }

            Log.i(TAG, "handshake complete, decoding frames")
            journal("radar handshake complete")
            // First chance per ride to refresh the location cache used by
            // SunsetCalculator (front- and radar-light auto-modes). 60-min staleness
            // gate means quick stop-and-go reconnects don't re-poll.
            LocationCache.refreshIfStale(context)
            captureLog.open()
            // Overlay + alert coroutine, extracted into OverlayPipeline.
            overlayJob = overlayPipeline.attach(scope, name)

            val rearMac = gatt.device?.address
            val v2Dec = RadarV2Decoder()
            var v2FrameCount = 0

            // Mark this connection as healthy so the reconnect loop resets
            // its backoff. Initialise the watchdog clock to "now" so we give
            // the first frame a fair chance to arrive.
            lastConnectionReachedDecode = true
            lastV2FrameMs = clock()

            // Drop the connection interval from BALANCED to LOW_POWER once
            // the V2 stream is up: the radar pushes notifications at its own
            // cadence, so a tighter interval just wastes the phone radio.
            try {
                val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                if (!ok) Log.w(TAG, "requestConnectionPriority(LOW_POWER) returned false")
            } catch (t: Throwable) {
                Log.w(TAG, "requestConnectionPriority threw: $t")
            }

            // Data-flow watchdog: if no V2 frame arrives for V2_FRAME_STALL_MS,
            // tear down the GATT so the outer loop can reconnect. Catches the
            // case where the stack thinks we are still connected but the radar
            // has gone silent.
            val capturedGatt = gatt
            watchdogJob = scope.launch {
                while (true) {
                    delay(V2_WATCHDOG_TICK_MS)
                    val now = clock()
                    val last = lastV2FrameMs
                    if (V2WatchdogDecider.isStale(now, last, V2_FRAME_STALL_MS)) {
                        val ageMs = now - last
                        Log.w(TAG, "V2 stream silent for ${ageMs}ms; tearing down GATT")
                        journal("radar V2 stream silent ${ageMs}ms; tearing down")
                        try {
                            capturedGatt.disconnect()
                        } catch (_: Throwable) {}
                        return@launch
                    }
                }
            }

            // Subscribe the radar control-service mode-state notify (6a4e2f14)
            // when EITHER the production light auto-mode OR the debug probe needs
            // it. Done here, AFTER the V2 handshake, so it cannot interfere with
            // the unlock (verified safe on-bench). 6a4e2f12 and the live
            // write-probe handles stay strictly debug-gated - never exposed in a
            // normal ride. Never touch 6a4e3203 (V1 char) here.
            val controlSvc = gatt.getService(Uuids.SVC_CONTROL)
            val ch2f14 = controlSvc?.getCharacteristic(Uuids.SETTINGS_14)
            if ((prefs.radarSettingsProbeEnabled || prefs.radarLightAutoModeEnabled) && ch2f14 != null) {
                queue.writeCccd(gatt, ch2f14)
            }
            if (prefs.radarSettingsProbeEnabled) {
                val probe12 = controlSvc?.getCharacteristic(Uuids.SETTINGS_12)
                captureLog.clog("# radar_settings_probe svc=${controlSvc != null} 2f14=${ch2f14 != null} 2f12=${probe12 != null}")
                if (probe12 != null) queue.writeCccd(gatt, probe12)
                liveRadarGatt = gatt
                liveRadarQueue = queue
            }

            // Radar tail-light auto day/night. The radar light shares THIS
            // connection (no separate device), so set the mode now and schedule
            // the dusk/dawn flip as locals cancelled in finally. Override (rider
            // button press) is detected from 2f14 slot changes in the loop below,
            // against a baseline captured fresh this connect. A manual override
            // sticks across brief reconnects and clears only past a deadband.
            // Supplementary rear light (built-in rear is primary), so failures
            // are non-critical; rider chose dashcam-parity fail feedback.
            radarLightBaselineKey = null
            if (RadarLightOverrideDecider.shouldClearOverride(
                    radarLightOffSinceMs,
                    clock(),
                    RADAR_LIGHT_OVERRIDE_DEADBAND_MS,
                )
            ) {
                radarLightUserOverride = false
            }
            radarLightOffSinceMs = null
            if (prefs.radarLightAutoModeEnabled && controlSvc != null) {
                val controller = RadarLightController(gatt, queue)
                val nowMs = System.currentTimeMillis()
                val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                val loc = LocationCache.current()
                val (sunriseMs, sunsetMs) = if (loc != null) {
                    SunsetCalculator.sunriseEpochMs(today, loc.first, loc.second) to
                        SunsetCalculator.sunsetEpochMs(today, loc.first, loc.second)
                } else {
                    SunsetCalculator.sunriseEpochMs(today) to SunsetCalculator.sunsetEpochMs(today)
                }
                val night = SunsetCalculator.isNight(nowMs, sunriseMs, sunsetMs)
                val plan = LightAutoModeDecider.plan(nowMs, sunriseMs, sunsetMs, night, radarLightUserOverride)
                suspend fun applyPhase(phase: LightAutoModeDecider.Phase) {
                    val mode = if (phase == LightAutoModeDecider.Phase.NIGHT) {
                        prefs.radarLightNightMode
                    } else {
                        prefs.radarLightDayMode
                    }
                    val okSet = applyWithRetry { controller.setMode(mode) }
                    Log.i(TAG, "radar light $phase mode=$mode applied=$okSet")
                    if (!okSet) postRadarLightModeFailNotification(mode)
                }
                plan.initial?.let { applyPhase(it) }
                val flipAt = plan.flipAtMs
                val flipTo = plan.flipTo
                if (flipAt != null && flipTo != null) {
                    val job = scope.launch {
                        kotlinx.coroutines.delay(flipAt - nowMs)
                        if (linkState.snapshot().radarGattActive && !radarLightUserOverride) {
                            applyPhase(flipTo)
                        }
                    }
                    if (flipTo == LightAutoModeDecider.Phase.NIGHT) radarSunsetJob = job else radarSunriseJob = job
                }
            }

            for ((uuid, bytes) in notifyChannel) {
                when (uuid) {
                    Uuids.RADAR_V2 -> {
                        lastV2FrameMs = clock()
                        if (v2FrameCount++ == 0) Log.i(TAG, "first V2 frame: ${bytes.toHex()}")
                        v2Dec.feed(bytes)?.let { RadarStateBus.publish(it) }
                    }
                    Uuids.SETTINGS_14 -> {
                        if (prefs.radarSettingsProbeEnabled) captureLog.clog("# radar_2f14 ${bytes.toHex()}")
                        if (prefs.radarLightAutoModeEnabled) {
                            RadarLightController.parseModeState(bytes)?.let { ms ->
                                val key = RadarLightOverrideDecider.key(ms.slot, ms.type)
                                if (radarLightBaselineKey == null) {
                                    radarLightBaselineKey = key
                                } else if (!radarLightUserOverride &&
                                    RadarLightOverrideDecider.isOverride(radarLightBaselineKey, key)
                                ) {
                                    radarLightUserOverride = true
                                    radarSunsetJob?.cancel()
                                    radarSunriseJob?.cancel()
                                    captureLog.clog("# radar_light_override (2f14 slot change)")
                                }
                            }
                        }
                    }
                    Uuids.SETTINGS_12 -> if (prefs.radarSettingsProbeEnabled) captureLog.clog("# radar_2f12 ${bytes.toHex()}")
                    Uuids.CHAR_BATTERY -> {
                        val pct = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                        val s = slug(name)
                        if (rearMac != null) macToSlug[rearMac] = s
                        BatteryStateBus.update(BatteryEntry(s, name, pct))
                        if (!prefs.isPaused) haPublisher.maybePublishBatteryToHa(name, pct)
                    }
                }
            }
            false
        } finally {
            radarSunsetJob?.cancel()
            radarSunriseJob?.cancel()
            radarLightOffSinceMs = clock()
            liveRadarGatt = null
            liveRadarQueue = null
            watchdogJob?.cancel()
            overlayJob?.cancel()
            queue.cancel()
            queueJob.cancel()
            linkState.markDisconnected()
            RadarStateBus.clear()
            // Fire-and-forget final flush of the ride summary so HA sees
            // the latest values before the next reconnect's backoff delay.
            scope.launch(Dispatchers.IO) { haPublisher.publishRideSummaryIfChanged() }
            closeOnce()
            captureLog.close()
        }
    }

    /**
     * Invokes the hidden BluetoothGatt.refresh() method via reflection.
     *
     * Known Android workaround for stale GATT cache after a firmware-side
     * service change; widely used in OSS BLE projects (Punch Through,
     * Stack Overflow). Android caches the remote GATT database between
     * connections; if the peer's services have changed since the cache
     * was populated, service discovery returns the stale list. The
     * @hide refresh() method clears that cache so the next
     * discoverServices() sees the live characteristics.
     *
     * The method is @hide and could in theory be removed in a future
     * Android release, so the call is wrapped in try/catch and the
     * caller falls back to the original behaviour on failure.
     */
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean = try {
        val method = BluetoothGatt::class.java.getMethod("refresh")
        method.invoke(gatt) as? Boolean ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "BluetoothGatt.refresh() unavailable: $t")
        false
    }

    /** Radar tail-light switch-failed feedback, dashcam parity (rider choice):
     *  a HIGH-priority notification + the NACK beep. Distinct notification ID
     *  from the dashcam so neither clobbers the other. Fires only when the GATT
     *  write was not ACKed after the retries - it can't catch the rarer "ACKed
     *  but the light element didn't change" case (no read-back). */
    private suspend fun postRadarLightModeFailNotification(mode: RadarLightMode) {
        val modeName = context.getString(
            when (mode) {
                RadarLightMode.NIGHT_FLASH -> R.string.settings_lightmode_night_flash
                RadarLightMode.DAY_FLASH -> R.string.settings_lightmode_day_flash
                RadarLightMode.SOLID -> R.string.settings_lightmode_solid
                RadarLightMode.PELOTON -> R.string.settings_lightmode_peloton
                RadarLightMode.OFF -> R.string.settings_lightmode_off
            },
        )

        notifications.postRadarLightFail(modeName)

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

    /**
     * Debug-only radar tail-light mode-set write-probe. Writes `07 00 NN` to
     * the radar's SETTINGS_ACK (6a4e2f11), mirroring the front camera's mode-set,
     * so a bench sweep can find which command sets the tail-light mode (and
     * whether NN selects a cycle-slot or a stable mode-type). Reached only via
     * the dev-only [RemoteControlReceiver] with the probe toggle on; the write
     * lands on the live radar connection if one is up. Not a shipping path - the
     * production controller will be derived once the encoding is pinned.
     */
    fun probeWriteRadarLight(nn: Int) {
        if (nn !in 0..0xFF) {
            captureLog.clog("# radar_probe_write skipped (bad nn=$nn)")
            return
        }
        probeWriteRadar(byteArrayOf(0x07, 0x00, nn.toByte()))
    }

    /** Parse a hex string (spaces ignored) and write it raw to the radar's
     *  control char. Lets the bench probe send any command - mode-set by type
     *  (`06 09 01 TT`), slot-list config (`06 09 05 ...`), etc. - not just
     *  the `07 00 NN` slot select. */
    fun probeWriteRadarRaw(hex: String) {
        val clean = hex.filterNot { it.isWhitespace() }
        val bytes = try {
            require(clean.isNotEmpty() && clean.length % 2 == 0)
            clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            captureLog.clog("# radar_probe_write skipped (bad hex='$hex')")
            return
        }
        probeWriteRadar(bytes)
    }

    private fun probeWriteRadar(payload: ByteArray) {
        val label = payload.joinToString(" ") { "%02x".format(it) }
        if (!prefs.radarSettingsProbeEnabled) {
            captureLog.clog("# radar_probe_write skipped (probe off) [$label]")
            return
        }
        val gatt = liveRadarGatt
        val queue = liveRadarQueue
        if (gatt == null || queue == null) {
            captureLog.clog("# radar_probe_write skipped (no live radar) [$label]")
            return
        }
        val ch = gatt.getService(Uuids.SVC_CONTROL)?.getCharacteristic(Uuids.SETTINGS_ACK)
        if (ch == null) {
            captureLog.clog("# radar_probe_write skipped (no 2f11) [$label]")
            return
        }
        scope.launch {
            val ok = queue.write(gatt, ch, payload, noResponse = false)
            captureLog.clog("# radar_probe_write [$label] ok=$ok")
        }
    }

    companion object {
        private const val TAG = "BikeRadar.Radar"

        // V2 data-flow watchdog: if no V2 notification arrives for
        // V2_FRAME_STALL_MS, the link is considered stuck and the GATT is torn
        // down so the outer loop reconnects.
        const val V2_WATCHDOG_TICK_MS = 2_000L
        const val V2_FRAME_STALL_MS = 5_000L

        private const val RADAR_LIGHT_OVERRIDE_DEADBAND_MS = 120_000L
    }
}
