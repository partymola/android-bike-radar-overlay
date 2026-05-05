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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import java.util.concurrent.atomic.AtomicReference
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
    @Volatile private var scanRegistered = false

    // Per-ride statistics. Reset on each onCreate so "this ride" begins
    // when the service starts. Published to HA via the periodic loop
    // started below.
    private var rideStats = RideStatsAccumulator()
    private val rideSummaryDiscoveredSlugs = ConcurrentHashMap.newKeySet<String>()

    // Radar link state
    @Volatile private var radarJob: Job? = null
    @Volatile var radarGattActive = false

    // Front camera/light link state
    @Volatile private var cameraLightJob: Job? = null
    @Volatile private var cameraLightGattActive = false
    @Volatile private var cameraLightUserOverride = false
    @Volatile private var cameraLightLastWrittenMode: CameraLightMode? = null
    @Volatile private var cameraLightOffSinceMs: Long? = null

    // MAC currently being driven by the radar link, exposed so the bond-state
    // receiver can match the right device. Null when no link is active.
    @Volatile private var currentRadarMac: String? = null

    // True when the user un-paired the radar in system settings. The reconnect
    // loop in runRadarConnection bails out instead of looping forever; cleared
    // when the user re-pairs (next bond state == BONDED) or restarts the app.
    @Volatile private var bondLost = false

    // Last time the V2 stream produced a frame, set inside the decode loop and
    // read by the watchdog. 0 means no frame has been seen yet on this link.
    @Volatile private var lastV2FrameMs: Long = 0L

    // Set true when the current connection reaches the V2 decode loop. Read
    // by runRadarConnection after connectAndRun returns to decide whether to
    // reset the reconnect backoff.
    @Volatile private var lastConnectionReachedDecode = false

    // ── Walk-away alarm state ────────────────────────────────────────────────
    // See WalkAwayDecider for the decision logic. The service owns all
    // mutable fields and samples them from a separate 2 s tick coroutine so
    // the alarm can evaluate while the radar is disconnected and no
    // RadarState is flowing.
    /** Monotonic ms of the last radar GATT disconnect. Null when radar is
     *  currently connected or has never been off this session. */
    @Volatile private var radarOffSinceMs: Long? = null
    /** Running total of ms the radar has been CONNECTED this session, used
     *  as the cold-start grace gate. Integrated on each connect→disconnect
     *  transition rather than per-tick, so short-lived connections that
     *  end within one tick still contribute their full duration. */
    @Volatile private var sessionRadarConnectedMs: Long = 0L
    /** Monotonic ms of the last connect transition, or null when the
     *  radar is not currently connected. Set in [markRadarConnected],
     *  consumed in [markRadarDisconnected]. */
    @Volatile private var radarConnectStartMs: Long? = null
    /** True once a dashcam advert has been observed after the most recent
     *  radar-off event; distinguishes "dashcam still on bike" from "rider
     *  took everything inside" (dashcam went silent as it went out of
     *  phone range). Cleared on radar reconnect. */
    @Volatile private var dashcamSeenSinceRadarOff = false
    /** Monotonic ms of the last walk-away notification fire. Null until
     *  first fire this session. Cleared on radar reconnect and on
     *  auto-dismiss. */
    @Volatile private var lastWalkAwayFireMs: Long? = null
    /** True when the user tapped Dismiss (or the notification itself) on
     *  the last walk-away fire; blocks refire until radar reconnects. */
    @Volatile private var walkAwayDismissed = false
    /** Single-slot job that clears [walkAwayDismissed] after a snooze
     *  window expires, re-arming the decider. AtomicReference makes
     *  the cancel-then-replace pattern atomic across the main thread
     *  (notification action handlers) and GATT callback / IO threads
     *  (lifecycle transitions in [markRadarConnected]). */
    private val walkAwaySnoozeJob = AtomicReference<Job?>(null)
    /** Looping alarm-stream ringtone played alongside the walk-away
     *  notification; null when not playing. The notification channel's
     *  audio attributes are normalised to USAGE_NOTIFICATION on modern
     *  Pixel/Android, which DND silences even with mBypassDnd=true.
     *  We play the alarm tone explicitly with USAGE_ALARM so it routes
     *  through the alarm stream and follows the user's alarm-volume
     *  policy regardless of DND state. */
    @Volatile private var walkAwayRingtone: Ringtone? = null
    /** Audio-focus token held while [walkAwayRingtone] is playing.
     *  Released in [stopWalkAwayAlarmTone]. */
    @Volatile private var walkAwayAudioFocusRequest: AudioFocusRequest? = null
    /** Pre-alert STREAM_ALARM volume, captured before the walk-away tone
     *  forces the alarm stream to max so the rider can't sleep through a
     *  forgotten-dashcam alert just because their alarm slider was low.
     *  Restored in [stopWalkAwayAlarmTone]. Null when no override is in
     *  effect. */
    @Volatile private var walkAwaySavedAlarmVolume: Int? = null
    /** Single-slot job that stops the alarm tone after
     *  [WALKAWAY_RINGTONE_CAP_MS] even if the user hasn't dismissed.
     *  The decider's rate limit re-fires the notification later, which
     *  re-starts the tone for another capped window. Bystander courtesy
     *  vs missed-alarm trade-off: a series of capped bursts is harder to
     *  ignore than continuous audio, and less of a nuisance to others. */
    private var walkAwayRingtoneCapJob: Job? = null

    private var bondReceiverRegistered = false
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val mac = device?.address ?: return
            val expected = currentRadarMac ?: return
            if (!mac.equals(expected, ignoreCase = true)) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            when (state) {
                BluetoothDevice.BOND_NONE -> onRadarBondLost(mac)
                BluetoothDevice.BOND_BONDED -> {
                    if (bondLost) {
                        Log.i(TAG_RADAR, "radar re-paired ($mac); allowing reconnect")
                        bondLost = false
                    }
                }
            }
        }
    }

    // Overlay refs for orientation change updates (set/cleared on Main thread)
    @Volatile private var overlayWm: WindowManager? = null
    @Volatile private var overlayViewRef: RadarOverlayView? = null

    // Capture log (written from GATT callback threads + coroutine threads)
    private val captureLogLock = Any()
    @Volatile private var captureLogWriter: PrintWriter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ClosePassStateBus.reset()
        rideStats = RideStatsAccumulator()
        rideSummaryDiscoveredSlugs.clear()
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
        registerBondReceiver()
        scope.launch { kickstartFromCache() }
        launchWalkAwayTick()
        launchDashcamRefresh()
        launchRideSummaryPublishLoop()
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
            ACTION_WALKAWAY_DISMISS -> {
                Log.i(TAG, "walk-away dismissed")
                walkAwayDismissed = true
                walkAwaySnoozeJob.getAndSet(null)?.cancel()
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_WALKAWAY_ID)
                stopWalkAwayAlarmTone()
            }
            ACTION_WALKAWAY_SNOOZE -> {
                Log.i(TAG, "walk-away snoozed for ${WALKAWAY_SNOOZE_MS / 1000}s")
                walkAwayDismissed = true
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_WALKAWAY_ID)
                stopWalkAwayAlarmTone()
                val newJob = scope.launch {
                    delay(WALKAWAY_SNOOZE_MS)
                    // Clear both gates so the decider can re-evaluate
                    // cleanly: treat the snooze as a full re-arm for this
                    // episode rather than "it's been 2 minutes, fire
                    // again immediately".
                    walkAwayDismissed = false
                    lastWalkAwayFireMs = null
                }
                walkAwaySnoozeJob.getAndSet(newJob)?.cancel()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseExpiryJob?.cancel()
        cameraLightJob?.cancel()
        unregisterEventScan()
        unregisterBondReceiver()
        closeCaptureLog()
        RadarStateBus.clear()
        stopWalkAwayAlarmTone()
        scope.cancel()
        // Walk-away and bond-lost notifications survive stopForeground; clear
        // after scope.cancel() so no in-flight coroutine can re-emit them.
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_WALKAWAY_ID)
        nm.cancel(NOTIF_BOND_LOST_ID)
        // Companion-object cache survives across service instances within the
        // same process; clear it so Stop = clean slate for MAC->slug resolution.
        macToSlug.clear()
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

        // Always-on PendingIntent scan (registerEventScan) covers ongoing
        // discovery and post-pairing recovery via BatteryScanReceiver, so
        // the active kickstart is only needed when the cache is empty
        // (first-run or post-clear).
        if (known.isNotEmpty()) return

        val fresh = scanForDevices(timeoutMs = 3_000)
        if (fresh.isNotEmpty()) {
            saveKnownDevices(fresh)
            for ((name, mac) in fresh) scheduleRead(name, mac)
        }
    }

    // ── battery read scheduling + execution ──────────────────────────────────

    private fun scheduleRead(name: String, mac: String) {
        // Always try to keep the radar link alive for rear devices.
        if (isRearDevice(name)) maybeStartRadarLink(name, mac)

        if (isRearDevice(name) && (radarGattActive || radarJob?.isActive == true)) {
            Log.d(TAG, "skip $name (radar gatt active, piggyback will read instead)")
            return
        }

        // Start or gate the camera light link when this is the configured dashcam.
        val isDashcam = prefs.dashcamMac?.equals(mac, ignoreCase = true) == true
        if (isDashcam && prefs.autoLightModeEnabled) maybeStartCameraLightLink(name, mac)
        if (isDashcam && (cameraLightGattActive || cameraLightJob?.isActive == true)) {
            BatteryStateBus.markSeen(slug(name), System.currentTimeMillis())
            Log.d(TAG, "skip $name (camera light gatt active)")
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
        launchBatteryRead(name, mac)
    }

    /**
     * Real GATT battery read, bypassing the SharedPrefs throttle but still
     * gated by [ATTEMPT_COOLDOWN_MS] so back-to-back fires from different
     * paths don't stack. Use this for liveness probes (e.g. dashcam
     * periodic refresh) where [scheduleRead]'s `markSeen` shortcut
     * would falsely keep the entry fresh without an actual sighting.
     */
    private fun launchBatteryRead(name: String, mac: String) {
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
                var gattClosed = false
                var gatt: BluetoothGatt? = null
                fun closeOnce() {
                    if (gattClosed) return
                    gattClosed = true
                    val g = gatt ?: return
                    try { g.disconnect() } catch (_: Throwable) {}
                    try { g.close() } catch (_: Throwable) {}
                }
                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                closeOnce()
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
                gatt = device.connectGatt(this@BikeRadarService, false, cb, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    if (!done) { done = true; cont.resume(null) }
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { closeOnce() }
            }
        }
    }

    // ── radar link ────────────────────────────────────────────────────────────

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bondReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        try { unregisterReceiver(bondReceiver) } catch (_: Throwable) {}
        bondReceiverRegistered = false
    }

    /**
     * Called when the radar's bond is removed in system Bluetooth settings.
     * Stops the reconnect loop (which would otherwise spin forever against a
     * peer that will refuse the LESC handshake) and posts a notification so
     * the user knows why the link went silent.
     */
    private fun onRadarBondLost(mac: String) {
        Log.w(TAG_RADAR, "radar bond removed ($mac); stopping reconnect loop")
        bondLost = true
        radarJob?.cancel()
        radarJob = null
        markRadarDisconnected()
        currentRadarMac = null
        notifyBondLost()
    }

    private fun notifyBondLost() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
        val piFlags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val openSettings = PendingIntent.getActivity(
            this, BOND_NOTIF_REQ,
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bike Radar")
            .setContentText("Radar pairing was removed. Re-pair in Bluetooth settings to resume.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openSettings)
            .build()
        nm.notify(NOTIF_BOND_LOST_ID, notif)
    }

    @Synchronized
    private fun maybeStartRadarLink(name: String, mac: String) {
        if (radarJob?.isActive == true) return
        if (bondLost) {
            Log.d(TAG_RADAR, "skip radar link start: bond lost, waiting for re-pair")
            return
        }
        Log.i(TAG_RADAR, "starting radar link to $name $mac")
        radarJob = scope.launch { runRadarConnection(mac, name) }
    }

    @Synchronized
    private fun maybeStartCameraLightLink(name: String, mac: String) {
        if (cameraLightJob?.isActive == true) return
        Log.i(TAG_LIGHT, "starting camera light link to $name $mac")
        cameraLightJob = scope.launch { runCameraLightConnection(mac, name) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runCameraLightConnection(mac: String, name: String) {
        val btMgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val device = try {
            btMgr.adapter?.getRemoteDevice(mac)
        } catch (_: Throwable) { null } ?: return

        var backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
        try {
            while (true) {
                if (!prefs.autoLightModeEnabled) {
                    Log.i(TAG_LIGHT, "auto light mode disabled; exiting link")
                    return
                }
                Log.i(TAG_LIGHT, "connect attempt to $name $mac")
                val quickReconnect = connectAndRunCameraLight(device, name)
                cameraLightGattActive = false
                val delayMs = if (quickReconnect) RADAR_QUICK_RECONNECT_MS else backoffMs
                Log.i(TAG_LIGHT, "reconnecting in ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
                if (!quickReconnect) {
                    backoffMs = (backoffMs * 2).coerceAtMost(RADAR_RECONNECT_BACKOFF_MAX_MS)
                } else {
                    backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
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
            try { g.disconnect() } catch (_: Throwable) {}
            try { g.close() } catch (_: Throwable) {}
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
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

        gatt = device.connectGatt(this, true, cb, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) { Log.w(TAG_LIGHT, "connectGatt returned null"); return false }

        val queueJob = scope.launch { queue.run() }

        var sunsetJob: Job? = null
        return try {
            val ok = servicesReady.await()
            if (!ok) { Log.w(TAG_LIGHT, "service discovery failed"); return false }

            cameraLightGattActive = true
            val offSince = cameraLightOffSinceMs
            if (offSince != null &&
                System.currentTimeMillis() - offSince >= CAMERA_LIGHT_OVERRIDE_DEADBAND_MS
            ) {
                cameraLightUserOverride = false
                Log.i(TAG_LIGHT, "override cleared after ${(System.currentTimeMillis() - offSince) / 1000}s off")
            }
            cameraLightOffSinceMs = null
            Log.i(TAG_LIGHT, "connected, running handshake")

            val handshakeOk = RadarUnlock.runHandshake(
                gatt, queue, notifyChannel, DeviceVariant.FRONT_CAMERA,
            ) { msg -> Log.d(TAG_LIGHT, msg) }

            if (!handshakeOk) {
                Log.w(TAG_LIGHT, "handshake failed; closing for quick reconnect")
                gatt.disconnect()
                return true
            }

            Log.i(TAG_LIGHT, "handshake complete; subscribing mode-state notify")
            val ch14 = gatt.getService(Uuids.SVC_CONTROL)?.getCharacteristic(Uuids.SETTINGS_14)
            if (ch14 != null) queue.writeCccd(gatt, ch14)

            val controller = CameraLightController(gatt, queue)
            val nowMs = System.currentTimeMillis()
            val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/London"))
            val sunsetMs = SunsetCalculator.sunsetEpochMs(today)
            val afterSunset = sunsetMs != null && nowMs >= sunsetMs
            val initialMode = if (afterSunset) prefs.cameraLightNightMode else prefs.cameraLightDayMode
            val sunsetLog = if (sunsetMs != null) "${sunsetMs - nowMs}ms away" else "unknown"
            if (!cameraLightUserOverride) {
                val applied = applyModeWithRetry(controller, initialMode)
                Log.i(TAG_LIGHT, "initial mode=$initialMode applied=$applied sunset=$sunsetLog")
                if (applied) cameraLightLastWrittenMode = initialMode
                else postLightModeFailNotification(initialMode)
            } else {
                Log.i(TAG_LIGHT, "initial mode skipped (manual override active) sunset=$sunsetLog")
            }

            if (sunsetMs != null && nowMs < sunsetMs) {
                val msToSunset = sunsetMs - nowMs
                sunsetJob = scope.launch {
                    kotlinx.coroutines.delay(msToSunset)
                    if (cameraLightGattActive && !cameraLightUserOverride) {
                        val nightMode = prefs.cameraLightNightMode
                        val nightOk = applyModeWithRetry(controller, nightMode)
                        Log.i(TAG_LIGHT, "sunset mode=$nightMode applied=$nightOk")
                        if (nightOk) cameraLightLastWrittenMode = nightMode
                        else postLightModeFailNotification(nightMode)
                    }
                }
            }

            val lightMac = gatt.device?.address
            val lightSlug = slug(name)
            if (lightMac != null) macToSlug[lightMac] = lightSlug

            for ((uuid, bytes) in notifyChannel) {
                when (uuid) {
                    Uuids.CHAR_BATTERY -> {
                        val pct = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                        BatteryStateBus.update(BatteryEntry(lightSlug, name, pct))
                        if (!prefs.isPaused) maybePublishBatteryToHa(name, pct)
                    }
                    Uuids.SETTINGS_14 -> {
                        val mode = CameraLightController.parseModeStateNotify(bytes) ?: continue
                        Log.d(TAG_LIGHT, "mode-state notify: $mode")
                        val expected = cameraLightLastWrittenMode
                        if (expected != null && mode != expected && !cameraLightUserOverride) {
                            cameraLightUserOverride = true
                            Log.i(TAG_LIGHT, "override detected: expected=$expected device=$mode")
                        }
                    }
                }
            }
            false
        } finally {
            cameraLightGattActive = false
            if (cameraLightOffSinceMs == null) cameraLightOffSinceMs = System.currentTimeMillis()
            sunsetJob?.cancel()
            queueJob.cancel()
            queue.cancel()
            closeOnce()
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
        Log.w(TAG_RADAR, "BluetoothGatt.refresh() unavailable: $t")
        false
    }

    @SuppressLint("MissingPermission")
    private suspend fun runRadarConnection(mac: String, name: String) {
        val btMgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val device = try {
            btMgr.adapter?.getRemoteDevice(mac)
        } catch (_: Throwable) { null } ?: return

        currentRadarMac = mac
        var backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
        try {
            while (true) {
                if (bondLost) {
                    Log.i(TAG_RADAR, "bond lost for $mac; reconnect loop suspended")
                    return
                }
                Log.i(TAG_RADAR, "connect attempt to $name $mac")
                lastConnectionReachedDecode = false
                val quickReconnect = connectAndRun(device, name)
                markRadarDisconnected()
                if (bondLost) {
                    Log.i(TAG_RADAR, "bond lost during attempt; exiting reconnect loop")
                    return
                }
                if (lastConnectionReachedDecode) {
                    // Healthy session — reset the backoff so the next reconnect
                    // is fast.
                    backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
                }
                val delayMs = when {
                    quickReconnect -> RADAR_QUICK_RECONNECT_MS
                    else -> backoffMs
                }
                val tag = when {
                    quickReconnect -> " (post-ABORT)"
                    else -> " (backoff=${backoffMs}ms)"
                }
                Log.i(TAG_RADAR, "reconnecting in ${delayMs}ms$tag")
                kotlinx.coroutines.delay(delayMs)
                if (!quickReconnect) {
                    backoffMs = (backoffMs * 2).coerceAtMost(RADAR_RECONNECT_BACKOFF_MAX_MS)
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
            try { g.disconnect() } catch (_: Throwable) {}
            try { g.close() } catch (_: Throwable) {}
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                clog("# conn state: status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        markRadarDisconnected()
                        queue.cancel()
                        notifyChannel.close()
                        if (!servicesReady.isCompleted) servicesReady.complete(false)
                        closeOnce()
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

            markRadarConnected()
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
                val closePassDetector = ClosePassDetector()
                var closePassDiscoveryPublished = false
                var closePassDiscoveryInFlight = false
                val sessionStartMs = System.currentTimeMillis()
                var seenDashcamThisSession = false
                var lastLoggedDashcamStatus: DashcamStatus? = null
                val ticker = flow { while (true) { emit(Unit); delay(DASHCAM_TICK_MS) } }
                try {
                    combine(RadarStateBus.state, BatteryStateBus.entries, ticker) { s, b, _ -> s to b }
                        .collect { (state, batteries) ->
                        val now = System.currentTimeMillis()

                        // Dashcam refresh + status update runs regardless
                        // of radar state. The walk-away alarm path
                        // specifically depends on the dashcam entry
                        // staying current AFTER the radar goes off, and
                        // the in-app main page glyph reads BatteryStateBus
                        // directly - both would silently desync if the
                        // refresh sat below the radar-NONE early return.
                        val dashcamSlug = prefs.dashcamMac?.let { mac ->
                            macToSlug[mac]
                                ?: macToSlug[mac.uppercase(Locale.ROOT)]
                                ?: prefs.dashcamDisplayName?.let { slug(it) }
                        }
                        val dashcamEntry = dashcamSlug?.let { batteries[it] }
                        if (dashcamEntry != null) seenDashcamThisSession = true

                        val cfg = DashcamStatusDeriver.Config(
                            warnWhenOff = prefs.dashcamWarnWhenOff,
                            selectedSlug = dashcamSlug,
                        )
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

                        if (state.source == DataSource.NONE) return@collect

                        rideStats.observeFrame(state)

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
                                    Log.w(TAG_RADAR, "overlay addView failed (permission TOCTOU?): $t")
                                }
                            } else {
                                clog("# overlay: SYSTEM_ALERT_WINDOW not granted")
                            }
                        }

                        view.setVisualMaxM(prefs.visualMaxDistanceM)
                        view.setAlertMaxM(prefs.alertMaxDistanceM)
                        view.setAdaptiveAlerts(prefs.adaptiveAlertsEnabled)
                        view.setPrecog(prefs.precogEnabled)
                        view.setState(state)

                        val threshold = prefs.batteryLowThresholdPct
                        val lowSlugs = batteries.values
                            .filter { it.pct < threshold && now - it.readAtMs < BATTERY_STALE_MS }
                            .map { it.slug }.toSet()
                        view.setBatteryLow(lowSlugs, prefs.batteryShowLabels)

                        if (!prefs.isPaused) {
                            when (val ev = alerts.decide(state.vehicles, prefs.alertMaxDistanceM, now, state.bikeSpeedMs)) {
                                is AlertDecider.Event.Beep           -> beeper.play(ev.count)
                                AlertDecider.Event.Clear             -> beeper.playClear()
                                AlertDecider.Event.UrgentApproach    -> beeper.playUrgent()
                                AlertDecider.Event.None              -> {}
                            }
                        } else {
                            alerts.reset()
                        }

                        // Close-pass detection: strict-gated per-track state
                        // machine that only emits an event for genuine
                        // overtakes at <1 m (default). Runs regardless of
                        // the pause state — pause silences the beeper but
                        // doesn't turn off data logging. Config is re-read
                        // every frame so Settings changes take effect
                        // immediately without losing per-track state.
                        val cpConfig = ClosePassDetector.Config(
                            enabled = prefs.closePassLoggingEnabled && ha.isConfigured(),
                            riderSpeedFloorMs = prefs.closePassRiderSpeedFloorMs,
                            closingSpeedFloorMs = prefs.closePassClosingSpeedFloorMs,
                            emitMinRangeXM = prefs.closePassEmitMinRangeXM,
                        )
                        // Publish discovery eagerly so HA has time to register the
                        // entity before any non-retained event payload arrives;
                        // events fired into a not-yet-registered topic get dropped.
                        // The BLE-advertised name is used (same source as battery
                        // discovery) so HA derives a stable, device-aligned slug.
                        // Flip the persisted flag on success only; retry on failure
                        // if HA was momentarily unreachable. The in-flight guard
                        // suppresses re-issue while the publish is pending.
                        if (cpConfig.enabled && !closePassDiscoveryPublished && !closePassDiscoveryInFlight) {
                            val radarMac = currentRadarMac
                            val radarSlug = radarMac?.let { macToSlug[it] }
                                ?: radarMac?.let { macToSlug[it.uppercase(Locale.ROOT)] }
                            if (radarSlug != null) {
                                closePassDiscoveryInFlight = true
                                launch(Dispatchers.IO) {
                                    val ok = ha.publishClosePassDiscovery(radarSlug, name)
                                    if (ok) closePassDiscoveryPublished = true
                                    else Log.w(TAG, "close-pass discovery publish failed; will retry")
                                    closePassDiscoveryInFlight = false
                                }
                            }
                        }
                        val cpEvents = closePassDetector.decide(
                            state.vehicles, state.bikeSpeedMs, now, cpConfig,
                        )
                        if (cpEvents.isNotEmpty()) {
                            ClosePassStateBus.increment(cpEvents.size)
                            for (ev in cpEvents) rideStats.observeClosePass(ev)
                            val radarMac = currentRadarMac
                            val radarSlug = radarMac?.let { macToSlug[it] }
                                ?: radarMac?.let { macToSlug[it.uppercase(Locale.ROOT)] }
                            if (radarSlug != null) {
                                launch(Dispatchers.IO) {
                                    for (ev in cpEvents) {
                                        val json = org.json.JSONObject()
                                            .put("ts", java.time.Instant.ofEpochMilli(ev.timestampMs).toString())
                                            .put("min_range_x_m", String.format(Locale.US, "%.2f", ev.minRangeXM).toFloat())
                                            .put("side", ev.side.name.lowercase(Locale.ROOT))
                                            .put("range_y_at_min_m", String.format(Locale.US, "%.1f", ev.rangeYAtMinM).toFloat())
                                            .put("closing_speed_kmh", ev.closingSpeedKmh)
                                            .put("rider_speed_kmh", ev.riderSpeedKmh)
                                            .put("vehicle_size", ev.vehicleSize.name)
                                            .put("threshold_m", ev.thresholdArmedM)
                                            .put("severity", ev.severity.name.lowercase(Locale.ROOT))
                                        val ok = ha.publishClosePassEvent(radarSlug, json)
                                        if (!ok) Log.w(TAG, "close-pass publish failed")
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    beeper.release()
                    if (overlayAdded) {
                        try { wm.removeView(view); clog("# overlay removed") }
                        catch (t: Throwable) { Log.w(TAG_RADAR, "removeView failed: $t") }
                    }
                    overlayWm = null
                    overlayViewRef = null
                }
            }

            val rearMac = gatt.device?.address
            val v2Dec = RadarV2Decoder()
            var v2FrameCount = 0

            // Mark this connection as healthy so the reconnect loop resets
            // its backoff. Initialise the watchdog clock to "now" so we give
            // the first frame a fair chance to arrive.
            lastConnectionReachedDecode = true
            lastV2FrameMs = System.currentTimeMillis()

            // Drop the connection interval from BALANCED to LOW_POWER once
            // the V2 stream is up: the radar pushes notifications at its own
            // cadence, so a tighter interval just wastes the phone radio.
            try {
                val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                if (!ok) Log.w(TAG_RADAR, "requestConnectionPriority(LOW_POWER) returned false")
            } catch (t: Throwable) {
                Log.w(TAG_RADAR, "requestConnectionPriority threw: $t")
            }

            // Data-flow watchdog: if no V2 frame arrives for V2_FRAME_STALL_MS,
            // tear down the GATT so the outer loop can reconnect. Catches the
            // case where the stack thinks we are still connected but the radar
            // has gone silent.
            val capturedGatt = gatt
            watchdogJob = scope.launch {
                while (true) {
                    delay(V2_WATCHDOG_TICK_MS)
                    val last = lastV2FrameMs
                    if (last == 0L) continue
                    val ageMs = System.currentTimeMillis() - last
                    if (ageMs > V2_FRAME_STALL_MS) {
                        Log.w(TAG_RADAR, "V2 stream silent for ${ageMs}ms; tearing down GATT")
                        try { capturedGatt.disconnect() } catch (_: Throwable) {}
                        return@launch
                    }
                }
            }

            for ((uuid, bytes) in notifyChannel) {
                when (uuid) {
                    Uuids.RADAR_V2 -> {
                        lastV2FrameMs = System.currentTimeMillis()
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
            watchdogJob?.cancel()
            overlayJob?.cancel()
            queue.cancel()
            queueJob.cancel()
            markRadarDisconnected()
            RadarStateBus.clear()
            // Fire-and-forget final flush of the ride summary so HA sees
            // the latest values before the next reconnect's backoff delay.
            scope.launch(Dispatchers.IO) { publishRideSummaryIfChanged() }
            closeOnce()
            closeCaptureLog()
        }
    }

    // ── camera light failure feedback ─────────────────────────────────────────

    /**
     * Attempts [controller.setMode] up to 3 times with increasing delays.
     * Returns true on the first success; false if all 3 attempts fail.
     */
    private suspend fun applyModeWithRetry(
        controller: CameraLightController,
        mode: CameraLightMode,
    ): Boolean {
        if (controller.setMode(mode)) return true
        kotlinx.coroutines.delay(500 + (100 * (Math.random() * 2 - 1)).toLong())
        if (controller.setMode(mode)) return true
        kotlinx.coroutines.delay(1500 + (300 * (Math.random() * 2 - 1)).toLong())
        return controller.setMode(mode)
    }

    private suspend fun postLightModeFailNotification(mode: CameraLightMode) {
        val modeName = when (mode) {
            CameraLightMode.HIGH -> "High"
            CameraLightMode.MEDIUM -> "Medium"
            CameraLightMode.LOW -> "Low"
            CameraLightMode.NIGHT_FLASH -> "Night flash"
            CameraLightMode.DAY_FLASH -> "Day flash"
            CameraLightMode.OFF -> "Off"
        }

        ensureNotificationChannel()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, LIGHT_FAIL_CHANNEL_ID)
            .setContentTitle("Front light")
            .setContentText("Couldn't switch to $modeName - check connection.")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setVibrate(LIGHT_FAIL_VIBRATE_PATTERN)
            .build()
        nm.notify(NOTIF_LIGHT_FAIL_ID, notif)

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
        // FIRST_MATCH + MATCH_LOST: host process wakes once when a known
        // device appears and once when it disappears, instead of for every
        // advert (~1 Hz/device under ALL_MATCHES). Battery values for the
        // radar still refresh continuously via its GATT 0x2a19 notify;
        // dashcam battery refreshes on each new presence episode.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(
                ScanSettings.CALLBACK_TYPE_FIRST_MATCH or
                    ScanSettings.CALLBACK_TYPE_MATCH_LOST
            )
            .build()
        val rc = try {
            scanner.startScan(filters, settings, buildScanPendingIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "startScan threw", t); -1
        }
        scanRegistered = (rc == 0)
        val offloaded = try { mgr.adapter.isOffloadedFilteringSupported } catch (_: Throwable) { false }
        Log.i(TAG, "event scan registered rc=$rc offloaded=$offloaded (first-match+match-lost)")
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
        // Drop legacy walk-away channels. Channel properties (sound,
        // vibration pattern, importance) are immutable post-creation,
        // so any code change has to migrate to a fresh ID and delete
        // the old one. The user's per-channel preferences (e.g. Override
        // Do Not Disturb) reset on this migration; the dashcam settings
        // row deeplinks back to the new channel for re-grant.
        WALKAWAY_CHANNEL_IDS_LEGACY.forEach { id ->
            if (nm.getNotificationChannel(id) != null) {
                nm.deleteNotificationChannel(id)
            }
        }
        if (nm.getNotificationChannel(LIGHT_FAIL_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    LIGHT_FAIL_CHANNEL_ID,
                    "Front light",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Alerts when the front camera/light mode could not be applied."
                    enableVibration(true)
                    vibrationPattern = LIGHT_FAIL_VIBRATE_PATTERN
                }
            )
        }

        if (nm.getNotificationChannel(WALKAWAY_CHANNEL_ID) == null) {
            // HIGH importance, no sound and no vibration on the channel.
            // Both modalities are driven explicitly from the FIRE path:
            // - audio: Ringtone with USAGE_ALARM (channel sound is
            //   normalised to USAGE_NOTIFICATION, which DND silences).
            // - haptics: Vibrator service (channel vibration is
            //   suppressed under DND when canBypassDnd is false, and
            //   the migration to v3 resets the user's bypass grant).
            // Driving both explicitly means the alarm fires through DND
            // regardless of the user's per-channel preferences.
            val ch = NotificationChannel(
                WALKAWAY_CHANNEL_ID,
                "Dashcam left on bike",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description =
                    "Alerts when the radar turns off but the dashcam is still broadcasting from the bike."
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    // ── walk-away alarm tone ─────────────────────────────────────────────────

    /**
     * Start the looping alarm-stream tone that accompanies a walk-away
     * notification. Idempotent: an already-playing call is a no-op so a
     * refire mid-tone doesn't restart the cap. @Synchronized with
     * [stopWalkAwayAlarmTone] prevents a concurrent FIRE/DISMISS from
     * leaving a tone playing past dismissal.
     */
    /**
     * Vibrate the walk-away pattern explicitly via the Vibrator
     * service. Channel-level vibration is suppressed by DND when the
     * channel doesn't bypass DND; explicit Vibrator calls are not.
     */
    private fun vibrateWalkAwayPattern() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(WALKAWAY_VIBRATE_PATTERN, -1)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try { vibrator.vibrate(effect, attrs) } catch (t: Throwable) {
            Log.w(TAG, "vibrate failed: $t")
        }
    }

    @Synchronized
    private fun startWalkAwayAlarmTone() {
        vibrateWalkAwayPattern()
        if (walkAwayRingtone?.isPlaying == true) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            // Empty listener is intentional: a walk-away alarm should
            // not duck or pause for transient focus loss. The whole
            // point is to keep alerting until the rider acts.
            .setOnAudioFocusChangeListener { }
            .build()
        if (am.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "walk-away audio focus denied; playing without focus")
        }

        // Bundled bike-bell sound, attributed in SettingsLicenses
        // ("Audio assets") and in res/raw/walkaway_alarm_license.txt.
        // Using a bundled asset rather than the system default alarm
        // (the rider's morning-wakeup tone) so the alarm is recognisable
        // as a walk-away alert and harder to ignore as routine.
        val uri = android.net.Uri.parse(
            "android.resource://${packageName}/${R.raw.walkaway_alarm}",
        )
        val rt = try {
            RingtoneManager.getRingtone(this, uri).apply {
                audioAttributes = attrs
                isLooping = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ringtone setup failed: $t")
            try { am.abandonAudioFocusRequest(focusReq) } catch (_: Throwable) {}
            return
        }
        // Force the alarm stream to max for the duration of the alert.
        // Mirrors the alarm-clock pattern: a rider whose phone alarm
        // volume is set low for sleep shouldn't lose a £200 dashcam to
        // their bedside-tone preference. Saved level is restored in
        // stopWalkAwayAlarmTone. Best-effort; some OEMs reject volume
        // writes from background services and that's fine.
        val savedAlarmVolume = try {
            am.getStreamVolume(AudioManager.STREAM_ALARM)
        } catch (_: Throwable) { null }
        if (savedAlarmVolume != null) {
            try {
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "alarm-stream max-out failed: $t")
            }
        }

        try {
            rt.play()
        } catch (t: Throwable) {
            Log.w(TAG, "ringtone play failed: $t")
            try { am.abandonAudioFocusRequest(focusReq) } catch (_: Throwable) {}
            // Roll back the alarm-stream override so a play() failure
            // doesn't leave the rider's morning alarm stuck at max.
            if (savedAlarmVolume != null) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                } catch (_: Throwable) {}
            }
            return
        }

        // Commit the new state only after play() succeeds, so a partial
        // setup never leaks a focus token or a half-initialised ringtone.
        walkAwayAudioFocusRequest = focusReq
        walkAwayRingtone = rt
        walkAwaySavedAlarmVolume = savedAlarmVolume
        walkAwayRingtoneCapJob?.cancel()
        walkAwayRingtoneCapJob = scope.launch {
            delay(WALKAWAY_RINGTONE_CAP_MS)
            stopWalkAwayAlarmTone()
        }
        Log.i(TAG, "walk-away alarm tone started (cap=${WALKAWAY_RINGTONE_CAP_MS / 1000}s)")
    }

    /**
     * Stop the alarm tone and release audio focus. Safe to call
     * unconditionally - all paths null-check.
     */
    @Synchronized
    private fun stopWalkAwayAlarmTone() {
        walkAwayRingtoneCapJob?.cancel()
        walkAwayRingtoneCapJob = null
        walkAwayRingtone?.let {
            try { it.stop() } catch (_: Throwable) {}
        }
        walkAwayRingtone = null
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        walkAwayAudioFocusRequest?.let { req ->
            try { am.abandonAudioFocusRequest(req) } catch (_: Throwable) {}
        }
        walkAwayAudioFocusRequest = null
        walkAwaySavedAlarmVolume?.let { saved ->
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "alarm-stream restore failed: $t")
            }
        }
        walkAwaySavedAlarmVolume = null
    }

    // ── dashcam liveness probe ───────────────────────────────────────────────

    /**
     * Periodic real-GATT probe of the configured dashcam, decoupled from
     * the radar connection's overlay coroutine. The collector inside
     * overlayJob only runs while the radar is connected; once the radar
     * disconnects, that coroutine is cancelled and its dashcam refresh
     * dies with it. The walk-away alarm and the in-app main-page glyph
     * (which reads BatteryStateBus directly) both need the dashcam
     * entry to keep advancing across the disconnect, so this lives at
     * service scope and runs from onCreate to onDestroy.
     */
    private fun launchDashcamRefresh() {
        scope.launch {
            while (true) {
                val mac = prefs.dashcamMac
                val name = prefs.dashcamDisplayName
                val gateOpen = IdleGate.shouldRefreshDashcam(
                    radarGattActive = radarGattActive,
                    radarOffSinceMs = radarOffSinceMs,
                    nowMs = System.currentTimeMillis(),
                )
                if (gateOpen && mac != null && !name.isNullOrEmpty()) {
                    val slug = resolveDashcamSlug()
                    val entry = slug?.let { BatteryStateBus.entries.value[it] }
                    val now = System.currentTimeMillis()
                    val ageMs = entry?.let { now - it.readAtMs } ?: Long.MAX_VALUE
                    if (ageMs >= DASHCAM_REFRESH_MS) launchBatteryRead(name, mac)
                }
                delay(DASHCAM_TICK_MS)
            }
        }
    }

    // ── walk-away alarm ──────────────────────────────────────────────────────

    private fun launchWalkAwayTick() {
        scope.launch {
            var prevTickMs = System.currentTimeMillis()
            while (true) {
                // Only the off-episode path needs 2 s cadence; the connected
                // path just needs to clear stale state once after reconnect,
                // and the never-paired-in-session path needs nothing at all.
                // Slow ticks 15× when idle to drop background CPU wake-ups.
                val activeTracking = radarOffSinceMs != null
                delay(if (activeTracking) WALKAWAY_TICK_MS else WALKAWAY_IDLE_TICK_MS)
                val now = System.currentTimeMillis()
                val elapsed = now - prevTickMs
                prevTickMs = now
                tickWalkAwayState(now, elapsed)
                evaluateWalkAway(now)
            }
        }
    }

    private fun launchRideSummaryPublishLoop() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(RIDE_SUMMARY_PUBLISH_PERIOD_MS)
                publishRideSummaryIfChanged()
            }
        }
    }

    /**
     * Publishes the current ride-summary snapshot if anything changed since
     * the last successful publish. Called periodically by the publish loop
     * and ad-hoc on radar disconnect for a snappier final value.
     *
     * Discovery is published once per slug per service lifetime, gated by
     * [rideSummaryDiscoveredSlugs]. A discovery failure is rolled back so
     * the next call retries.
     */
    private suspend fun publishRideSummaryIfChanged() {
        if (!ha.isConfigured()) return
        if (!rideStats.changedSinceLast()) return
        val mac = currentRadarMac ?: return
        val slug = macToSlug[mac]
            ?: macToSlug[mac.uppercase(Locale.ROOT)]
            ?: return
        val deviceName = loadKnownDevices()
            .firstOrNull { it.second.equals(mac, ignoreCase = true) }
            ?.first
            ?: "radar"

        if (rideSummaryDiscoveredSlugs.add(slug)) {
            val ok = ha.publishRideSummaryDiscovery(slug, deviceName)
            if (!ok) {
                rideSummaryDiscoveredSlugs.remove(slug)
                Log.w(TAG, "ride-summary discovery publish failed; will retry")
                return
            }
            Log.i(TAG, "ride-summary discovery published for $slug")
        }

        val ok = ha.publishRideSummaryState(slug, rideStats.snapshot())
        if (ok) rideStats.markPublished()
        else Log.w(TAG, "ride-summary state publish failed")
    }

    /** Off-instant is stamped at the actual disconnect callback so it
     *  isn't tied to tick cadence (the idle tick is 30 s; that would
     *  drift the walk-away threshold by up to 30 s). Clean-reconnect
     *  cleanup likewise fires at the connection-success site, not
     *  lazily on the next tick. */
    private fun markRadarConnected() {
        if (radarOffSinceMs != null) {
            radarOffSinceMs = null
            dashcamSeenSinceRadarOff = false
            walkAwayDismissed = false
            walkAwaySnoozeJob.getAndSet(null)?.cancel()
            lastWalkAwayFireMs = null
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_WALKAWAY_ID)
        }
        radarConnectStartMs = System.currentTimeMillis()
        radarGattActive = true
    }

    private fun markRadarDisconnected() {
        radarGattActive = false
        radarConnectStartMs?.let {
            sessionRadarConnectedMs += System.currentTimeMillis() - it
            radarConnectStartMs = null
        }
        if (radarOffSinceMs == null) {
            radarOffSinceMs = System.currentTimeMillis()
        }
    }

    private fun tickWalkAwayState(nowMs: Long, elapsedMs: Long) {
        // sessionRadarConnectedMs is integrated on connect→disconnect
        // transitions in [markRadarDisconnected], not per-tick. The idle
        // tick is 30 s; a connection that ends within that window would
        // never have its duration counted under the old per-tick scheme.

        // Once the radar is off, watch the dashcam battery bus for a
        // fresh advert to mark the "still on the bike" gate.
        val offAt = radarOffSinceMs
        if (offAt != null && !dashcamSeenSinceRadarOff) {
            val slug = resolveDashcamSlug()
            val entry = slug?.let { BatteryStateBus.entries.value[it] }
            if (entry != null && entry.readAtMs > offAt) {
                dashcamSeenSinceRadarOff = true
            }
        }
    }

    private fun evaluateWalkAway(nowMs: Long) {
        val slug = resolveDashcamSlug()
        val dashcamLastAdvertMs = slug?.let { BatteryStateBus.entries.value[it] }?.readAtMs ?: 0L
        val input = WalkAwayDecider.Input(
            nowMs = nowMs,
            config = WalkAwayDecider.Config(
                // Gate on both the dedicated toggle AND the dashcam-warn
                // master switch. If the rider explicitly said "don't
                // warn me about the dashcam at all" we respect that.
                enabled = prefs.walkAwayAlarmEnabled && prefs.dashcamWarnWhenOff
                    && slug != null,
                thresholdMs = prefs.walkAwayAlarmThresholdSec * 1000L,
            ),
            radarConnected = radarGattActive,
            radarOffSinceMs = radarOffSinceMs,
            dashcamLastAdvertMs = dashcamLastAdvertMs,
            dashcamHasAdvertedSinceRadarOff = dashcamSeenSinceRadarOff,
            sessionTotalRadarConnectedMs = sessionRadarConnectedMs,
            lastFireMs = lastWalkAwayFireMs,
            dismissedForEpisode = walkAwayDismissed,
        )
        when (WalkAwayDecider.decide(input)) {
            WalkAwayDecider.Action.FIRE -> {
                postWalkAwayNotification()
                startWalkAwayAlarmTone()
                lastWalkAwayFireMs = nowMs
            }
            WalkAwayDecider.Action.AUTO_DISMISS -> {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_WALKAWAY_ID)
                stopWalkAwayAlarmTone()
                lastWalkAwayFireMs = null
            }
            WalkAwayDecider.Action.NONE -> {}
        }
    }

    private fun resolveDashcamSlug(): String? {
        val mac = prefs.dashcamMac ?: return null
        return macToSlug[mac]
            ?: macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefs.dashcamDisplayName?.let { slug(it) }
    }

    private fun postWalkAwayNotification() {
        val piFlags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val dismissPi = PendingIntent.getBroadcast(
            this, NOTIF_WALKAWAY_DISMISS_REQ,
            Intent(this, InternalControlReceiver::class.java).apply {
                action = InternalControlReceiver.ACTION_WALKAWAY_DISMISS
            },
            piFlags,
        )
        val snoozePi = PendingIntent.getBroadcast(
            this, NOTIF_WALKAWAY_SNOOZE_REQ,
            Intent(this, InternalControlReceiver::class.java).apply {
                action = InternalControlReceiver.ACTION_WALKAWAY_SNOOZE
            },
            piFlags,
        )
        val notif = NotificationCompat.Builder(this, WALKAWAY_CHANNEL_ID)
            .setContentTitle("Dashcam left on bike")
            .setContentText(
                "Radar is off but the dashcam is still on your bike. " +
                    "Battery draining, easy to forget.",
            )
            .setSmallIcon(R.drawable.ic_videocam_off)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(WALKAWAY_VIBRATE_PATTERN)
            .addAction(0, "Dismiss", dismissPi)
            .addAction(0, "Remind in 2 min", snoozePi)
            // Tapping the notification body is treated as Dismiss; swipe-
            // dismiss via setDeleteIntent also marks the episode handled.
            .setContentIntent(dismissPi)
            .setDeleteIntent(dismissPi)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_WALKAWAY_ID, notif)
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
        val actionBroadcast = if (paused) InternalControlReceiver.ACTION_RESUME else InternalControlReceiver.ACTION_PAUSE_1H
        val piFlags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val actionPi = PendingIntent.getBroadcast(
            this, NOTIF_ACTION_REQ,
            Intent(this, InternalControlReceiver::class.java).apply { action = actionBroadcast },
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
        private const val TAG_LIGHT = "BikeRadar.Light"
        const val CHANNEL_ID = "bike_radar_min"
        // v2 channel created with alarm-stream sound; the v1 legacy id
        // is deleted on channel-ensure so an upgrade picks up sound.
        const val WALKAWAY_CHANNEL_ID = "bike_radar_walkaway_v3"
        const val LIGHT_FAIL_CHANNEL_ID = "bike_radar_light_fail"
        private val WALKAWAY_CHANNEL_IDS_LEGACY = listOf(
            "bike_radar_walkaway",
            "bike_radar_walkaway_v2",
        )
        const val NOTIF_ID = 1
        const val NOTIF_BOND_LOST_ID = 2
        const val NOTIF_WALKAWAY_ID = 3
        const val NOTIF_LIGHT_FAIL_ID = 4
        private const val BOND_NOTIF_REQ = 0xB1CE
        private const val NOTIF_WALKAWAY_DISMISS_REQ = 0xB1CF
        private const val NOTIF_WALKAWAY_SNOOZE_REQ = 0xB1D0
        private const val PREFS_THROTTLE = "bike_radar_throttle"
        private const val KEY_KNOWN = "known_devices"
        private const val KEY_LAST_TS = "last_ts"
        private const val SCAN_PI_REQ = 0xB1CC

        const val ACTION_READ_DEVICE = "es.jjrh.bikeradar.READ_DEVICE"
        const val ACTION_UPDATE_NOTIF = "es.jjrh.bikeradar.UPDATE_NOTIF"
        const val ACTION_FORCE_RECONNECT = "es.jjrh.bikeradar.FORCE_RECONNECT"
        const val ACTION_WALKAWAY_DISMISS = "es.jjrh.bikeradar.WALKAWAY_DISMISS"
        const val ACTION_WALKAWAY_SNOOZE = "es.jjrh.bikeradar.WALKAWAY_SNOOZE"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"
        private const val NOTIF_ACTION_REQ = 0xB1CD

        const val THROTTLE_MS = 5 * 60 * 1000L
        const val ATTEMPT_COOLDOWN_MS = 30 * 1000L

        // Reconnect backoff: starts fast, doubles on each consecutive failure,
        // caps at 8 s. Resets to the initial value once a connection reaches
        // the V2 decode loop. Quick-reconnect (post-handshake-ABORT) bypasses
        // backoff entirely.
        const val RADAR_RECONNECT_BACKOFF_INITIAL_MS = 1_000L
        const val RADAR_RECONNECT_BACKOFF_MAX_MS = 8_000L
        const val RADAR_QUICK_RECONNECT_MS = 1_500L

        // V2 data-flow watchdog: if no V2 notification has been observed for
        // V2_FRAME_STALL_MS, the link is considered stuck and the GATT is
        // torn down so the outer loop reconnects.
        const val V2_WATCHDOG_TICK_MS = 2_000L
        const val V2_FRAME_STALL_MS = 5_000L
        const val BATTERY_HA_HEARTBEAT_MS = 5 * 60 * 1000L

        // Ride-summary publish cadence. The accumulator only changes on
        // radar events, so most ticks short-circuit via changedSinceLast.
        // 60 s is fine-grained enough that a close-pass shows up in HA
        // within a minute, while still cheap when the rider is parked.
        const val RIDE_SUMMARY_PUBLISH_PERIOD_MS = 60_000L
        const val BATTERY_STALE_MS = 15 * 60 * 1000L
        const val MAX_CAPTURE_LOGS = 500
        const val MIN_USEFUL_LOG_BYTES = 500L

        // Dashcam presence-by-advert timing. Fresh threshold accommodates
        // SCAN_MODE_LOW_POWER batching; cold-start grace covers the window
        // between overlay activation and the first received advert.
        const val DASHCAM_TICK_MS = 2_000L
        const val DASHCAM_FRESH_MS = 30_000L
        const val DASHCAM_COLD_START_MS = 10_000L
        // Some dashcams (Garmin Vue) don't put the 0xfe1f service UUID
        // in their adverts, so the controller-level scan filter never
        // wakes BatteryScanReceiver and there's no advert-driven
        // markSeen path. Without a periodic probe the entry ages past
        // DASHCAM_FRESH_MS within seconds of the cold-start kickstart
        // read, flipping the glyph to Dropped. The dashcam-status
        // ticker fires a real GATT read every DASHCAM_REFRESH_MS so
        // the glyph reflects whether the camera is actually responding
        // - bumping readAtMs via the throttle path's markSeen would
        // falsely keep it green for up to THROTTLE_MS after a power-off,
        // defeating the alarm. Set just under DASHCAM_FRESH_MS so a
        // single failed probe is enough to flip the glyph red.
        const val DASHCAM_REFRESH_MS = 20_000L

        // Walk-away alarm tick cadence + snooze. Tick interval matches the
        // dashcam status tick so the feature reacts on the same cadence
        // riders already expect for the "dashcam off" indicator.
        const val WALKAWAY_TICK_MS = 2_000L
        // 30 s when no off-episode is in progress: tick still fires often
        // enough to clear post-reconnect cleanup state within a few seconds
        // of action, but doesn't wake the IO dispatcher every 2 s during
        // long parked periods.
        const val WALKAWAY_IDLE_TICK_MS = 30_000L
        const val WALKAWAY_SNOOZE_MS = 2 * 60_000L
        // Pixel native alarm cadence: three 1.5 s pulses with 0.8 s gaps,
        // ~7 s total. Long enough to feel through fabric, recognisable as
        // an alarm rather than a routine notification.
        private val WALKAWAY_VIBRATE_PATTERN = longArrayOf(0, 1500, 800, 1500, 800, 1500)
        private val LIGHT_FAIL_VIBRATE_PATTERN = longArrayOf(0, 300, 150, 300)
        // Cap on a single alarm-tone burst. After this the tone stops on
        // its own; the decider's rate limit will fire again later and the
        // tone restarts. Capped-burst pattern is harder to ignore than
        // continuous audio and less noisy for bystanders.
        private const val WALKAWAY_RINGTONE_CAP_MS = 60_000L
        // Override detection: blips shorter than this are treated as the same ride session.
        private const val CAMERA_LIGHT_OVERRIDE_DEADBAND_MS = 120_000L

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
