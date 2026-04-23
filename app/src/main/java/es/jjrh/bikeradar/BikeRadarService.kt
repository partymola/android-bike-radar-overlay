// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class BikeRadarService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var creds: HaCredentials
    private lateinit var ha: HaClient

    // Battery path state
    private val attemptInFlight = ConcurrentHashMap<String, Long>()
    private val discoveredSlugs = ConcurrentHashMap.newKeySet<String>()
    private val lastHaPublishMs = ConcurrentHashMap<String, Long>()
    private val lastPublishedPct = ConcurrentHashMap<String, Int>()
    private var scanRegistered = false

    // Radar link state
    private var radarJob: Job? = null
    @Volatile var radarGattActive = false

    // Overlay refs for orientation change updates (set/cleared on Main thread)
    @Volatile private var overlayWm: WindowManager? = null
    @Volatile private var overlayViewRef: RadarOverlayView? = null

    // Capture log (written from GATT callback threads + coroutine threads)
    private val captureLogLock = Any()
    @Volatile private var captureLogWriter: PrintWriter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        creds = HaCredentials(this)
        creds.seedFromBuildConfigIfEmpty()
        ha = HaClient(creds.baseUrl, creds.token)

        ensureNotificationChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        pruneCaptureLogs()
        schedulePauseExpiry()
        registerEventScan()
        scope.launch { kickstartFromCache() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_READ_DEVICE -> {
                val mac = intent.getStringExtra(EXTRA_MAC) ?: return START_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: return START_STICKY
                if (!prefs.isPaused) scheduleRead(name, mac)
            }
            ACTION_UPDATE_NOTIF -> {
                schedulePauseExpiry()
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification())
            }
            ACTION_FORCE_RECONNECT -> {
                Log.i(TAG_RADAR, "force reconnect requested")
                radarJob?.cancel()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseExpiryJob?.cancel()
        unregisterEventScan()
        closeCaptureLog()
        RadarStateBus.clear()
        scope.cancel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wm = overlayWm ?: return
        val v = overlayViewRef ?: return
        try { wm.updateViewLayout(v, buildOverlayParams(wm)) } catch (_: Throwable) {}
    }

    // ── battery scan kickstart ────────────────────────────────────────────────

    private suspend fun kickstartFromCache() {
        val known = loadKnownDevices()
        for ((name, mac) in known) scheduleRead(name, mac)

        val fresh = scanForDevices()
        if (fresh.isNotEmpty()) {
            val merged = (known + fresh).distinctBy { it.second }
            saveKnownDevices(merged)
            for ((name, mac) in fresh) scheduleRead(name, mac)
        }
    }

    // ── battery read scheduling + execution ──────────────────────────────────

    private fun scheduleRead(name: String, mac: String) {
        // Always try to keep the radar link alive for rear devices.
        if (isRearDevice(name)) maybeStartRadarLink(name, mac)

        if (radarGattActive && isRearDevice(name)) {
            Log.d(TAG, "skip $name (radar gatt active, piggyback will read instead)")
            return
        }

        val now = System.currentTimeMillis()
        val key = slug(name)
        val throttleKey = "${KEY_LAST_TS}_$key"
        val sp = getSharedPreferences(PREFS_THROTTLE, MODE_PRIVATE)
        if (now - sp.getLong(throttleKey, 0L) < THROTTLE_MS) {
            // Advert sighting proves the device is still powered on even
            // though we're skipping the GATT read. Keep the entry fresh
            // so the dashcam presence indicator doesn't flip to Dropped.
            BatteryStateBus.markSeen(key, now)
            Log.d(TAG, "skip $name (throttled); marked seen")
            return
        }
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
        val sp = getSharedPreferences(PREFS_THROTTLE, MODE_PRIVATE)

        val known = loadKnownDevices().toMutableList()
        if (known.none { it.second == mac }) {
            known.removeAll { it.first == name }
            known.add(name to mac)
            saveKnownDevices(known)
        }

        val pct = readBattery(mac) ?: run {
            Log.w(TAG, "battery read failed: $name $mac")
            return
        }
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
        if (publishBatteryToHa(name, pct)) {
            sp.edit().putLong("${KEY_LAST_TS}_$s", System.currentTimeMillis()).apply()
        }
    }

    // Throttles HA publishes driven by the 2a19 notify stream (~5 s cadence).
    // Publishes immediately on pct change; otherwise one heartbeat every
    // BATTERY_HA_HEARTBEAT_MS to keep HA's last-update recent.
    private suspend fun maybePublishBatteryToHa(name: String, pct: Int) {
        val s = slug(name)
        val now = System.currentTimeMillis()
        val lastPct = lastPublishedPct[s]
        val lastMs = lastHaPublishMs[s] ?: 0L
        val shouldPublish = pct != lastPct || (now - lastMs) >= BATTERY_HA_HEARTBEAT_MS
        if (!shouldPublish) return
        if (publishBatteryToHa(name, pct)) {
            lastHaPublishMs[s] = now
            lastPublishedPct[s] = pct
        }
    }

    private suspend fun publishBatteryToHa(name: String, pct: Int): Boolean {
        ha = HaClient(creds.baseUrl, creds.token)
        if (!ha.isConfigured()) return true

        val s = slug(name)
        if (discoveredSlugs.add(s)) {
            val ok = ha.publishBatteryDiscovery(s, name)
            if (!ok) {
                discoveredSlugs.remove(s)
                Log.w(TAG, "HA discovery failed for varia_${s}_battery")
                return false
            }
            Log.i(TAG, "HA discovery published for varia_${s}_battery")
        }
        val ok = ha.publishBatteryState(s, pct)
        if (ok) HaHealthBus.reportOk()
        else { HaHealthBus.reportError("battery publish failed"); Log.w(TAG, "HA state publish failed for varia/$s/battery") }
        return ok
    }

    @SuppressLint("MissingPermission")
    private suspend fun readBattery(mac: String, timeoutMs: Long = 15_000): Int? {
        val btMgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = btMgr.adapter ?: return null
        if (!adapter.isEnabled) return null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val device = try { adapter.getRemoteDevice(mac) } catch (_: Throwable) { return null }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var done = false
                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                g.close()
                                if (!done) { done = true; cont.resume(null) }
                            }
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        val ch = g.getService(Uuids.SVC_BATTERY)?.getCharacteristic(Uuids.CHAR_BATTERY)
                        if (ch == null) { g.disconnect(); return }
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
                            if (!done) { done = true; cont.resume(value[0].toInt() and 0xFF) }
                        }
                        g.disconnect()
                    }
                }
                val gatt = device.connectGatt(this@BikeRadarService, false, cb, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    if (!done) { done = true; cont.resume(null) }
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {}
                }
            }
        }
    }

    // ── radar link ────────────────────────────────────────────────────────────

    private fun maybeStartRadarLink(name: String, mac: String) {
        if (radarJob?.isActive == true) return
        Log.i(TAG_RADAR, "starting radar link to $name $mac")
        radarJob = scope.launch { runRadarConnection(mac, name) }
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
        Log.w(TAG_RADAR, "BluetoothGatt.refresh() unavailable: $t")
        false
    }

    @SuppressLint("MissingPermission")
    private suspend fun runRadarConnection(mac: String, name: String) {
        val btMgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val device = try {
            btMgr.adapter?.getRemoteDevice(mac)
        } catch (_: Throwable) { null } ?: return

        while (true) {
            Log.i(TAG_RADAR, "connect attempt to $name $mac")
            val quickReconnect = connectAndRun(device, name)
            radarGattActive = false
            RadarStateBus.clear()
            val delayMs = if (quickReconnect) RADAR_QUICK_RECONNECT_MS else RADAR_RECONNECT_MS
            Log.i(TAG_RADAR, "reconnecting in ${delayMs}ms" + if (quickReconnect) " (post-ABORT)" else "")
            kotlinx.coroutines.delay(delayMs)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndRun(device: BluetoothDevice, name: String): Boolean {
        val notifyChannel = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        val queue = BleOpQueue()
        val servicesReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var gatt: BluetoothGatt? = null
        var overlayJob: Job? = null
        var cacheRefreshed = false

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                clog("# conn state: status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        radarGattActive = false
                        queue.cancel()
                        notifyChannel.close()
                        if (!servicesReady.isCompleted) servicesReady.complete(false)
                        g.close()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                clog("# services discovered status=$status services=${g.services.size}")
                val ok = status == BluetoothGatt.GATT_SUCCESS
                val radarPresent = g.getService(Uuids.SVC_RADAR) != null
                if (ok && !cacheRefreshed && (g.services.isEmpty() || !radarPresent)) {
                    // Stale GATT cache from a prior session can leave the
                    // service list empty or missing the radar service even
                    // when the connection is healthy. Clear the cache and
                    // re-discover once before reporting failure.
                    cacheRefreshed = true
                    val refreshed = refreshGattCache(g)
                    clog("# stale cache detected, refresh=$refreshed, retrying discoverServices")
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
                clog("# MTU: $mtu status=$status")
                queue.onMtuChanged(mtu, status)
            }

            @Deprecated("API < 33 compat")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val bytes = ch.value ?: ByteArray(0)
                clogPacket(ch.uuid, bytes)
                notifyChannel.trySend(ch.uuid to bytes)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                clogPacket(ch.uuid, value)
                notifyChannel.trySend(ch.uuid to value)
            }
        }

        gatt = device.connectGatt(this, true, cb, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) { clog("# connectGatt returned null"); return false }

        val queueJob = scope.launch { queue.run() }

        return try {
            val ok = servicesReady.await()
            if (!ok) { clog("# services discovery failed"); return false }

            radarGattActive = true
            Log.i(TAG_RADAR, "connected, running handshake")

            val handshakeOk = RadarUnlock.runHandshake(gatt, queue, notifyChannel) { msg ->
                clog("# script: $msg")
            }

            if (!handshakeOk) {
                clog("# handshake aborted — closing gatt for quick reconnect")
                gatt.disconnect()
                return true
            }

            Log.i(TAG_RADAR, "handshake complete, decoding frames")
            openCaptureLog()

            // Overlay + alert coroutine. Runs on Main (WindowManager requires it).
            overlayJob = scope.launch(Dispatchers.Main) {
                var overlayAdded = false
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val view = RadarOverlayView(this@BikeRadarService)
                val beeper = AlertBeeper().also { it.setVolumePct(prefs.alertVolume) }
                val alerts = AlertDecider()
                val sessionStartMs = System.currentTimeMillis()
                var seenDashcamThisSession = false
                var lastLoggedDashcamStatus: DashcamStatus? = null
                val ticker = flow { while (true) { emit(Unit); delay(DASHCAM_TICK_MS) } }
                try {
                    combine(RadarStateBus.state, BatteryStateBus.entries, ticker) { s, b, _ -> s to b }
                        .collect { (state, batteries) ->
                        if (state.source == DataSource.NONE) return@collect

                        if (!overlayAdded) {
                            if (Settings.canDrawOverlays(this@BikeRadarService)) {
                                try {
                                    wm.addView(view, buildOverlayParams(wm))
                                    overlayWm = wm
                                    overlayViewRef = view
                                    overlayAdded = true
                                    clog("# overlay added")
                                } catch (t: Throwable) {
                                    clog("# overlay addView failed: $t")
                                }
                            } else {
                                clog("# overlay: SYSTEM_ALERT_WINDOW not granted")
                            }
                        }

                        view.setVisualMaxM(prefs.visualMaxDistanceM)
                        view.setAlertMaxM(prefs.alertMaxDistanceM)
                        view.setState(state)

                        val threshold = prefs.batteryLowThresholdPct
                        val now = System.currentTimeMillis()
                        val lowSlugs = batteries.values
                            .filter { it.pct < threshold && now - it.readAtMs < BATTERY_STALE_MS }
                            .map { it.slug }.toSet()
                        view.setBatteryLow(lowSlugs, prefs.batteryShowLabels)

                        // Prefer the map (authoritative, refreshed on every advert so it
                        // tracks name renames). Fall back to deriving from the stored display
                        // name so the indicator still works when the dashcam has not
                        // advertised this session — which is the entire point of the feature.
                        val dashcamSlug = prefs.dashcamMac?.let { mac ->
                            macToSlug[mac]
                                ?: macToSlug[mac.uppercase(Locale.ROOT)]
                                ?: prefs.dashcamDisplayName?.let { slug(it) }
                        }
                        val cfg = DashcamStatusDeriver.Config(
                            warnWhenOff = prefs.dashcamWarnWhenOff,
                            selectedSlug = dashcamSlug,
                        )
                        val dashcamEntry = dashcamSlug?.let { batteries[it] }
                        if (dashcamEntry != null) seenDashcamThisSession = true
                        val status = DashcamStatusDeriver.derive(
                            config = cfg,
                            entries = batteries,
                            nowMs = now,
                            sessionStartMs = sessionStartMs,
                            seenThisSession = seenDashcamThisSession,
                            freshMs = DASHCAM_FRESH_MS,
                            coldStartMs = DASHCAM_COLD_START_MS,
                        )
                        if (status != lastLoggedDashcamStatus) {
                            Log.i(TAG_RADAR, "dashcam status=$status " +
                                "warn=${prefs.dashcamWarnWhenOff} " +
                                "mac=${prefs.dashcamMac ?: "-"} slug=${dashcamSlug ?: "-"} " +
                                "entries=${batteries.size} " +
                                "seen=$seenDashcamThisSession " +
                                "ageMs=${dashcamEntry?.let { now - it.readAtMs } ?: -1L} " +
                                "sessionAgeMs=${now - sessionStartMs}")
                            lastLoggedDashcamStatus = status
                        }
                        view.setDashcamStatus(status, dashcamSlug)

                        if (!prefs.isPaused) {
                            when (val ev = alerts.decide(state.vehicles, prefs.alertMaxDistanceM, now)) {
                                is AlertDecider.Event.Beep -> beeper.play(ev.count)
                                AlertDecider.Event.Clear   -> beeper.playClear()
                                AlertDecider.Event.None    -> {}
                            }
                        } else {
                            alerts.reset()
                        }
                    }
                } finally {
                    beeper.release()
                    overlayWm = null
                    overlayViewRef = null
                    if (overlayAdded) {
                        try { wm.removeView(view); clog("# overlay removed") }
                        catch (t: Throwable) { Log.w(TAG_RADAR, "removeView failed: $t") }
                    }
                }
            }

            val rearMac = gatt.device?.address
            val v2Dec = RadarV2Decoder()
            var v2FrameCount = 0

            for ((uuid, bytes) in notifyChannel) {
                when (uuid) {
                    Uuids.RADAR_V2 -> {
                        if (v2FrameCount++ == 0) Log.i(TAG_RADAR, "first V2 frame: ${bytes.toHex()}")
                        v2Dec.feed(bytes)?.let { RadarStateBus.publish(it) }
                    }
                    Uuids.CHAR_BATTERY -> {
                        val pct = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                        val s = slug(name)
                        if (rearMac != null) macToSlug[rearMac] = s
                        BatteryStateBus.update(BatteryEntry(s, name, pct))
                        if (!prefs.isPaused) maybePublishBatteryToHa(name, pct)
                    }
                }
            }
            false
        } finally {
            overlayJob?.cancel()
            queue.cancel()
            queueJob.cancel()
            radarGattActive = false
            try { gatt?.disconnect() } catch (_: Throwable) {}
            closeCaptureLog()
        }
    }

    // ── capture log ───────────────────────────────────────────────────────────

    private fun openCaptureLog() {
        val dir = getExternalFilesDir(null) ?: return
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = java.io.File(dir, "bike-radar-capture-$stamp.log")
        try {
            val pw = PrintWriter(BufferedWriter(FileWriter(file)), true)
            synchronized(captureLogLock) { captureLogWriter = pw }
            activeCaptureLogName = file.name
            clog("# bike-radar capture started ${java.time.Instant.now()}")
            clog("# format: unix_ms char_tail_4hex hex_bytes_no_spaces")
            Log.i(TAG_RADAR, "capture log: ${file.absolutePath}")
            // Prune after the new file exists so steady-state count is
            // MAX_CAPTURE_LOGS, not MAX_CAPTURE_LOGS+1. The active file is
            // skipped by name inside pruneCaptureLogs.
            pruneCaptureLogs()
        } catch (t: Throwable) {
            Log.w(TAG_RADAR, "failed to open capture log: $t")
        }
    }

    private fun closeCaptureLog() {
        synchronized(captureLogLock) {
            captureLogWriter?.flush()
            captureLogWriter?.close()
            captureLogWriter = null
        }
        activeCaptureLogName = null
    }

    fun clog(msg: String) {
        synchronized(captureLogLock) { captureLogWriter?.println(msg) }
        Log.d(TAG_RADAR, msg)
    }

    private fun clogPacket(uuid: UUID, bytes: ByteArray) {
        // Use chars 4-7 of the first UUID segment as the tag (e.g. "3203", "3204", "2a19").
        // All Garmin chars share the suffix 667b-11e3-949a-0800200c9a66 so the last 4 are
        // always "9a66" — chars 4-7 of the first segment give the meaningful discriminator.
        val tag = uuid.toString().substring(4, 8)
        val line = "${System.currentTimeMillis()} $tag ${bytes.toHex()}"
        synchronized(captureLogLock) { captureLogWriter?.println(line) }
    }

    // ── event scan registration ───────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun registerEventScan() {
        if (scanRegistered) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_SCAN missing - event scan deferred")
            return
        }
        val mgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = mgr?.adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "no BLE scanner")
            return
        }
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString("0000fe1f-0000-1000-8000-00805f9b34fb")))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val rc = try {
            scanner.startScan(filters, settings, buildScanPendingIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "startScan threw", t); -1
        }
        scanRegistered = (rc == 0)
        val offloaded = try { mgr.adapter.isOffloadedFilteringSupported } catch (_: Throwable) { false }
        Log.i(TAG, "event scan registered rc=$rc offloaded=$offloaded (all-matches mode)")
    }

    @SuppressLint("MissingPermission")
    private fun unregisterEventScan() {
        if (!scanRegistered) return
        val mgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = mgr?.adapter?.bluetoothLeScanner ?: return
        try { scanner.stopScan(buildScanPendingIntent()) } catch (_: Throwable) {}
        scanRegistered = false
    }

    private fun buildScanPendingIntent(): PendingIntent {
        val i = Intent(this, BatteryScanReceiver::class.java).apply {
            action = BatteryScanReceiver.ACTION_SCAN_RESULT
        }
        val flags = if (Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(this, SCAN_PI_REQ, i, flags)
    }

    // ── kickstart active scan ─────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun scanForDevices(timeoutMs: Long = 12_000): List<Pair<String, String>> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()

        val found = ConcurrentHashMap<String, Pair<String, String>>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(cbType: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device?.name ?: return
                val mac = result.device?.address ?: return
                if (BatteryScanReceiver.matchesVariaName(name)) found.putIfAbsent(mac, name to mac)
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(emptyList(), settings, cb)
        try {
            kotlinx.coroutines.delay(timeoutMs)
        } finally {
            scanner.stopScan(cb)
        }
        return found.values.toList()
    }

    // ── known-device cache ────────────────────────────────────────────────────

    private fun loadKnownDevices(): List<Pair<String, String>> {
        val sp = getSharedPreferences(PREFS_THROTTLE, MODE_PRIVATE)
        val raw = sp.getStringSet(KEY_KNOWN, emptySet()) ?: emptySet()
        return raw.mapNotNull {
            val p = it.split("|", limit = 2)
            if (p.size == 2) p[0] to p[1] else null
        }
    }

    private fun saveKnownDevices(devs: List<Pair<String, String>>) {
        val sp = getSharedPreferences(PREFS_THROTTLE, MODE_PRIVATE)
        sp.edit().putStringSet(KEY_KNOWN, devs.map { "${it.first}|${it.second}" }.toSet()).apply()
    }

    // ── notification ──────────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bike Radar", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    private fun buildNotification(): Notification {
        val paused = prefs.isPaused
        val contentText = if (paused) {
            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(prefs.pausedUntilEpochMs))
            "Paused until $t"
        } else {
            "Active"
        }
        val actionLabel = if (paused) "Resume" else "Pause 1h"
        val actionBroadcast = if (paused) RemoteControlReceiver.ACTION_RESUME else RemoteControlReceiver.ACTION_PAUSE_1H
        val piFlags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val actionPi = PendingIntent.getBroadcast(
            this, NOTIF_ACTION_REQ,
            Intent(this, RemoteControlReceiver::class.java).apply { action = actionBroadcast },
            piFlags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bike Radar")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, actionLabel, actionPi)
            .build()
    }

    // ── pause expiry + capture prune ──────────────────────────────────────────

    private var pauseExpiryJob: Job? = null

    private fun schedulePauseExpiry() {
        pauseExpiryJob?.cancel()
        val until = prefs.pausedUntilEpochMs
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0) return
        pauseExpiryJob = scope.launch {
            delay(remaining)
            prefs.pausedUntilEpochMs = 0L
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun pruneCaptureLogs() {
        val dir = getExternalFilesDir(null) ?: return
        val logs = dir.listFiles { f -> f.name.startsWith("bike-radar-capture-") && f.name.endsWith(".log") }
            ?: return
        val active = activeCaptureLogName
        // A real session logs thousands of packet lines; anything under a few
        // hundred bytes is just the header + maybe a connect-state line from a
        // session where the radar never actually connected.
        val tiny = logs.filter { it.name != active && it.length() < MIN_USEFUL_LOG_BYTES }
        tiny.forEach { it.delete() }
        val remaining = logs.filter { it.name != active && it.length() >= MIN_USEFUL_LOG_BYTES }
        val keepFromOld = if (active != null) MAX_CAPTURE_LOGS - 1 else MAX_CAPTURE_LOGS
        if (remaining.size <= keepFromOld) {
            if (tiny.isNotEmpty()) Log.d(TAG, "deleted ${tiny.size} header-only capture logs")
            return
        }
        val pruned = remaining.sortedByDescending { it.lastModified() }.drop(keepFromOld)
        pruned.forEach { it.delete() }
        Log.d(TAG, "deleted ${tiny.size} header-only + ${pruned.size} old capture logs")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun buildOverlayParams(wm: WindowManager) = WindowManager.LayoutParams(
        dpToPx(130),
        wm.currentWindowMetrics.bounds.height(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).also { it.gravity = Gravity.TOP or Gravity.END; it.x = 0; it.y = 0 }

    private fun isRearDevice(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("rear") || n.contains("rtl")
    }

    companion object {
        private const val TAG = "BikeRadar"
        private const val TAG_RADAR = "BikeRadar.Radar"
        const val CHANNEL_ID = "bike_radar_min"
        const val NOTIF_ID = 1
        private const val PREFS_THROTTLE = "bike_radar_throttle"
        private const val KEY_KNOWN = "known_devices"
        private const val KEY_LAST_TS = "last_ts"
        private const val SCAN_PI_REQ = 0xB1CC

        const val ACTION_READ_DEVICE = "es.jjrh.bikeradar.READ_DEVICE"
        const val ACTION_UPDATE_NOTIF = "es.jjrh.bikeradar.UPDATE_NOTIF"
        const val ACTION_FORCE_RECONNECT = "es.jjrh.bikeradar.FORCE_RECONNECT"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"
        private const val NOTIF_ACTION_REQ = 0xB1CD

        const val THROTTLE_MS = 5 * 60 * 1000L
        const val ATTEMPT_COOLDOWN_MS = 30 * 1000L
        const val RADAR_RECONNECT_MS = 5_000L
        const val RADAR_QUICK_RECONNECT_MS = 1_500L
        const val BATTERY_HA_HEARTBEAT_MS = 5 * 60 * 1000L
        const val BATTERY_STALE_MS = 15 * 60 * 1000L
        const val MAX_CAPTURE_LOGS = 500
        const val MIN_USEFUL_LOG_BYTES = 500L

        // Dashcam presence-by-advert timing. Fresh threshold accommodates
        // SCAN_MODE_LOW_POWER batching; cold-start grace covers the window
        // between overlay activation and the first received advert.
        const val DASHCAM_TICK_MS = 2_000L
        const val DASHCAM_FRESH_MS = 30_000L
        const val DASHCAM_COLD_START_MS = 10_000L

        @Volatile var activeCaptureLogName: String? = null
            internal set

        /** MAC (identity address) -> slug used in BatteryStateBus entries.
         *  Populated by doReadBattery + the piggyback read path; read by the
         *  overlay composer to resolve the user-selected dashcam MAC to the
         *  right battery entry. */
        val macToSlug = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun slug(name: String): String =
            name.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .removePrefix("varia_")
                .ifEmpty { "device" }
    }
}
