// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.data.PrefsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
    private lateinit var haPublisher: HaPublisher
    private lateinit var notifications: ServiceNotifications

    // Battery path state
    private val attemptInFlight = ConcurrentHashMap<String, Long>()

    // Dashcam-probe backoff state (BatteryProbeBackoff): guards against the
    // dashcam-off connect storm that would otherwise contend with the radar link.
    // lastDashcamProbeMs = last probe-launch time; dashcamProbeFailures =
    // consecutive read-failure count. Consulted only by the dashcam ticker while
    // the radar is connected; reset on a successful read and at ride start.
    private val lastDashcamProbeMs = ConcurrentHashMap<String, Long>()
    private val dashcamProbeFailures = ConcurrentHashMap<String, Int>()

    @Volatile private var scanRegistered = false

    // Per-ride statistics. Reset on each onCreate so "this ride" begins
    // when the service starts. Published to HA via the periodic loop
    // started below.
    private var rideStats = RideStatsAccumulator()

    // Radar link state
    @Volatile private var radarJob: Job? = null

    // Front camera/light link state
    @Volatile private var cameraLightJob: Job? = null

    @Volatile private var cameraLightGattActive = false

    @Volatile private var cameraLightUserOverride = false

    @Volatile private var cameraLightLastWrittenMode: CameraLightMode? = null

    @Volatile private var cameraLightOffSinceMs: Long? = null
    private val frontModeDiscoveredSlugs = ConcurrentHashMap.newKeySet<String>()

    // Radar tail-light auto-mode state (the radar light shares the radar's GATT
    // link, so unlike the camera there is no separate connection - the schedule
    // jobs are locals inside connectAndRun). Override is detected from 2f14 slot
    // changes against a per-connect baseline (see [RadarLightOverrideDecider]);
    // it persists across brief reconnects and is cleared only past a deadband.
    @Volatile private var radarLightUserOverride = false

    @Volatile private var radarLightBaselineKey: Int? = null

    @Volatile private var radarLightOffSinceMs: Long? = null

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
    // The radar-link / walk-away cluster lives in a single MutableStateFlow
    // so multi-field transitions in markRadarConnected / markRadarDisconnected
    // /tickWalkAwayState are observed atomically by readers. See
    // [RadarLinkState] for per-field semantics and the ARMED/BLANK/IDLE walk-
    // away state machine. The snooze-job AtomicReference (below) is NOT in
    // the cluster: it's a cancellable side effect, and `update { }` may run
    // its lambda multiple times under contention.
    private val _radarLinkState = MutableStateFlow(RadarLinkState())

    /** Read-only view of the radar-link / walk-away cluster. Currently has no
     *  external consumers; exposed so the planned overlay-pipeline extraction
     *  (audit H2) and future tests can observe transitions without reaching
     *  into private service fields. */
    val radarLinkState: StateFlow<RadarLinkState> = _radarLinkState

    // Convenience read-only accessors so the existing inline read sites do
    // not need to spell out `_radarLinkState.value.xxx` everywhere. Writes go
    // through `_radarLinkState.update { }` in the transition methods only.
    private val radarGattActive get() = _radarLinkState.value.radarGattActive
    private val radarOffSinceMs get() = _radarLinkState.value.radarOffSinceMs
    private val radarConnectStartMs get() = _radarLinkState.value.radarConnectStartMs
    private val sessionRadarConnectedMs get() = _radarLinkState.value.sessionRadarConnectedMs
    private val walkAwayArmed get() = _radarLinkState.value.walkAwayArmed
    private val walkAwayDismissed get() = _radarLinkState.value.walkAwayDismissed
    private val lastWalkAwayFireMs get() = _radarLinkState.value.lastWalkAwayFireMs

    // ── Bosch eBike live-data snapshot ───────────────────────────────
    // Last-known eBike snapshot from the status reader, or null when the
    // feature is off (no Bosch eBike, experimental flag off, or Flow not
    // running so no frames arrive). Consumed by [WalkAwayArmingGate] and by the
    // AlertDecider stationary override. The null case is the
    // graceful-degradation path that radar-only riders take.
    @Volatile private var lastEBikeSnapshot: LiveDataSnapshot? = null

    // Wall-clock of the last eBike snapshot. The radar-drop cue trusts
    // `system_locked == false` only while this is fresh: a stale snapshot
    // means the eBike link itself has dropped (rider walked away), so
    // "unlocked" can no longer be believed.
    @Volatile private var lastEBikeSnapshotMs: Long = 0L

    // Radar-drop cue latch (RadarDropDecider). Set to the last cue time;
    // reset to null on radar reconnect. See [RadarDropDecider] for the
    // full rationale.
    @Volatile private var radarDropLastCueMs: Long? = null

    // Diagnostics latch: true once a radar-drop "near miss" (radar down past
    // threshold but the cue held because riding wasn't confirmed) has been
    // logged this down-episode. Reset on radar reconnect so each episode logs
    // at most one suppression line - enough to tune the gate, no per-tick spam.
    @Volatile private var radarDropSuppressLogged = false

    // Live radar GATT + queue, set in connectAndRun after the V2 handshake and
    // cleared in its finally. Used ONLY by the debug-only radar light-mode
    // write-probe ([probeWriteRadarLight]); production code paths address the
    // gatt/queue locally inside connectAndRun.
    @Volatile private var liveRadarGatt: BluetoothGatt? = null

    @Volatile private var liveRadarQueue: BleOpQueue? = null

    // Cached overlay settings so the per-frame overlay loop does not re-read
    // SharedPreferences ~6-10x a frame for values the user changes a handful
    // of times a session. Kept fresh by a prefs.flow collector in onCreate
    // (same pattern as DebugOverlayService); the getter falls back to a live
    // snapshot until the collector's first emit, so it is never stale-null.
    @Volatile private var cachedOverlayPrefs: PrefsSnapshot? = null
    private val overlayPrefs: PrefsSnapshot get() = cachedOverlayPrefs ?: prefs.snapshot()

    // Read-only reader for the bike's proprietary live-data stream (the channel
    // the Bosch Flow app uses). Sources the live snapshot. Null when the eBike
    // feature is off or no bonded eBike is present.
    @Volatile private var ebikeStatusReader: EBikeStatusReader? = null

    // Absolute odometer at the first snapshot of this session; the
    // capture log writes `odo_delta_m = current - baseline` rather than
    // the absolute (privacy hardening, see EBikeCaptureFormatter).
    @Volatile private var sessionStartOdometerM: Long? = null

    // Ride-edge detector state, mutated only on the BLE callback
    // thread inside onSnapshot. Tracks whether the rider is currently
    // riding (per eBike lock + wheel-motion signals) so STARTED / ENDED
    // edges can publish to HA. See RideEdgeDetector.
    @Volatile private var rideEdgeState: RideEdgeDetector.State = RideEdgeDetector.State()

    // Climb-detector state. Accumulates the duration of sustained
    // high rider_power; when the dwell elapses, the AlertDecider's
    // stationary-suppress gate is forced off so a slow climb on Fitzjohns
    // or similar still gets alerts. Mutated only on the BLE callback
    // thread inside onSnapshot. See ClimbDetector.
    @Volatile private var climbState: ClimbDetector.State = ClimbDetector.State()

    @Volatile private var climbing: Boolean = false

    // sessionRadarConnectedMs, radarConnectStartMs, walkAwayArmed,
    // walkAwayDismissed, lastWalkAwayFireMs are part of [_radarLinkState]
    // above. The walk-away ARMED/BLANK/IDLE state machine - re-arm only via
    // a fresh radar power-on, not via an inter-ride dashcam advert - is the
    // canonical model pinned by WalkAwayDeciderTest; see [WalkAwayDecider]
    // class KDoc for the full rationale.

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
     *  [WALKAWAY_RINGTONE_CAP_MS]. Without this cap a forgotten alert
     *  would loop forever; the decider's rate limit then re-fires the
     *  notification, re-starting the tone for another bounded window. */
    private var walkAwayRingtoneCapJob: Job? = null

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
                        Log.i(TAG_RADAR, "radar re-paired ($mac); allowing reconnect")
                        bondLost = false
                    }
                }
            }
        }
    }

    // Overlay window + view ownership lives in AndroidOverlayHost; the
    // service forwards onConfigurationChanged so the host re-applies layout
    // params on rotation.
    private lateinit var overlayHost: AndroidOverlayHost

    // Pipeline orchestrates the per-frame combine(RadarStateBus +
    // BatteryStateBus + ticker) loop that drives the overlay view, the
    // beeper cues, and the close-pass detector + HA publish. Allocated in
    // onCreate; attach() is called per radar connection.
    private lateinit var overlayPipeline: OverlayPipeline

    // Service-scoped AlertBeeper. Allocated in onCreate, released in
    // onDestroy. Hoisted out of overlayJob so reconnects do not pay
    // AudioTrack cold-start every time, and so audio focus + the
    // MODE_IN_CALL guard survive across radar drops.
    @Volatile private var alertBeeper: AlertBeeper? = null

    // Capture log: owns the per-ride file lifecycle, buffered append, prune/gzip.
    // mirror echoes to logcat only in debug builds (release keeps BLE/movement
    // payloads out of logcat); onActiveName mirrors the active name to the
    // companion DebugScreen reads.
    private val captureLog = CaptureLogManager(
        externalFilesDir = { getExternalFilesDir(null) },
        captureLoggingEnabled = { prefs.captureLoggingEnabled },
        mirror = { if (BuildConfig.DEBUG) Log.d(TAG_RADAR, it) },
        onActiveName = { activeCaptureLogName = it },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ClosePassStateBus.reset()
        rideStats = RideStatsAccumulator()
        prefs = Prefs(this)
        cachedOverlayPrefs = prefs.snapshot()
        scope.launch { prefs.flow.collect { cachedOverlayPrefs = it } }
        creds = HaCredentials(this)
        creds.seedFromBuildConfigIfEmpty()
        ha = HaClient(creds.baseUrl, creds.token)
        notifications = ServiceNotifications(this) { prefs }

        notifications.ensureChannels()
        startForeground(
            ServiceNotifications.NOTIF_ID,
            notifications.buildForeground(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        // Service-scope AlertBeeper. AudioTracks are warmed once here
        // so the first beep after any radar reconnect lands without
        // mixer / MinBuf cold-start latency.
        //
        // Rotation is fetched via DisplayManager rather than Context.getDisplay():
        // on Android 16 the latter throws UnsupportedOperationException for
        // Service contexts (no associated Display). The DisplayManager handle
        // returns a live Display whose getRotation() tracks orientation changes.
        val defaultDisplay: Display? = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        alertBeeper = AlertBeeper(
            audioManager = getSystemService(AUDIO_SERVICE) as AudioManager,
            rotationProvider = { defaultDisplay?.rotation ?: android.view.Surface.ROTATION_90 },
            // Record every cue that actually sounds (post-suppression) in the
            // capture log, so a wrong-time beep can be traced after a ride, and
            // tally the alarm cues (beep/urgent) into the ride stats for the
            // alerts-per-km / per-hour summary metrics. onCue runs on
            // AlertBeeper's playback thread; rideStats is otherwise written only
            // from the radar-collect (Main) context, so marshal the tally onto
            // Main to keep the accumulator single-writer.
            onCue = {
                clog("# cue $it")
                scope.launch(Dispatchers.Main) { rideStats.observeAlertCue(it) }
            },
        ).also {
            it.setVolumePct(prefs.alertVolume)
            it.setPanning(
                enabled = prefs.experimentalLateralPanning,
                invertLR = prefs.experimentalLateralPanningInvertLR,
            )
        }

        overlayHost = AndroidOverlayHost(this, ::buildOverlayParams)
        overlayPipeline = OverlayPipeline(
            prefs = prefs,
            ha = ha,
            beeper = alertBeeper!!,
            overlayHost = overlayHost,
            phoneBattery = AndroidPhoneBatterySource(this),
            rideStats = { rideStats },
            overlayPrefsSnapshot = { cachedOverlayPrefs ?: prefs.snapshot() },
            ebikeSnapshot = { lastEBikeSnapshot },
            climbingNow = { climbing },
            currentRadarMac = { currentRadarMac },
            macToSlug = { macToSlug },
            clog = { line -> clog(line) },
        )
        haPublisher = HaPublisher(
            scope = scope,
            creds = creds,
            rideStats = { rideStats },
            currentRadarMac = { currentRadarMac },
            macToSlug = { macToSlug },
            loadKnownDevices = { loadKnownDevices() },
            slug = { name -> slug(name) },
        )

        pruneCaptureLogs()
        schedulePauseExpiry()
        registerEventScan()
        registerBondReceiver()
        scope.launch { kickstartFromCache() }
        launchWalkAwayTick()
        launchDashcamRefresh()
        haPublisher.launchRideSummaryPublishLoop()
        maybeStartEBikeReader()
    }

    /**
     * Lifecycle: start the read-only eBike live-data reader when the feature
     * flag is on AND BLE permissions are granted. [EBikeStatusReader]
     * subscribes to the bike's proprietary status stream (the channel the
     * Bosch Flow app uses) over the existing bonded link, and pushes snapshots
     * into [lastEBikeSnapshot] (for the AlertDecider stationary override +
     * walk-away gate) and [EBikeStateBus] (for the SYSTEM-card eBike row). It
     * never writes to the bike.
     *
     * Flag off / permission missing / no bonded eBike → reader not started,
     * `lastEBikeSnapshot` stays null, all downstream consumers fall back to the
     * radar's own bike-speed reading. Graceful degradation for radar-only riders.
     */
    @SuppressLint("MissingPermission")
    private fun maybeStartEBikeReader() {
        if (!prefs.eBikeDataEnabled) return
        if (!hasBlePermissions()) {
            Log.i(TAG_RADAR, "ebike: feature on but BLE permissions not granted; skipping")
            return
        }
        val ebikeMac = EBikeStatusReader.findBondedEBikeMac(this)
        if (ebikeMac == null) {
            Log.i(TAG_RADAR, "ebike: no bonded eBike found; status reader not started")
            return
        }
        val reader = EBikeStatusReader(
            context = this,
            scope = scope,
            mac = ebikeMac,
            onSnapshot = { snap ->
                onEBikeSnapshot(snap)
                EBikeStateBus.setSnapshot(snap)
            },
            log = { m -> Log.i("BikeRadar.EBikeStatus", m) },
            // Sink for the Debug "log unknown eBike object IDs" pinning
            // workflow. Always wired so flipping the toggle takes effect on
            // the next frame; the closure no-ops when the pref is off.
            onUnknownRecord = { objId, value ->
                if (prefs.eBikeUnknownObjectLogEnabled) {
                    clog("ebike_unk obj=0x${"%04x".format(objId)} val=$value")
                }
            },
        )
        ebikeStatusReader = reader
        reader.start()
        clog("# ebike status-reader started")
    }

    /**
     * Handle a fresh live-data snapshot (from [EBikeStatusReader]): cache it
     * for the AlertDecider stationary override and the walk-away arming gate,
     * append a delta-only line to the capture log, and drive ride-edge + climb
     * detection. Extracted so both the (defunct) eb21 path and the proprietary
     * status reader feed identical downstream behaviour.
     */
    private fun onEBikeSnapshot(snap: LiveDataSnapshot) {
        lastEBikeSnapshot = snap
        lastEBikeSnapshotMs = System.currentTimeMillis()
        // Capture odometer baseline on first sighting, then log the snapshot
        // delta-only. format() returns null when every field is still
        // unobserved so we skip logging empty stubs.
        if (sessionStartOdometerM == null) {
            sessionStartOdometerM = snap.odometerM
        }
        EBikeCaptureFormatter.format(snap, sessionStartOdometerM)?.let(::clog)
        // Feed the edge detector; on STARTED / ENDED publish to HA so
        // dashboards and automations have bike-truth ride boundaries
        // (independent of GPS drift on the office side).
        val (nextState, edge) = RideEdgeDetector.next(rideEdgeState, snap)
        rideEdgeState = nextState
        if (edge != RideEdgeDetector.Edge.NONE) {
            val edgeName = if (edge == RideEdgeDetector.Edge.STARTED) "started" else "ended"
            val nowIso = java.time.Instant.now().toString()
            clog("# ebike ride_edge=$edgeName t=$nowIso")
            haPublisher.publishRideEdgeIfHa(edgeName, nowIso)
        }
        // Thread the climb state. Sustained high rider_power (default >= 250 W
        // for >= 30 s) flips the climbing bit, which the AlertDecider
        // stationary override consults to keep alerts firing on a slow climb.
        val (nextClimb, isClimbing) = ClimbDetector.classify(
            prev = climbState,
            nowMs = System.currentTimeMillis(),
            riderPowerW = snap.riderPower,
        )
        climbState = nextClimb
        if (isClimbing != climbing) {
            climbing = isClimbing
            clog("# ebike climbing=$isClimbing rider_power=${snap.riderPower}")
        }
    }

    private fun hasBlePermissions(): Boolean {
        // The eBike reader is a read-only GATT client connecting to a bonded
        // device by address - it needs BLUETOOTH_CONNECT only. It does not
        // advertise (the old peripheral link did; it's gone) and does not scan
        // (the address comes from the bonded-device list).
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
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
                notifications.postForeground()
            }
            ACTION_FORCE_RECONNECT -> {
                Log.i(TAG_RADAR, "force reconnect requested")
                radarJob?.cancel()
            }
            ACTION_WALKAWAY_DISMISS -> {
                Log.i(TAG, "walk-away dismissed")
                _radarLinkState.update { it.copy(walkAwayDismissed = true) }
                walkAwaySnoozeJob.getAndSet(null)?.cancel()
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_WALKAWAY_ID)
                stopWalkAwayAlarmTone()
            }
            ACTION_START_EBIKE_READER -> {
                // Onboarding eBike step just enabled the feature; bring up the
                // read-only status reader now. Idempotent: maybeStartEBikeReader
                // bails when the reader is already running.
                if (ebikeStatusReader == null) maybeStartEBikeReader()
            }
            ACTION_RESTART_EBIKE_READER -> {
                // Tear the status reader down and rebuild it (e.g. the rider
                // re-opened Flow, or the bike came back).
                Log.i(TAG_RADAR, "ebike: ACTION_RESTART_EBIKE_READER - restarting status reader")
                ebikeStatusReader?.shutdown()
                ebikeStatusReader = null
                EBikeStateBus.reset()
                maybeStartEBikeReader()
            }
            ACTION_WALKAWAY_SNOOZE -> {
                Log.i(TAG, "walk-away snoozed for ${WALKAWAY_SNOOZE_MS / 1000}s")
                _radarLinkState.update { it.copy(walkAwayDismissed = true) }
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_WALKAWAY_ID)
                stopWalkAwayAlarmTone()
                val newJob = scope.launch {
                    delay(WALKAWAY_SNOOZE_MS)
                    // Clear both gates so the decider can re-evaluate
                    // cleanly: treat the snooze as a full re-arm for this
                    // episode rather than "it's been 2 minutes, fire
                    // again immediately".
                    _radarLinkState.update {
                        it.copy(walkAwayDismissed = false, lastWalkAwayFireMs = null)
                    }
                }
                walkAwaySnoozeJob.getAndSet(newJob)?.cancel()
            }
            ACTION_RADAR_LIGHT_PROBE_WRITE -> {
                val hex = intent.getStringExtra(EXTRA_RADAR_LIGHT_HEX)
                if (hex != null) {
                    probeWriteRadarRaw(hex)
                } else {
                    probeWriteRadarLight(intent.getIntExtra(EXTRA_RADAR_LIGHT_NN, -1))
                }
            }
        }
        return START_STICKY
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
    private fun probeWriteRadarLight(nn: Int) {
        if (nn !in 0..0xFF) {
            clog("# radar_probe_write skipped (bad nn=$nn)")
            return
        }
        probeWriteRadar(byteArrayOf(0x07, 0x00, nn.toByte()))
    }

    /** Parse a hex string (spaces ignored) and write it raw to the radar's
     *  control char. Lets the bench probe send any command - mode-set by type
     *  (`06 09 01 TT`), slot-list config (`06 09 05 ...`), etc. - not just
     *  the `07 00 NN` slot select. */
    private fun probeWriteRadarRaw(hex: String) {
        val clean = hex.filterNot { it.isWhitespace() }
        val bytes = try {
            require(clean.isNotEmpty() && clean.length % 2 == 0)
            clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            clog("# radar_probe_write skipped (bad hex='$hex')")
            return
        }
        probeWriteRadar(bytes)
    }

    private fun probeWriteRadar(payload: ByteArray) {
        val label = payload.joinToString(" ") { "%02x".format(it) }
        if (!prefs.radarSettingsProbeEnabled) {
            clog("# radar_probe_write skipped (probe off) [$label]")
            return
        }
        val gatt = liveRadarGatt
        val queue = liveRadarQueue
        if (gatt == null || queue == null) {
            clog("# radar_probe_write skipped (no live radar) [$label]")
            return
        }
        val ch = gatt.getService(Uuids.SVC_CONTROL)?.getCharacteristic(Uuids.SETTINGS_ACK)
        if (ch == null) {
            clog("# radar_probe_write skipped (no 2f11) [$label]")
            return
        }
        scope.launch {
            val ok = queue.write(gatt, ch, payload, noResponse = false)
            clog("# radar_probe_write [$label] ok=$ok")
        }
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
        alertBeeper?.release()
        alertBeeper = null
        // Lifecycle teardown: stop the eBike status reader and tear down the
        // internal timer scope. The reader's GATT calls are wrapped in
        // try/catch so permission revocation between start and shutdown does
        // not crash here.
        ebikeStatusReader?.shutdown()
        ebikeStatusReader = null
        EBikeStateBus.reset()
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
        if (::overlayHost.isInitialized) overlayHost.onConfigurationChanged()
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
        // Keep the radar link alive for the SELECTED radar. Default is
        // name-match (zero config); if the rider pinned a specific radar
        // (radarMac, still bonded) only that exact device links - the guard
        // against silently streaming the wrong rear-facing radar. See
        // RadarSelection. The bonded-device enumeration only runs when a pin
        // exists, so the common no-pin path stays a pure name-match.
        val pinnedRadar = prefs.radarMac
        val shouldLinkRadar = if (pinnedRadar == null) {
            isRearDevice(name)
        } else {
            RadarSelection.shouldLinkRadar(
                mac = mac,
                nameMatchesRadar = isRearDevice(name),
                chosenMac = pinnedRadar,
                bondedRadarMacs = RadarSelection.bondedRadars(this).mapTo(HashSet()) { it.mac },
            )
        }
        if (shouldLinkRadar) maybeStartRadarLink(name, mac)

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
        val btMgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = btMgr.adapter ?: return null
        if (!adapter.isEnabled) return null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
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
                gatt = device.connectGatt(this@BikeRadarService, false, cb, BluetoothDevice.TRANSPORT_LE)
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
        try {
            unregisterReceiver(bondReceiver)
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
        notifications.ensureChannels()
        val piFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openSettings = PendingIntent.getActivity(
            this,
            BOND_NOTIF_REQ,
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags,
        )
        val notif = NotificationCompat.Builder(this, ServiceNotifications.CHANNEL_ID)
            .setContentTitle(getString(R.string.svc_main_notif_title))
            .setContentText(getString(R.string.svc_main_bond_lost_text))
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
        } catch (_: Throwable) {
            null
        } ?: return

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
                val delayMs = if (quickReconnect) RADAR_QUICK_RECONNECT_MS else jittered(backoffMs)
                Log.i(TAG_LIGHT, "reconnecting in ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
                if (!quickReconnect) {
                    backoffMs = (backoffMs * 2).coerceAtMost(
                        reconnectBackoffCap(
                            now = System.currentTimeMillis(),
                            offSinceMs = radarOffSinceMs,
                            longOfflineThresholdMs = prefs.radarLongOfflineThresholdMinutes * 60_000L,
                            longOfflineCapMs = prefs.radarLongOfflineCapSec * 1_000L,
                        ),
                    )
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
        if (gatt == null) {
            Log.w(TAG_LIGHT, "connectGatt returned null")
            return false
        }

        val queueJob = scope.launch { queue.run() }

        var sunsetJob: Job? = null
        var sunriseJob: Job? = null
        return try {
            val ok = servicesReady.await()
            if (!ok) {
                Log.w(TAG_LIGHT, "service discovery failed")
                return false
            }

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
                gatt,
                queue,
                notifyChannel,
                DeviceVariant.FRONT_CAMERA,
            ) { msg -> Log.d(TAG_LIGHT, msg) }

            if (!handshakeOk) {
                Log.w(TAG_LIGHT, "handshake failed; closing for quick reconnect")
                gatt.disconnect()
                return true
            }

            Log.i(TAG_LIGHT, "handshake complete; subscribing mode-state notify")
            // Refresh the location cache for sunrise/sunset before scheduling
            // the dusk/dawn flips below. Idempotent with the radar-side
            // refresh: if either device handshook first, the cache is warm.
            LocationCache.refreshIfStale(this@BikeRadarService)
            val ch14 = gatt.getService(Uuids.SVC_CONTROL)?.getCharacteristic(Uuids.SETTINGS_14)
            if (ch14 != null) queue.writeCccd(gatt, ch14)

            val lightSlugEarly = slug(name)
            val lightMacEarly = gatt.device?.address
            if (lightMacEarly != null) macToSlug[lightMacEarly] = lightSlugEarly
            if (ha.isConfigured() && frontModeDiscoveredSlugs.add(lightSlugEarly)) {
                if (!ha.publishFrontModeDiscovery(lightSlugEarly, name)) {
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
            Log.i(TAG_LIGHT, "auto-mode: night=$isNight override=$cameraLightUserOverride sunset=$sunsetLog")
            suspend fun applyPhase(phase: LightAutoModeDecider.Phase, label: String) {
                val mode = if (phase == LightAutoModeDecider.Phase.NIGHT) {
                    prefs.cameraLightNightMode
                } else {
                    prefs.cameraLightDayMode
                }
                val ok = applyWithRetry { controller.setMode(mode) }
                Log.i(TAG_LIGHT, "$label mode=$mode applied=$ok")
                if (ok) {
                    cameraLightLastWrittenMode = mode
                    if (ha.isConfigured()) ha.publishFrontModeState(lightSlugEarly, mode.name)
                } else {
                    postLightModeFailNotification(mode)
                }
            }
            if (plan.initial != null) {
                applyPhase(plan.initial, "initial")
            } else {
                Log.i(TAG_LIGHT, "initial mode skipped (manual override active)")
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
                        Log.d(TAG_LIGHT, "mode-state notify: $mode")
                        val expected = cameraLightLastWrittenMode
                        if (expected != null && mode != expected && !cameraLightUserOverride) {
                            cameraLightUserOverride = true
                            Log.i(TAG_LIGHT, "override detected: expected=$expected device=$mode")
                        }
                        if (ha.isConfigured()) ha.publishFrontModeState(lightSlugEarly, mode.name)
                    }
                }
            }
            false
        } finally {
            cameraLightGattActive = false
            if (cameraLightOffSinceMs == null) cameraLightOffSinceMs = System.currentTimeMillis()
            sunsetJob?.cancel()
            sunriseJob?.cancel()
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
        } catch (_: Throwable) {
            null
        } ?: return

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
                    else -> jittered(backoffMs)
                }
                val tag = when {
                    quickReconnect -> " (post-ABORT)"
                    else -> " (backoff=${backoffMs}ms)"
                }
                Log.i(TAG_RADAR, "reconnecting in ${delayMs}ms$tag")
                kotlinx.coroutines.delay(delayMs)
                if (!quickReconnect) {
                    backoffMs = (backoffMs * 2).coerceAtMost(
                        reconnectBackoffCap(
                            now = System.currentTimeMillis(),
                            offSinceMs = radarOffSinceMs,
                            longOfflineThresholdMs = prefs.radarLongOfflineThresholdMinutes * 60_000L,
                            longOfflineCapMs = prefs.radarLongOfflineCapSec * 1_000L,
                        ),
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
        if (gatt == null) {
            clog("# connectGatt returned null")
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
                clog("# services discovery failed")
                return false
            }

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
            // First chance per ride to refresh the location cache used by
            // SunsetCalculator (front- and radar-light auto-modes). 60-min staleness
            // gate means quick stop-and-go reconnects don't re-poll.
            LocationCache.refreshIfStale(this@BikeRadarService)
            openCaptureLog()
            // Overlay + alert coroutine, extracted into OverlayPipeline.
            overlayJob = overlayPipeline.attach(scope, name)

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
                clog("# radar_settings_probe svc=${controlSvc != null} 2f14=${ch2f14 != null} 2f12=${probe12 != null}")
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
                    System.currentTimeMillis(),
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
                    Log.i(TAG_RADAR, "radar light $phase mode=$mode applied=$okSet")
                    if (!okSet) postRadarLightModeFailNotification(mode)
                }
                plan.initial?.let { applyPhase(it) }
                val flipAt = plan.flipAtMs
                val flipTo = plan.flipTo
                if (flipAt != null && flipTo != null) {
                    val job = scope.launch {
                        kotlinx.coroutines.delay(flipAt - nowMs)
                        if (_radarLinkState.value.radarGattActive && !radarLightUserOverride) {
                            applyPhase(flipTo)
                        }
                    }
                    if (flipTo == LightAutoModeDecider.Phase.NIGHT) radarSunsetJob = job else radarSunriseJob = job
                }
            }

            for ((uuid, bytes) in notifyChannel) {
                when (uuid) {
                    Uuids.RADAR_V2 -> {
                        lastV2FrameMs = System.currentTimeMillis()
                        if (v2FrameCount++ == 0) Log.i(TAG_RADAR, "first V2 frame: ${bytes.toHex()}")
                        v2Dec.feed(bytes)?.let { RadarStateBus.publish(it) }
                    }
                    Uuids.SETTINGS_14 -> {
                        if (prefs.radarSettingsProbeEnabled) clog("# radar_2f14 ${bytes.toHex()}")
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
                                    clog("# radar_light_override (2f14 slot change)")
                                }
                            }
                        }
                    }
                    Uuids.SETTINGS_12 -> if (prefs.radarSettingsProbeEnabled) clog("# radar_2f12 ${bytes.toHex()}")
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
            radarLightOffSinceMs = System.currentTimeMillis()
            liveRadarGatt = null
            liveRadarQueue = null
            watchdogJob?.cancel()
            overlayJob?.cancel()
            queue.cancel()
            queueJob.cancel()
            markRadarDisconnected()
            RadarStateBus.clear()
            // Fire-and-forget final flush of the ride summary so HA sees
            // the latest values before the next reconnect's backoff delay.
            scope.launch(Dispatchers.IO) { haPublisher.publishRideSummaryIfChanged() }
            closeOnce()
            closeCaptureLog()
        }
    }

    // ── light failure feedback ─────────────────────────────────────────────────

    /** Three jittered attempts at a light-mode write (shared by the camera and
     *  radar light paths). [write] returns true once the device ACKs the GATT
     *  write; note for the radar that confirms receipt, not that the light
     *  element actually changed (the radar can't be read back). */
    private suspend fun applyWithRetry(write: suspend () -> Boolean): Boolean {
        if (write()) return true
        kotlinx.coroutines.delay(500 + (100 * (Math.random() * 2 - 1)).toLong())
        if (write()) return true
        kotlinx.coroutines.delay(1500 + (300 * (Math.random() * 2 - 1)).toLong())
        return write()
    }

    private suspend fun postLightModeFailNotification(mode: CameraLightMode) {
        val modeName = getString(
            when (mode) {
                CameraLightMode.HIGH -> R.string.settings_lightmode_high
                CameraLightMode.MEDIUM -> R.string.settings_lightmode_medium
                CameraLightMode.LOW -> R.string.settings_lightmode_low
                CameraLightMode.NIGHT_FLASH -> R.string.settings_lightmode_night_flash
                CameraLightMode.DAY_FLASH -> R.string.settings_lightmode_day_flash
                CameraLightMode.OFF -> R.string.settings_lightmode_off
            },
        )

        notifications.ensureChannels()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, ServiceNotifications.LIGHT_FAIL_CHANNEL_ID)
            .setContentTitle(getString(R.string.svc_main_dashcam_light_title))
            .setContentText(getString(R.string.svc_main_light_fail_text, modeName))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setVibrate(ServiceNotifications.LIGHT_FAIL_VIBRATE_PATTERN)
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

    /** Radar tail-light switch-failed feedback, dashcam parity (rider choice):
     *  a HIGH-priority notification + the NACK beep. Distinct notification ID
     *  from the dashcam so neither clobbers the other. Fires only when the GATT
     *  write was not ACKed after the retries - it can't catch the rarer "ACKed
     *  but the light element didn't change" case (no read-back). */
    private suspend fun postRadarLightModeFailNotification(mode: RadarLightMode) {
        val modeName = getString(
            when (mode) {
                RadarLightMode.NIGHT_FLASH -> R.string.settings_lightmode_night_flash
                RadarLightMode.DAY_FLASH -> R.string.settings_lightmode_day_flash
                RadarLightMode.SOLID -> R.string.settings_lightmode_solid
                RadarLightMode.PELOTON -> R.string.settings_lightmode_peloton
                RadarLightMode.OFF -> R.string.settings_lightmode_off
            },
        )

        notifications.ensureChannels()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, ServiceNotifications.LIGHT_FAIL_CHANNEL_ID)
            .setContentTitle(getString(R.string.svc_main_radar_light_title))
            .setContentText(getString(R.string.svc_main_light_fail_text, modeName))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setVibrate(ServiceNotifications.LIGHT_FAIL_VIBRATE_PATTERN)
            .build()
        nm.notify(NOTIF_RADAR_LIGHT_FAIL_ID, notif)

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

    private fun openCaptureLog() = captureLog.open()

    private fun closeCaptureLog() = captureLog.close()

    fun clog(msg: String) = captureLog.clog(msg)

    /** Capture-log line for a non-None AlertDecider event. Pairs the
     *  emitted event with the in-front, in-alert-range closest vehicle
     *  observed in the same frame so a replay can attribute the beep
     *  to a specific track without re-deriving the AlertDecider's
     *  internal stable-close set. */
    private fun logAlertEvent(ev: AlertDecider.Event, state: RadarState, nowMs: Long) {
        val evStr = when (ev) {
            is AlertDecider.Event.Beep -> "Beep(${ev.count})"
            AlertDecider.Event.Clear -> "Clear"
            is AlertDecider.Event.UrgentApproach -> "UrgentApproach"
            AlertDecider.Event.None -> "None"
        }
        val alertMax = prefs.alertMaxDistanceM
        val closest = state.vehicles
            .filter { !it.isBehind && !it.isAlongsideStationary && it.distanceM in 0..alertMax }
            .minByOrNull { it.distanceM }
        // `frame_closest_*` is the in-front, in-range vehicle with the
        // smallest distance THIS FRAME, not the track the decider
        // actually attributed the event to. The decider applies
        // `sustainFrames`, per-tid latches, and stationary suppress on
        // top of frame closeness; same-frame closest is a diagnostic
        // anchor, not the attribution. `_mps` suffix on speed fields
        // distinguishes m/s from the `_ms` (milliseconds) suffix used
        // elsewhere in the capture log. `-1` is the sentinel for "no
        // close vehicle this frame" across all numeric fields.
        clog(
            "# alert ts=$nowMs event=$evStr " +
                "frame_closest_tid=${closest?.id ?: -1} " +
                "frame_closest_d=${closest?.distanceM ?: -1} " +
                "closing_mps=${closest?.let { -it.speedMs } ?: -1f} " +
                "bike_speed_mps=${state.bikeSpeedMs ?: -1f}",
        )
    }

    private fun clogPacket(uuid: UUID, bytes: ByteArray) = captureLog.clogPacket(uuid, bytes)

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
                .build(),
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
                    ScanSettings.CALLBACK_TYPE_MATCH_LOST,
            )
            .build()
        val rc = try {
            scanner.startScan(filters, settings, buildScanPendingIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "startScan threw", t)
            -1
        }
        scanRegistered = (rc == 0)
        val offloaded = try {
            mgr.adapter.isOffloadedFilteringSupported
        } catch (_: Throwable) {
            false
        }
        Log.i(TAG, "event scan registered rc=$rc offloaded=$offloaded (first-match+match-lost)")
    }

    @SuppressLint("MissingPermission")
    private fun unregisterEventScan() {
        if (!scanRegistered) return
        val mgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = mgr?.adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(buildScanPendingIntent())
        } catch (_: Throwable) {}
        scanRegistered = false
    }

    private fun buildScanPendingIntent(): PendingIntent {
        val i = Intent(this, BatteryScanReceiver::class.java).apply {
            action = BatteryScanReceiver.ACTION_SCAN_RESULT
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(this, SCAN_PI_REQ, i, flags)
    }

    // ── kickstart active scan ─────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun scanForDevices(timeoutMs: Long = 12_000): List<Pair<String, String>> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
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

    // ── walk-away alarm tone ─────────────────────────────────────────────────

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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build(),
                )
            } else {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attrs)
            }
        } catch (t: Throwable) {
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

        // System default alarm tone. A bundled sound asset was dropped
        // because its licence was not GPL-compatible; the device alarm
        // keeps the app asset-free. It does share the rider's wake-up
        // tone, but the alarm stream is forced to max below and the cue
        // loops under USAGE_ALARM, so it still cuts through. Fall back
        // through ringtone/notification if no default alarm is set.
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val rt = try {
            RingtoneManager.getRingtone(this, uri).apply {
                audioAttributes = attrs
                isLooping = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ringtone setup failed: $t")
            try {
                am.abandonAudioFocusRequest(focusReq)
            } catch (_: Throwable) {}
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
        } catch (_: Throwable) {
            null
        }
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
            try {
                am.abandonAudioFocusRequest(focusReq)
            } catch (_: Throwable) {}
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
            try {
                it.stop()
            } catch (_: Throwable) {}
        }
        walkAwayRingtone = null
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        walkAwayAudioFocusRequest?.let { req ->
            try {
                am.abandonAudioFocusRequest(req)
            } catch (_: Throwable) {}
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
                val link = _radarLinkState.value
                val gateOpen = IdleGate.shouldRefreshDashcam(
                    radarGattActive = link.radarGattActive,
                    radarOffSinceMs = link.radarOffSinceMs,
                    nowMs = System.currentTimeMillis(),
                )
                if (gateOpen && mac != null && !name.isNullOrEmpty()) {
                    val slug = resolveDashcamSlug()
                    val entry = slug?.let { BatteryStateBus.entries.value[it] }
                    val now = System.currentTimeMillis()
                    val ageMs = entry?.let { now - it.readAtMs } ?: Long.MAX_VALUE
                    if (ageMs >= DASHCAM_REFRESH_MS) {
                        // Connect-storm guard: while the radar is connected, a
                        // probe that keeps failing (dashcam powered off) backs off
                        // so it can't connect-storm and contend with the radar
                        // link. While the radar is DISCONNECTED the walk-away alarm
                        // consumes this same liveness freshness, so backoff is
                        // bypassed there - the probe keeps its full age-gated
                        // cadence, byte-identical to the pre-fix behaviour.
                        val probeOk = BatteryProbeBackoff.shouldProbe(
                            link.radarGattActive,
                            now,
                            lastDashcamProbeMs[mac],
                            dashcamProbeFailures[mac] ?: 0,
                            DASHCAM_REFRESH_MS,
                            DASHCAM_PROBE_BACKOFF_CAP_MS,
                        )
                        if (probeOk) {
                            lastDashcamProbeMs[mac] = now
                            launchBatteryRead(name, mac)
                        }
                    }
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
                evaluateRadarDrop(now)
            }
        }
    }

    /** Off-instant is stamped at the actual disconnect callback so it
     *  isn't tied to tick cadence (the idle tick is 30 s; that would
     *  drift the walk-away threshold by up to 30 s). Clean-reconnect
     *  cleanup likewise fires at the connection-success site, not
     *  lazily on the next tick.
     *
     *  Side effects (notification cancel, snooze-job cancel, clog) sit
     *  OUTSIDE the [update] lambda - the lambda may run multiple times
     *  on a CAS retry, but these effects must fire exactly once per
     *  observed transition. The snapshot read of the prior state is the
     *  arbiter for whether to fire the effects. */
    private fun markRadarConnected() {
        val nowMs = System.currentTimeMillis()
        val prev = _radarLinkState.value
        _radarLinkState.update { current ->
            if (current.radarOffSinceMs != null) {
                // Any → IDLE: radar is back, leave-behind tracking off.
                // Re-arming requires the next radar disconnect.
                current.copy(
                    radarOffSinceMs = null,
                    walkAwayArmed = false,
                    walkAwayDismissed = false,
                    lastWalkAwayFireMs = null,
                    radarConnectStartMs = nowMs,
                    radarGattActive = true,
                )
            } else {
                current.copy(
                    radarConnectStartMs = nowMs,
                    radarGattActive = true,
                )
            }
        }
        // New radar presence episode: clear dashcam-probe backoff so the camera
        // is re-probed promptly this ride (the storm guard resets per ride).
        prefs.dashcamMac?.let {
            dashcamProbeFailures.remove(it)
            lastDashcamProbeMs.remove(it)
        }
        if (prev.radarOffSinceMs != null) {
            val prevState = if (prev.walkAwayArmed) "ARMED" else "BLANK"
            walkAwaySnoozeJob.getAndSet(null)?.cancel()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_WALKAWAY_ID)
            clog("# walkaway state=IDLE transition_reason=radar-connected prev_state=$prevState")
        }
    }

    private fun markRadarDisconnected() {
        val nowMs = System.currentTimeMillis()
        val prev = _radarLinkState.value
        // Computed once from prev and reused across any CAS retries below.
        // walkAwayArmed is monotonic within an off-episode (only cleared by
        // markRadarConnected / tickWalkAwayState BLANK), so re-evaluating
        // it per retry wouldn't change the post-state semantically.
        val armOnDisconnect = prev.radarOffSinceMs == null &&
            WalkAwayArmingGate.shouldArm(
                lastEBikeSnapshot,
                snapshotAgeMs = nowMs - lastEBikeSnapshotMs,
                freshMs = WALKAWAY_EBIKE_FRESH_MS,
            )
        _radarLinkState.update { current ->
            val addedMs = current.radarConnectStartMs?.let { nowMs - it } ?: 0L
            current.copy(
                radarGattActive = false,
                radarConnectStartMs = null,
                sessionRadarConnectedMs = current.sessionRadarConnectedMs + addedMs,
                // Off-instant is stamped on the FIRST disconnect; a stutter
                // mid-off-episode must not refresh it.
                radarOffSinceMs = current.radarOffSinceMs ?: nowMs,
                // Consult the eBike snapshot before arming. When the bike
                // reports system_locked=false the rider is on the bike
                // (mid-ride radar BLE blip); arming would misfire. Any other
                // case (locked, null systemLocked, null snapshot, eBike flag
                // off) falls through to the existing IDLE → ARMED path.
                walkAwayArmed = current.walkAwayArmed || armOnDisconnect,
            )
        }
        if (prev.radarOffSinceMs == null) {
            // ebike_locked + ebike_age_ms make the arming decision tunable: a
            // BLANK is always a fresh unlocked reading; an ARMED is one of
            // locked / stale-unlocked / no-eBike, told apart by these two.
            val ebikeAgeMs = nowMs - lastEBikeSnapshotMs
            if (armOnDisconnect) {
                clog(
                    "# walkaway state=ARMED transition_reason=radar-disconnected " +
                        "ebike_locked=${lastEBikeSnapshot?.systemLocked} ebike_age_ms=$ebikeAgeMs",
                )
            } else {
                clog(
                    "# walkaway state=BLANK transition_reason=radar-disconnected-but-ebike-unlocked " +
                        "ebike_age_ms=$ebikeAgeMs",
                )
            }
        }
    }

    private fun tickWalkAwayState(nowMs: Long, elapsedMs: Long) {
        // sessionRadarConnectedMs is integrated on connect→disconnect
        // transitions in [markRadarDisconnected], not per-tick. The idle
        // tick is 30 s; a connection that ends within that window would
        // never have its duration counted under the old per-tick scheme.

        // ARMED → BLANK transition: while the radar is still off, watch
        // for the dashcam going stale. The "stale window" is anchored at
        // the LATER of (radarOffSinceMs, dashcamLastAdvertMs):
        //   - if the dashcam has adverted since radar-off, the window
        //     starts at the most recent advert (rider walked away from
        //     a continuously-fresh dashcam, then dashcam dropped out);
        //   - if the dashcam was already silent at radar-off, the window
        //     starts at the disconnect itself (rider stopped with the
        //     camera already off — never was a leave-behind risk).
        // Once the window exceeds dashcamFreshMs we declare BLANK; the
        // alarm is permanently disarmed for this off-episode regardless
        // of whether the dashcam comes back later.
        //
        // Once disarmed, the rider has packed up the bike for now;
        // re-arming requires the next ride (radar power-on then off).
        val snapshot = _radarLinkState.value
        val offAt = snapshot.radarOffSinceMs
        if (offAt != null && snapshot.walkAwayArmed) {
            val slug = resolveDashcamSlug()
            val lastAdvert = slug?.let { BatteryStateBus.entries.value[it] }?.readAtMs ?: 0L
            val anchorMs = maxOf(offAt, lastAdvert)
            val freshMs = WalkAwayDecider.Config(
                enabled = false,
                thresholdMs = 0,
            ).dashcamFreshMs
            if (nowMs - anchorMs > freshMs) {
                // Conditional disarm: if a concurrent markRadarConnected
                // arrived between the snapshot above and this update, the
                // off-episode that motivated BLANK is already over and
                // walkAwayArmed has been cleared / a fresh episode may have
                // begun with a new offAt. Only disarm when the cluster is
                // still on the same off-episode we observed.
                _radarLinkState.update { current ->
                    if (current.walkAwayArmed && current.radarOffSinceMs == offAt) {
                        current.copy(walkAwayArmed = false)
                    } else {
                        current
                    }
                }
                clog(
                    "# walkaway state=BLANK transition_reason=dashcam-stale " +
                        "window_ms=${nowMs - anchorMs} fresh_ms=$freshMs",
                )
            }
        }
    }

    private fun evaluateWalkAway(nowMs: Long) {
        val slug = resolveDashcamSlug()
        val dashcamLastAdvertMs = slug?.let { BatteryStateBus.entries.value[it] }?.readAtMs ?: 0L
        val link = _radarLinkState.value
        val input = WalkAwayDecider.Input(
            nowMs = nowMs,
            config = WalkAwayDecider.Config(
                // Gate on both the dedicated toggle AND the dashcam-warn
                // master switch. If the rider explicitly said "don't
                // warn me about the dashcam at all" we respect that.
                enabled = prefs.walkAwayAlarmEnabled &&
                    prefs.dashcamWarnWhenOff &&
                    slug != null,
                thresholdMs = prefs.walkAwayAlarmThresholdSec * 1000L,
            ),
            // Snapshot the cluster once so the decider sees a coherent set
            // of fields rather than a sequence of independent volatile reads.
            radarConnected = link.radarGattActive,
            radarOffSinceMs = link.radarOffSinceMs,
            dashcamLastAdvertMs = dashcamLastAdvertMs,
            armed = link.walkAwayArmed,
            sessionTotalRadarConnectedMs = link.sessionRadarConnectedMs,
            lastFireMs = link.lastWalkAwayFireMs,
            dismissedForEpisode = link.walkAwayDismissed,
        )
        when (WalkAwayDecider.decide(input)) {
            WalkAwayDecider.Action.FIRE -> {
                postWalkAwayNotification()
                startWalkAwayAlarmTone()
                _radarLinkState.update { it.copy(lastWalkAwayFireMs = nowMs) }
            }
            WalkAwayDecider.Action.AUTO_DISMISS -> {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_WALKAWAY_ID)
                stopWalkAwayAlarmTone()
                _radarLinkState.update { it.copy(lastWalkAwayFireMs = null) }
            }
            WalkAwayDecider.Action.NONE -> {}
        }
    }

    /**
     * Radar-drop audio cue: a dropped radar link looks identical to a clear
     * road on the overlay, and the rider's eyes are on the road, so the
     * warning must be audible. Fires when the radar has been down for
     * [RADAR_DROP_THRESHOLD_MS] while eBike confirms the rider is still on the
     * bike, then repeats every [RADAR_DROP_CUE_INTERVAL_MS] until reconnect.
     *
     * eBike-gated on purpose: a fresh `system_locked == false` is the only
     * signal that separates a mid-ride radar loss (cue) from a ride-end
     * dismount (no cue). That makes this mutually exclusive with the walk-away
     * alarm, which arms only when NOT unlocked. Without an eBike signal there is
     * no cue - a radar-only / no-eBike rider never gets a false ride-end fire.
     * Full design rationale + scenario matrix in [RadarDropDecider]'s KDoc.
     */
    private fun evaluateRadarDrop(nowMs: Long) {
        if (prefs.isPaused) return
        val snap = lastEBikeSnapshot
        val link = _radarLinkState.value
        val downForMs = link.radarOffSinceMs?.let { nowMs - it }
        val ebikeAgeMs = nowMs - lastEBikeSnapshotMs
        val ridingConfirmed = RadarDropDecider.ridingConfirmed(
            systemLocked = snap?.systemLocked,
            snapshotAgeMs = ebikeAgeMs,
            freshMs = RADAR_DROP_EBIKE_FRESH_MS,
        )
        val decision = RadarDropDecider.decide(
            radarEverLive = link.sessionRadarConnectedMs > 0L,
            radarDownForMs = downForMs,
            ridingConfirmed = ridingConfirmed,
            nowMs = nowMs,
            thresholdMs = RADAR_DROP_THRESHOLD_MS,
            cadenceMs = RADAR_DROP_CUE_INTERVAL_MS,
            lastCueMs = radarDropLastCueMs,
        )
        // The latch resets lazily here on the next tick that sees the radar
        // back up (downForMs == null), NOT eagerly in markRadarConnected like
        // the walk-away state. Safe because a fresh drop re-stamps
        // radarOffSinceMs and restarts below the threshold.
        radarDropLastCueMs = decision.lastCueMs
        if (decision.fire) {
            alertBeeper?.playRadarDropped()
            clog(
                "# radar_drop_cue down_ms=${downForMs ?: -1L} " +
                    "system_locked=${snap?.systemLocked} ebike_age_ms=$ebikeAgeMs",
            )
        }
        // Near-miss diagnostics: an eBike IS present but the radar-down cue is
        // held because riding isn't confirmed (the snapshot went stale, or the
        // bike is locked). Log once per down-episode so the freshness gate is
        // tunable from ride logs; reset the latch when the radar returns. Gated
        // on a non-null snapshot so a radar-only rider - whose cue is suppressed
        // by design, with nothing to tune - never logs it.
        val suppressed = link.sessionRadarConnectedMs > 0L &&
            snap != null &&
            downForMs != null &&
            downForMs >= RADAR_DROP_THRESHOLD_MS &&
            !ridingConfirmed
        if (suppressed && !radarDropSuppressLogged) {
            radarDropSuppressLogged = true
            clog(
                "# radar_drop_suppressed down_ms=$downForMs reason=riding-not-confirmed " +
                    "system_locked=${snap.systemLocked} ebike_age_ms=$ebikeAgeMs",
            )
        }
        if (downForMs == null) radarDropSuppressLogged = false
        // Reconnect acknowledgement: fires once on the tick the radar comes
        // back up, but only when a drop cue had been raised this down-episode
        // (decided in [RadarDropDecider]). Closes the ambiguity a bare silence
        // leaves after a drop cue - "back" vs "still dead".
        if (decision.fireReconnect) {
            alertBeeper?.playRadarReconnected()
            clog("# radar_reconnect_cue")
        }
    }

    private fun resolveDashcamSlug(): String? {
        val mac = prefs.dashcamMac ?: return null
        return macToSlug[mac]
            ?: macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefs.dashcamDisplayName?.let { slug(it) }
    }

    private fun postWalkAwayNotification() {
        val piFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val dismissPi = PendingIntent.getBroadcast(
            this,
            NOTIF_WALKAWAY_DISMISS_REQ,
            Intent(this, InternalControlReceiver::class.java).apply {
                action = InternalControlReceiver.ACTION_WALKAWAY_DISMISS
            },
            piFlags,
        )
        val snoozePi = PendingIntent.getBroadcast(
            this,
            NOTIF_WALKAWAY_SNOOZE_REQ,
            Intent(this, InternalControlReceiver::class.java).apply {
                action = InternalControlReceiver.ACTION_WALKAWAY_SNOOZE
            },
            piFlags,
        )
        val notif = NotificationCompat.Builder(this, ServiceNotifications.WALKAWAY_CHANNEL_ID)
            .setContentTitle(getString(R.string.svc_main_walkaway_notif_title))
            .setContentText(getString(R.string.svc_main_walkaway_notif_text))
            .setSmallIcon(R.drawable.ic_videocam_off)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(WALKAWAY_VIBRATE_PATTERN)
            .addAction(0, getString(R.string.svc_main_walkaway_action_dismiss), dismissPi)
            .addAction(0, getString(R.string.svc_main_walkaway_action_snooze), snoozePi)
            // Tapping the notification body is treated as Dismiss; swipe-
            // dismiss via setDeleteIntent also marks the episode handled.
            .setContentIntent(dismissPi)
            .setDeleteIntent(dismissPi)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_WALKAWAY_ID, notif)
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
            notifications.postForeground()
        }
    }

    private fun pruneCaptureLogs() = captureLog.prune()

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
    ).also {
        it.gravity = Gravity.TOP or Gravity.END
        it.x = 0
        it.y = 0
    }

    private fun isRearDevice(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("rear") || n.contains("rtl")
    }

    companion object {
        private const val TAG = "BikeRadar"
        private const val TAG_RADAR = "BikeRadar.Radar"
        private const val TAG_LIGHT = "BikeRadar.Light"
        const val NOTIF_BOND_LOST_ID = 2
        const val NOTIF_WALKAWAY_ID = 3
        const val NOTIF_LIGHT_FAIL_ID = 4
        const val NOTIF_RADAR_LIGHT_FAIL_ID = 5
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

        /**
         * Brings the eBike Live Data subsystem up mid-session.
         * Fire-and-forget: the onboarding eBike step sends this after the
         * rider flips eBikeDataEnabled = true so the read-only status reader
         * starts immediately, without waiting for a full service restart.
         * No-op if the flag is off or BLE permissions are missing.
         */
        const val ACTION_START_EBIKE_READER = "es.jjrh.bikeradar.START_EBIKE_READER"

        /**
         * Tear the existing status reader down and start a fresh one.
         * [ACTION_START_EBIKE_READER] alone is idempotent - it no-ops when the
         * reader is already running - so a forced restart needs a stop
         * first.
         */
        const val ACTION_RESTART_EBIKE_READER = "es.jjrh.bikeradar.RESTART_EBIKE_READER"

        /** Debug-only radar light-mode write-probe (see [probeWriteRadarLight]).
         *  Forwarded from the dev-only [RemoteControlReceiver]. */
        const val ACTION_RADAR_LIGHT_PROBE_WRITE = "es.jjrh.bikeradar.RADAR_LIGHT_PROBE_WRITE"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"
        const val EXTRA_RADAR_LIGHT_NN = "nn"
        const val EXTRA_RADAR_LIGHT_HEX = "hex"

        const val THROTTLE_MS = 5 * 60 * 1000L
        const val ATTEMPT_COOLDOWN_MS = 30 * 1000L

        // Reconnect backoff: starts fast, doubles on each consecutive failure,
        // caps at 8 s. Resets to the initial value once a connection reaches
        // the V2 decode loop. Quick-reconnect (post-handshake-ABORT) bypasses
        // backoff entirely.
        const val RADAR_RECONNECT_BACKOFF_INITIAL_MS = 1_000L
        const val RADAR_RECONNECT_BACKOFF_MAX_MS = 8_000L
        const val RADAR_QUICK_RECONNECT_MS = 1_500L

        // After the radar has been offline past `longOfflineThresholdMs`,
        // the cap relaxes to `longOfflineCapMs`. At the steady-state 8 s
        // ceiling a parked-overnight bike would otherwise trigger ~10,800
        // GATT opens per 24 h; the longer cap lets the radio idle while
        // still picking up the radar within one cycle of return.
        @androidx.annotation.VisibleForTesting
        internal fun reconnectBackoffCap(
            now: Long,
            offSinceMs: Long?,
            longOfflineThresholdMs: Long,
            longOfflineCapMs: Long,
        ): Long {
            if (offSinceMs == null) return RADAR_RECONNECT_BACKOFF_MAX_MS
            return if (now - offSinceMs > longOfflineThresholdMs) {
                longOfflineCapMs
            } else {
                RADAR_RECONNECT_BACKOFF_MAX_MS
            }
        }

        // Phone-battery sample written into the capture log at most once per
        // this period. The capture-log line is comment-prefixed so existing
        // decoders skip it cleanly.
        const val PHONE_BATTERY_LOG_PERIOD_MS = 60_000L

        @androidx.annotation.VisibleForTesting
        internal fun formatPhoneBatteryLog(
            unixMs: Long,
            level: Int,
            scale: Int,
            tempDc: Int,
            plugged: Int,
        ): String {
            val pct = if (level < 0 || scale <= 0) -1 else (level * 100) / scale
            return "# phone t=$unixMs level=$pct temp_dc=$tempDc charging=${plugged != 0}"
        }

        // V2 data-flow watchdog: if no V2 notification has been observed for
        // V2_FRAME_STALL_MS, the link is considered stuck and the GATT is
        // torn down so the outer loop reconnects.
        const val V2_WATCHDOG_TICK_MS = 2_000L
        const val V2_FRAME_STALL_MS = 5_000L
        const val BATTERY_STALE_MS = 15 * 60 * 1000L

        /** Rear-radar battery percentage below which the in-ride audible
         *  critical-battery cue fires. The general `batteryLowThresholdPct`
         *  still drives only the silent visual glyph; this stricter level is
         *  what adds the sound. */
        const val CRITICAL_BATTERY_PCT = 10

        /** Minimum gap between repeats of the critical-battery cue while the
         *  radar battery stays below [CRITICAL_BATTERY_PCT]. Sparing by
         *  design: a critical battery the rider cannot fix mid-ride must not
         *  nag. */
        const val CRITICAL_BATTERY_CUE_INTERVAL_MS = 120_000L

        /** Radar-drop cue: continuous radar-off time before the first cue.
         *  Deliberately generous so the normal end-of-ride wind-down (radar
         *  off around when the dashcam goes off) never trips it. */
        const val RADAR_DROP_THRESHOLD_MS = 60_000L

        /** Radar-drop cue re-fire gap while the radar stays down. */
        const val RADAR_DROP_CUE_INTERVAL_MS = 180_000L

        /** Max age of the eBike snapshot for its `system_locked` to be trusted
         *  by the radar-drop cue. Older than this means the eBike link has
         *  itself dropped (rider left), so "unlocked" can't be believed. */
        const val RADAR_DROP_EBIKE_FRESH_MS = 30_000L

        /** Same trust window for the walk-away arming gate: a `system_locked =
         *  false` older than this is a stale reading from before the eBike link
         *  dropped and must NOT suppress arming. Separate from the radar-drop
         *  constant so the two gates can be tuned independently. */
        const val WALKAWAY_EBIKE_FRESH_MS = 30_000L

        /** Re-fire gap for the pre-flight LOW-battery cue (L8). 30 min is
         *  long enough that a sub-30-min commute gets exactly one heads-up
         *  per device at connect, and it re-arms for the next ride. */
        const val PREFLIGHT_BATTERY_CUE_INTERVAL_MS = 30 * 60 * 1000L

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

        // Connect-storm guard: while the radar is connected, a dashcam whose
        // liveness probe keeps failing (powered off) is retried with doubling
        // backoff (base DASHCAM_REFRESH_MS) capped here, so it can't connect-storm
        // the radar link. 60s: a dashcam switched back on mid-ride is re-detected
        // within <=60s (auto-light-off config; the camera-light link detects
        // faster when on). Backoff never applies while the radar is disconnected.
        const val DASHCAM_PROBE_BACKOFF_CAP_MS = 60_000L

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

        // Hard cap on how long the looping alarm tone keeps playing
        // before it is force-stopped. Without this the tone loops until
        // the user dismisses; this caps a forgotten alert at one
        // bounded burst.
        private const val WALKAWAY_RINGTONE_CAP_MS = 60_000L

        // Override detection: blips shorter than this are treated as the same ride session.
        private const val CAMERA_LIGHT_OVERRIDE_DEADBAND_MS = 120_000L
        private const val RADAR_LIGHT_OVERRIDE_DEADBAND_MS = 120_000L

        @Volatile var activeCaptureLogName: String? = null
            internal set

        /** MAC (identity address) -> slug used in BatteryStateBus entries.
         *  Populated by doReadBattery + the piggyback read path; read by the
         *  overlay composer to resolve the user-selected dashcam MAC to the
         *  right battery entry. */
        val macToSlug = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun slug(name: String): String = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .removePrefix("varia_")
            .ifEmpty { "device" }
    }
}
