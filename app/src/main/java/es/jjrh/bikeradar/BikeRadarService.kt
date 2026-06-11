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
import android.content.SharedPreferences
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
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class BikeRadarService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var creds: HaCredentials

    @Volatile private lateinit var ha: HaClient

    /** Strong reference required: SharedPreferences holds listeners weakly,
     *  so an inline lambda would be collected and silently stop firing. */
    private val haCredsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            ha = HaClient(creds.baseUrl, creds.token)
        }

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

    // Per-ride statistics. Reset on onCreate AND when the radar reconnects
    // after the long-offline gap (the bike was parked - a new ride starts
    // with fresh numbers; see maybePostRideSummary). Published to HA via
    // the periodic loop started below and summarised in the post-ride
    // notification. @Volatile: the tick coroutine replaces the instance;
    // the overlay loop and beeper-cue closure read it from other threads.
    @Volatile private var rideStats = RideStatsAccumulator()

    // Ride-summary notification state: one post per radar-off episode.
    // Tick-coroutine-confined; see maybePostRideSummary.
    private var rideSummaryPosted = false
    private var lastRadarOffSinceMs: Long? = null

    // ── Walk-away alarm state ────────────────────────────────────────────────
    // See WalkAwayDecider for the decision logic. The service owns all
    // mutable fields and samples them from a separate 2 s tick coroutine so
    // the alarm can evaluate while the radar is disconnected and no
    // RadarState is flowing.
    // The radar-link / walk-away cluster + its transition logic live in
    // [RadarLinkCoordinator], built in onCreate. It owns the single
    // MutableStateFlow so multi-field transitions are atomic against readers,
    // drives the walk-away alarm + radar-drop cue, and is the
    // [RadarLinkStateGateway] the radar GATT loop reports connect/disconnect to.
    // The snooze-job AtomicReference (below) stays service-owned: it's a
    // cancellable side effect, not state.
    private lateinit var radarLinkCoordinator: RadarLinkCoordinator

    // Thin read accessors for the two remaining service-side read sites
    // (scheduleRead light-flip guard, walk-away tick cadence). All other reads
    // moved into the coordinator with the transitions.
    private val radarGattActive get() = radarLinkCoordinator.snapshot().radarGattActive
    private val radarOffSinceMs get() = radarLinkCoordinator.snapshot().radarOffSinceMs

    // ── Bosch eBike live-data snapshot ───────────────────────────────
    // The snapshot cache + everything derived from it (odometer baseline,
    // ride-edge + climb detection) live in [EBikeSnapshotCoordinator], fed by
    // the status reader's callback. The service keeps only the reader lifecycle
    // (below). snapshot()==null when the feature is off / no bonded eBike - the
    // graceful-degradation path radar-only riders take.
    private lateinit var ebikeSnapshotCoordinator: EBikeSnapshotCoordinator

    // Sticky: true once the radar has decoded >=1 vehicle this session. Gates the
    // dead-radar banner - no traffic ever seen means a bench test, not a ride.
    // Set by a RadarStateBus collector in onCreate; read by RadarLinkCoordinator.
    @Volatile private var sawTrack = false

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

    // The radar-link / walk-away cluster (radarOffSinceMs, sessionRadarConnectedMs,
    // walkAwayArmed, walkAwayDismissed, lastWalkAwayFireMs ...) and its
    // ARMED/BLANK/IDLE state machine live in [RadarLinkCoordinator] - re-arm
    // only via a fresh radar power-on, not via an inter-ride dashcam advert,
    // the canonical model pinned by WalkAwayDeciderTest; see [WalkAwayDecider]
    // class KDoc for the full rationale.

    /** Single-slot job that clears the walk-away dismissal after a snooze
     *  window expires, re-arming the decider. AtomicReference makes
     *  the cancel-then-replace pattern atomic across the main thread
     *  (notification action handlers) and GATT callback / IO threads
     *  (the coordinator's markConnected). */
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
        scope.launch { RadarStateBus.state.collect { if (it.vehicles.isNotEmpty()) sawTrack = true } }
        creds = HaCredentials(this)
        creds.seedFromBuildConfigIfEmpty()
        ha = HaClient(creds.baseUrl, creds.token)
        // Rebuild the client when the user saves new HA credentials
        // mid-session; consumers hold `{ ha }` providers, so close-pass and
        // front-mode publishes pick up the change without a service restart.
        // The decryption cost lands here (once per save), not per frame.
        creds.registerOnChangeListener(haCredsListener)
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
            ha = { ha },
            beeper = alertBeeper!!,
            overlayHost = overlayHost,
            phoneBattery = AndroidPhoneBatterySource(this),
            rideStats = { rideStats },
            overlayPrefsSnapshot = { cachedOverlayPrefs ?: prefs.snapshot() },
            ebikeSnapshot = { ebikeSnapshotCoordinator.snapshot() },
            climbingNow = { ebikeSnapshotCoordinator.climbing() },
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
        ebikeSnapshotCoordinator = EBikeSnapshotCoordinator(
            clock = { System.currentTimeMillis() },
            clog = ::clog,
            publishRideEdge = { edge, iso -> haPublisher.publishRideEdgeIfHa(edge, iso) },
            nowIso = { java.time.Instant.now().toString() },
        )
        walkAwayAlarm = WalkAwayAlarm(this, scope)
        radarLinkCoordinator = RadarLinkCoordinator(
            clock = { System.currentTimeMillis() },
            prefs = prefs,
            postWalkAwayNotification = notifications::postWalkAway,
            cancelWalkAwayNotification = notifications::cancelWalkAway,
            startWalkAwayAlarm = walkAwayAlarm::start,
            stopWalkAwayAlarm = walkAwayAlarm::stop,
            alertBeeper = { alertBeeper },
            clog = ::clog,
            setReconnectBanner = ::setReconnectBanner,
            resolveDashcamSlug = ::resolveDashcamSlug,
            eBikeSnapshot = { ebikeSnapshotCoordinator.snapshot() },
            eBikeSnapshotAtMs = { ebikeSnapshotCoordinator.snapshotAtMs() },
            hasEBikeSignal = { ebikeSnapshotCoordinator.hasEverSeenSnapshot() },
            everSawTrack = { sawTrack },
            postForgotToLock = notifications::postForgotToLock,
            cancelForgotToLock = notifications::cancelForgotToLock,
            cancelWalkAwaySnooze = { walkAwaySnoozeJob.getAndSet(null)?.cancel() },
            clearDashcamBackoff = {
                prefs.dashcamMac?.let {
                    dashcamProbeFailures.remove(it)
                    lastDashcamProbeMs.remove(it)
                }
            },
        )
        radarLink = RadarLinkController(
            context = this,
            scope = scope,
            prefs = prefs,
            captureLog = captureLog,
            overlayPipeline = overlayPipeline,
            haPublisher = haPublisher,
            notifications = notifications,
            linkState = radarLinkCoordinator,
            macToSlug = macToSlug,
            slug = { name -> slug(name) },
        )
        cameraLink = CameraLightLinkController(
            context = this,
            scope = scope,
            prefs = prefs,
            ha = { ha },
            haPublisher = haPublisher,
            notifications = notifications,
            macToSlug = macToSlug,
            slug = { name -> slug(name) },
            radarOffSinceMs = { radarLinkCoordinator.snapshot().radarOffSinceMs },
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
     * into [EBikeSnapshotCoordinator] (for the AlertDecider stationary override +
     * walk-away gate) and [EBikeStateBus] (for the SYSTEM-card eBike row). It
     * never writes to the bike.
     *
     * Flag off / permission missing / no bonded eBike → reader not started,
     * the coordinator snapshot stays null, all downstream consumers fall back to
     * the radar's own bike-speed reading. Graceful degradation for radar-only riders.
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
                ebikeSnapshotCoordinator.onSnapshot(snap)
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
                radarLinkCoordinator.markWalkAwayDismissed()
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
                radarLinkCoordinator.markWalkAwayDismissed()
                notifications.cancelWalkAway()
                walkAwayAlarm.stop()
                val newJob = scope.launch {
                    delay(WALKAWAY_SNOOZE_MS)
                    // Clear both gates so the decider can re-evaluate
                    // cleanly: treat the snooze as a full re-arm for this
                    // episode rather than "it's been 2 minutes, fire
                    // again immediately".
                    radarLinkCoordinator.clearWalkAwayDismissalForReArm()
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
        if (::creds.isInitialized) creds.unregisterOnChangeListener(haCredsListener)
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
                val link = radarLinkCoordinator.snapshot()
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
                radarLinkCoordinator.tickWalkAwayState(now, elapsed)
                radarLinkCoordinator.evaluateWalkAway(now)
                radarLinkCoordinator.evaluateRadarDrop(now)
                maybePostRideSummary(now)
            }
        }
    }

    /** Ride-summary notification + new-ride stats reset, evaluated from the
     *  walk-away tick (the only loop that keeps running while the radar is
     *  off). Decision logic lives in [RideSummaryNotificationDecider]; this
     *  glue tracks the off-episode edges: a sustained off-episode posts the
     *  summary once, and a reconnect after the long-offline gap (the same
     *  Settings boundary the reconnect loop treats as "parked") starts a new
     *  ride with fresh stats. */
    private fun maybePostRideSummary(nowMs: Long) {
        val off = radarOffSinceMs
        if (off == null) {
            lastRadarOffSinceMs?.let { wasOffSince ->
                val offDuration = nowMs - wasOffSince
                val longOffMs = prefs.radarLongOfflineThresholdMinutes * 60_000L
                if (RideSummaryNotificationDecider.shouldStartNewRide(offDuration, longOffMs)) {
                    rideStats = RideStatsAccumulator()
                }
                rideSummaryPosted = false
            }
            lastRadarOffSinceMs = null
            return
        }
        lastRadarOffSinceMs = off
        // Snapshot only once the dwell could pass. The accumulator is
        // single-context by contract and its Main-thread writer can still be
        // in its cancellation tail for a moment after a disconnect; the
        // 3-minute dwell dwarfs that window, so gating the read on it keeps
        // this IO-side reader out of the race entirely.
        if (rideSummaryPosted || nowMs - off < RideSummaryNotificationDecider.POST_DWELL_MS) return
        val snap = rideStats.snapshot()
        if (RideSummaryNotificationDecider.shouldPost(off, nowMs, rideSummaryPosted, snap)) {
            rideSummaryPosted = true
            notifications.postRideSummary(snap)
        }
    }

    /** Show/hide/retitle the service-owned dead-radar banner per the
     *  [RadarLinkVisualDecider.LinkVisual]. Idempotent; all WindowManager work
     *  runs on the main thread, where the view ref is the only reader/writer (so
     *  no extra synchronisation). LIVE detaches; a non-LIVE variant attaches a
     *  fresh overlay (or updates the message on an already-attached one, e.g. a
     *  late eBike snapshot flipping PLAIN -> UNLOCKED). canDrawOverlays is
     *  re-checked for the permission TOCTOU, mirroring the pipeline's attach. */
    private fun setReconnectBanner(visual: RadarLinkVisualDecider.LinkVisual) {
        scope.launch(Dispatchers.Main) {
            val current = reconnectBannerView
            if (visual != RadarLinkVisualDecider.LinkVisual.LIVE) {
                if (current == null) {
                    if (!reconnectHost.canDrawOverlays()) return@launch
                    val v = reconnectHost.createView()
                    v.setReconnecting(visual)
                    if (reconnectHost.attach(v) == null) {
                        reconnectBannerView = v
                        clog("# reconnect_banner shown variant=$visual")
                    }
                } else {
                    current.setReconnecting(visual)
                }
            } else if (current != null) {
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
