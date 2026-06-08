// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.data.PrefsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class BikeRadarService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var creds: HaCredentials
    private lateinit var ha: HaClient
    private lateinit var haPublisher: HaPublisher
    private lateinit var notifications: ServiceNotifications
    private lateinit var walkAwayAlarm: WalkAwayAlarm
    private lateinit var radarLink: RadarLinkController
    private lateinit var cameraLink: CameraLightLinkController
    private lateinit var batteryReader: BatteryReader
    private lateinit var knownDevices: KnownDevices

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

    // Overlay window + view ownership lives in AndroidOverlayHost; the
    // service forwards onConfigurationChanged so the host re-applies layout
    // params on rotation.
    private lateinit var overlayHost: AndroidOverlayHost

    // Second overlay host for the service-owned "reconnecting" banner shown
    // while the rear-radar link is down (the per-connection pipeline and its
    // view are torn down during the drop, so this is a separate surface).
    // Driven off RadarLinkVisualDecider from the walk-away tick. The view ref
    // is touched only on the main thread (show/hide dispatch to Main).
    private lateinit var reconnectHost: AndroidOverlayHost
    private var reconnectBannerView: RadarOverlayView? = null

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
        knownDevices = KnownDevices(getSharedPreferences(PREFS_THROTTLE, MODE_PRIVATE))
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
        reconnectHost = AndroidOverlayHost(this, ::buildOverlayParams)
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
            currentRadarMac = { radarLink.currentRadarMac },
            macToSlug = { macToSlug },
            clog = { line -> clog(line) },
        )
        haPublisher = HaPublisher(
            scope = scope,
            creds = creds,
            rideStats = { rideStats },
            currentRadarMac = { radarLink.currentRadarMac },
            macToSlug = { macToSlug },
            loadKnownDevices = { knownDevices.load() },
            slug = { name -> slug(name) },
        )
        walkAwayAlarm = WalkAwayAlarm(this, scope)
        radarLink = RadarLinkController(
            context = this,
            scope = scope,
            prefs = prefs,
            captureLog = captureLog,
            overlayPipeline = overlayPipeline,
            haPublisher = haPublisher,
            notifications = notifications,
            linkState = object : RadarLinkStateGateway {
                override fun markConnected() = markRadarConnected()
                override fun markDisconnected() = markRadarDisconnected()
                override fun snapshot(): RadarLinkState = _radarLinkState.value
            },
            macToSlug = macToSlug,
            slug = { name -> slug(name) },
        )
        cameraLink = CameraLightLinkController(
            context = this,
            scope = scope,
            prefs = prefs,
            ha = ha,
            haPublisher = haPublisher,
            notifications = notifications,
            macToSlug = macToSlug,
            slug = { name -> slug(name) },
            radarOffSinceMs = { _radarLinkState.value.radarOffSinceMs },
        )
        batteryReader = BatteryReader(
            context = this,
            scope = scope,
            prefs = prefs,
            knownDevices = knownDevices,
            haPublisher = haPublisher,
            macToSlug = macToSlug,
            slug = { name -> slug(name) },
            dashcamProbeFailures = dashcamProbeFailures,
        )

        pruneCaptureLogs()
        schedulePauseExpiry()
        registerEventScan()
        radarLink.registerBondReceiver()
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
                radarLink.forceReconnect()
            }
            ACTION_WALKAWAY_DISMISS -> {
                Log.i(TAG, "walk-away dismissed")
                _radarLinkState.update { it.copy(walkAwayDismissed = true) }
                walkAwaySnoozeJob.getAndSet(null)?.cancel()
                notifications.cancelWalkAway()
                walkAwayAlarm.stop()
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
                notifications.cancelWalkAway()
                walkAwayAlarm.stop()
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
                    radarLink.probeWriteRadarRaw(hex)
                } else {
                    radarLink.probeWriteRadarLight(intent.getIntExtra(EXTRA_RADAR_LIGHT_NN, -1))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseExpiryJob?.cancel()
        if (::cameraLink.isInitialized) cameraLink.stop()
        unregisterEventScan()
        if (::radarLink.isInitialized) radarLink.shutdown()
        closeCaptureLog()
        RadarStateBus.clear()
        if (::reconnectHost.isInitialized) {
            reconnectBannerView?.let { reconnectHost.detach(it) }
            reconnectBannerView = null
        }
        if (::walkAwayAlarm.isInitialized) walkAwayAlarm.stop()
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
        notifications.cancelWalkAway()
        notifications.cancelBondLost()
        // Companion-object cache survives across service instances within the
        // same process; clear it so Stop = clean slate for MAC->slug resolution.
        macToSlug.clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlayHost.isInitialized) overlayHost.onConfigurationChanged()
        if (::reconnectHost.isInitialized) reconnectHost.onConfigurationChanged()
    }

    // ── battery scan kickstart ────────────────────────────────────────────────

    private suspend fun kickstartFromCache() {
        val known = knownDevices.load()
        for ((name, mac) in known) scheduleRead(name, mac)

        // Always-on PendingIntent scan (registerEventScan) covers ongoing
        // discovery and post-pairing recovery via BatteryScanReceiver, so
        // the active kickstart is only needed when the cache is empty
        // (first-run or post-clear).
        if (known.isNotEmpty()) return

        val fresh = scanForDevices(timeoutMs = 3_000)
        if (fresh.isNotEmpty()) {
            knownDevices.save(fresh)
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
        if (shouldLinkRadar) radarLink.start(name, mac)

        if (isRearDevice(name) && (radarGattActive || radarLink.isActive())) {
            Log.d(TAG, "skip $name (radar gatt active, piggyback will read instead)")
            return
        }

        // Start or gate the camera light link when this is the configured dashcam.
        val isDashcam = prefs.dashcamMac?.equals(mac, ignoreCase = true) == true
        if (isDashcam && prefs.autoLightModeEnabled) cameraLink.start(name, mac)
        if (isDashcam && (cameraLink.isGattActive() || cameraLink.isActive())) {
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
        batteryReader.launch(name, mac)
    }

    // ── capture log ───────────────────────────────────────────────────────────

    private fun closeCaptureLog() = captureLog.close()

    fun clog(msg: String) = captureLog.clog(msg)

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
                            batteryReader.launch(name, mac)
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
            notifications.cancelWalkAway()
            clog("# walkaway state=IDLE transition_reason=radar-connected prev_state=$prevState")
        }
        // Radar is back: hide the reconnecting banner now rather than waiting
        // for the next (up to 30s idle) tick of evaluateRadarDrop.
        setReconnectBanner(false)
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
                notifications.postWalkAway()
                walkAwayAlarm.start()
                _radarLinkState.update { it.copy(lastWalkAwayFireMs = nowMs) }
            }
            WalkAwayDecider.Action.AUTO_DISMISS -> {
                notifications.cancelWalkAway()
                walkAwayAlarm.stop()
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
        val link = _radarLinkState.value
        val downForMs = link.radarOffSinceMs?.let { nowMs - it }
        // Visual "reconnecting" banner: shown whenever the rear link is down
        // past the visual threshold. NOT eBike-gated (unlike the audio cue
        // below) - it is a glanceable status and the only dead-radar signal a
        // radar-only rider gets. Hidden while paused; the eager hide on
        // reconnect lives in markRadarConnected so it doesn't wait for the
        // (up to 30s) idle tick.
        val visual = RadarLinkVisualDecider.decide(
            radarEverLive = link.sessionRadarConnectedMs > 0L,
            radarDownForMs = downForMs,
            visualThresholdMs = RADAR_DROP_VISUAL_THRESHOLD_MS,
            paused = prefs.isPaused,
        )
        // Must run before the isPaused early-return below so a pause HIDES an
        // already-shown banner (decide() returns LIVE when paused).
        setReconnectBanner(visual == RadarLinkVisualDecider.LinkVisual.RECONNECTING)
        if (prefs.isPaused) return
        val snap = lastEBikeSnapshot
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

    /** Show/hide the service-owned "reconnecting" banner. Idempotent; all
     *  WindowManager work runs on the main thread, where the view ref is the
     *  only reader/writer (so no extra synchronisation). A null view + show
     *  attaches a fresh reconnecting overlay; a non-null view + hide detaches
     *  it. canDrawOverlays is re-checked for the permission TOCTOU, mirroring
     *  the pipeline's attach path. */
    private fun setReconnectBanner(show: Boolean) {
        scope.launch(Dispatchers.Main) {
            val current = reconnectBannerView
            if (show && current == null) {
                if (!reconnectHost.canDrawOverlays()) return@launch
                val v = reconnectHost.createView()
                v.setReconnecting(true)
                if (reconnectHost.attach(v) == null) {
                    reconnectBannerView = v
                    clog("# reconnect_banner shown")
                }
            } else if (!show && current != null) {
                reconnectBannerView = null
                reconnectHost.detach(current)
                clog("# reconnect_banner hidden")
            }
        }
    }

    private fun resolveDashcamSlug(): String? {
        val mac = prefs.dashcamMac ?: return null
        return macToSlug[mac]
            ?: macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefs.dashcamDisplayName?.let { slug(it) }
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

        /** Visual "reconnecting" banner: continuous radar-off time before the
         *  overlay marks the rear blind. Far shorter than the audio cue's 60s
         *  because a glanceable status banner is cheap (not an interruptive
         *  nag) and is the ONLY dead-radar signal a radar-only rider gets. 10s
         *  rides through normal reconnects (corpus median 8.4s, floor 5.3s) and
         *  marks the screen only once a drop is likely real. Not eBike-gated. */
        const val RADAR_DROP_VISUAL_THRESHOLD_MS = 10_000L

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

        @Volatile var activeCaptureLogName: String? = null
            internal set

        /** MAC (identity address) -> slug used in BatteryStateBus entries.
         *  Populated by the battery read path ([BatteryReader]) + the radar/
         *  camera piggyback reads; read by the overlay composer to resolve the
         *  user-selected dashcam MAC to the right battery entry. */
        val macToSlug = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun slug(name: String): String = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .removePrefix("varia_")
            .ifEmpty { "device" }
    }
}
