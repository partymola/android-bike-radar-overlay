// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the front camera link tells the dashcam walk-away alarm. Implemented by
 *  [RadarLinkCoordinator]. */
internal interface DashcamLinkGateway {
    /** A handshake completed: the camera is on, and heard until its link ends. */
    fun markDashcamHeard(mac: String)

    /** The camera ended a link whose handshake completed
     *  ([CameraLightLinkController.isSwitchOff]). */
    fun markDashcamSwitchedOff(mac: String)

    /** A connection ended, whatever ended it: reported from its teardown. */
    fun markDashcamLinkDown()

    /** The link loop ended. Until a sighting starts it again, nothing reports the camera. */
    fun markDashcamLinkGone()
}

/**
 * Owns the front camera/light BLE link: the start/reconnect lifecycle, the GATT
 * connect -> AMV handshake (FRONT_CAMERA variant) -> mode-state-notify loop, and
 * the time-of-day light auto-mode (the same pure [LightAutoModeDecider] the
 * radar tail-light uses). A parallel, near-mirror of [RadarLinkController];
 * unlike the radar it owns no shared service state. It reads the radar's
 * off-time [radarOffSinceMs], the "bike parked" proxy for the long-offline
 * reconnect-backoff cap (shared with the radar loop), and reports the camera's
 * handshakes, switch-offs, each connection's end and its loop's end to
 * [dashcamLink].
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
    /** No default: a link reporting to nothing would leave a switched-off
     *  camera counted as on. */
    @get:androidx.annotation.VisibleForTesting
    internal val dashcamLink: DashcamLinkGateway,
    /** Whether one of the ride's cues is sounding ([OverlayPipeline.cueSounding]),
     *  which the light-failure alert waits out. No default: a link wired to
     *  nothing would sound its alert over every cue. */
    @get:androidx.annotation.VisibleForTesting
    internal val cueSounding: Flow<Boolean>,
    /** Monotonic clock for the camera-light override deadband; elapsedRealtime
     *  so a wall-clock jump can't mis-clear the rider's manual override. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** Wall clock the light plan is set against sunrise and sunset by, which
     *  elapsedRealtime is not, and the clock a rider's pause deadline is read
     *  against while a failure alert is held. Injected so a test can pin it. */
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    /** GATT opener seam, defaulting to the shared LE-transport [connectGattLe].
     *  Injected only by the Robolectric harness so a test can capture the
     *  connection's [BluetoothGattCallback] and drive the callbacks by hand;
     *  production keeps the real connect path unchanged. */
    private val openGatt: (Context, BluetoothDevice, Boolean, BluetoothGattCallback) -> BluetoothGatt? = ::connectGattLe,
) {
    private val linkProbe = LinkProbeRecorder(
        load = { prefs.cameraLinkProbe },
        store = { prefs.cameraLinkProbe = it },
    )

    /**
     * Record an attempt that stopped before there was a service table to
     * describe, matching the radar's handling.
     *
     * Silence is not neutral here: the slot keeps the previous attempt's
     * answer, so a bundle would report a camera stopping point this attempt
     * never reached. Shares the debounce with [recordLinkProbe], so a camera
     * failing this way on every retry still writes once and keeps its stamp.
     */
    private fun recordPreDiscoveryExit(outcome: String) {
        linkProbe.record(LinkProbe.format(emptyList(), outcome))
    }

    /**
     * Persist what this attempt found and where it stopped, for the
     * diagnostic bundle. Same contract as the radar's, and the same reason:
     * without it a camera that connects and never completes its handshake
     * produces no capture log at all, so nothing names the failing step.
     *
     * The two exits before service discovery reach [recordPreDiscoveryExit]
     * instead, so the slot never reports a stopping point this attempt did
     * not reach.
     */
    private fun recordLinkProbe(gatt: BluetoothGatt, outcome: String) {
        val body = try {
            LinkProbe.format(
                gatt.services.map { svc ->
                    svc.uuid.toString().substring(4, 8) to
                        svc.characteristics.map { it.uuid.toString().substring(4, 8) }
                },
                outcome,
            )
        } catch (t: Throwable) {
            // Swallowed on purpose: a diagnostic must not take down a link,
            // and this coroutine rides a scope with no exception handler.
            Log.w(TAG, "camera link probe failed: $t")
            return
        }
        linkProbe.record(body)
    }

    // The connection coroutine; single-slot, guarded by start()'s @Synchronized.
    @Volatile private var cameraLightJob: Job? = null

    @Volatile private var cameraLightGattActive = false

    @Volatile private var cameraLightUserOverride = false

    @Volatile private var cameraLightLastWrittenMode: CameraLightMode? = null

    // The mode a set is writing, so the camera's echo of it, which can reach
    // the notify loop before the write's answer reaches the set, is not read as
    // a press (`anEchoOfTheSetBeforeItsAnswerIsNotAPress`). A set clears only
    // the value it set, so a cancelled set of an old connection cannot clear a
    // newer one's. No test reaches that case.
    private class Setting(val mode: CameraLightMode)

    private val cameraLightSetting = AtomicReference<Setting?>(null)

    @Volatile private var cameraLightOffSinceMs: Long? = null

    // Set when a failure alert goes out, cleared by a light write that lands or
    // a connect that starts a new ride. Here, not per connection, so a flapping
    // link alerts once a ride (`aRepeatFailureOnALaterConnectionOnlyUpdatesTheEntry`).
    // Written only under [lightFailLock]; the one unlocked read decides only
    // whether a report waits on the hold.
    @Volatile private var lightFailAlerted = false

    // Light writes landed, counted so a report can tell one landed since its
    // failure.
    @Volatile private var lightWritesLanded = 0

    private val lightFailLock = Any()

    // The current connection's ended-by-app flag, so stop() can mark it before
    // the cancel reaches the connection's own teardown
    // (`theLoopEndingReportsTheLinkGoneAndALinkTheAppEndedIsNoSwitchOff`).
    @Volatile private var connectionEndedByApp: AtomicBoolean? = null

    @Synchronized
    fun start(name: String, mac: String) {
        if (cameraLightJob?.isActive == true) return
        Log.i(TAG, "starting camera light link to $name ${LogRedaction.mac(mac)}")
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
        connectionEndedByApp?.set(true)
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
                // The captured mac, not merely a non-null one: a rider who turns
                // the ownership switch off mid-session, or picks a different
                // camera, must not leave this loop reconnecting to the old one.
                // Between sessions, like the toggle above it, so a camera that
                // is already connected keeps that session until it drops.
                // `aCameraTheRiderSwitchedOffIsNeverConnectedTo` and
                // `aLiveLinkDropsWhenItIsNoLongerTheSelectedCamera` pin the two
                // entries; nothing reaches the live teardown, which needs a ride.
                if (prefs.activeDashcamMac?.equals(mac, ignoreCase = true) != true) {
                    Log.i(TAG, "camera is no longer the selected one; exiting link")
                    journal("camera link exit: no longer the selected camera")
                    return
                }
                Log.i(TAG, "connect attempt to $name ${LogRedaction.mac(mac)}")
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
            dashcamLink.markDashcamLinkGone()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndRunCameraLight(device: BluetoothDevice, name: String): Boolean {
        val notifyChannel = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        val queue = BleOpQueue()
        val servicesReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
        // Read on the binder thread that delivers the disconnect.
        val handshakeDone = AtomicBoolean(false)
        val endedByApp = AtomicBoolean(false).also { connectionEndedByApp = it }
        // Set by the disconnect callback before it fails the pending write, so
        // a set that failed because of it reads the link as gone.
        val linkDown = AtomicBoolean(false)
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
                        linkDown.set(true)
                        // Before notifyChannel.close(), which lets the connection's
                        // finally run and set endedByApp.
                        val adapterOn = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                            ?.adapter?.state == BluetoothAdapter.STATE_ON
                        if (isSwitchOff(status, adapterOn, handshakeDone.get(), endedByApp.get())) {
                            dashcamLink.markDashcamSwitchedOff(device.address)
                        }
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

        gatt = openGatt(context, device, true, cb)
        if (gatt == null) {
            Log.w(TAG, "connectGatt returned null")
            journal("camera connectGatt returned null")
            recordPreDiscoveryExit(RadarLinkController.NO_GATT)
            return false
        }

        val queueJob = scope.launch { queue.run() }

        // The initial set and the dusk or dawn switch, which a link's end
        // cancels without waiting on them.
        val jobs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        return try {
            val ok = servicesReady.await()
            if (!ok) {
                Log.w(TAG, "service discovery failed")
                journal("camera services discovery failed")
                recordPreDiscoveryExit(RadarLinkController.DISCOVERY_FAILED)
                return false
            }

            cameraLightGattActive = true
            val offSince = cameraLightOffSinceMs
            if (CameraLightOverrideDecider.shouldClearOverride(offSince, clock(), CAMERA_LIGHT_OVERRIDE_DEADBAND_MS)) {
                cameraLightUserOverride = false
                Log.i(TAG, "override cleared after ${(clock() - (offSince ?: 0)) / 1000}s off")
            }
            // A new ride for the failure alert is the camera back after the rider's
            // idle-radar gap, the app's own boundary, read on the camera's own off
            // time so it holds with no radar (`aFailureOnANewRideAlertsInFullAgain`,
            // `aFailureAMinuteShortOfTheNewRideGapOnlyUpdatesTheEntry`,
            // `theNewRideGapIsTheRidersOwn`).
            if (offSince != null &&
                RideSummaryNotificationDecider.shouldStartNewRide(clock() - offSince, prefs.radarLongOfflineThresholdMinutes * 60_000L)
            ) {
                synchronized(lightFailLock) { lightFailAlerted = false }
            }
            cameraLightOffSinceMs = null
            Log.i(TAG, "connected, running handshake")

            val handshakeAbort = EnablingSequence.runHandshake(
                gatt,
                queue,
                notifyChannel,
                DeviceVariant.FRONT_CAMERA,
                // Debug builds only: the script lines carry the raw handshake
                // exchange with the camera, which no release sink records.
            ) { msg -> if (BuildConfig.DEBUG) Log.d(TAG, msg) }

            recordLinkProbe(gatt, handshakeAbort ?: RadarLinkController.HANDSHAKE_OK)

            if (handshakeAbort != null) {
                Log.w(TAG, "handshake failed; closing for quick reconnect")
                journal("camera handshake failed at $handshakeAbort (quick reconnect)")
                gatt.disconnect()
                return true
            }

            Log.i(TAG, "handshake complete; subscribing mode-state notify")
            journal("camera handshake complete")
            // Heard first: a switch-off landing between the two is then not
            // counted, rather than counted and at once cleared.
            dashcamLink.markDashcamHeard(device.address)
            handshakeDone.set(true)
            // As at the radar's handshake: a live area only with an app screen
            // open, else the saved one (LocationCache).
            LocationCache.refreshIfStale(context)
            val ch14 = gatt.getService(Uuids.SVC_CONTROL)?.getCharacteristic(Uuids.SETTINGS_14)
            if (ch14 != null) queue.writeCccd(gatt, ch14)

            val lightSlugEarly = slug(name)
            val lightMacEarly = gatt.device?.address
            if (lightMacEarly != null) macToSlug[lightMacEarly] = lightSlugEarly
            haPublisher.offerFrontMode(ha, lightSlugEarly, name, null)

            val controller = CameraLightController(gatt, queue)
            val nowMs = wallClock()
            val today = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            // Manual coordinates (if set) -> the approximate area -> London. The
            // resolver re-validates every input, so a corrupt pref can't reach
            // the solar calc.
            val resolved = RideLocationResolver.resolve(
                prefs.manualLocationLat,
                prefs.manualLocationLon,
                LocationCache.current(),
            )
            val sunriseMs = SunsetCalculator.sunriseEpochMs(today, resolved.lat, resolved.lon)
            val sunsetMs = SunsetCalculator.sunsetEpochMs(today, resolved.lat, resolved.lon)
            val isNight = SunsetCalculator.isNight(nowMs, sunriseMs, sunsetMs)

            // Same time-of-day scheduling as the radar tail light, via the shared
            // pure LightAutoModeDecider: DAY -> cameraLightDayMode, NIGHT ->
            // cameraLightNightMode. A manual side-button override suppresses both
            // the initial apply and the scheduled flip; the flip is also
            // re-checked at fire time in case the override or link changed.
            val plan = LightAutoModeDecider.plan(nowMs, sunriseMs, sunsetMs, isNight, cameraLightUserOverride)
            // Provenance only, never the coordinates: this goes to release logcat.
            Log.i(TAG, "auto-mode: night=$isNight override=$cameraLightUserOverride location=${resolved.source}")

            fun linkGone() = linkDown.get() || endedByApp.get()

            // An answered read of the camera's battery: the link's proof of life.
            // False after the queue's own op timeout, if the read was not
            // submitted, if the link ended, or if the camera answered with an
            // error status. A camera with no battery characteristic never proves
            // itself, so its failure is never reported, deliberately
            // (`aCameraWithNoBatteryToReadNeverProvesItAnswers`). On a phone, after
            // a write the camera never answered, the platform's GATT client stays
            // busy until its own timeout, so this read is usually refused at submit
            // and nothing is reported; that leaves
            // `unansweredWritesAreReportedOnceTheCameraAnswersARead` covering a
            // late answer.
            suspend fun linkAnswers(): Boolean {
                val battery = gatt.getService(Uuids.SVC_BATTERY)?.getCharacteristic(Uuids.CHAR_BATTERY) ?: return false
                return queue.read(gatt, battery) != null
            }

            // The mode that failed and the landed-write count at its failure, or
            // null when the set landed.
            suspend fun setLight(phase: LightAutoModeDecider.Phase, label: String): Pair<CameraLightMode, Int>? {
                val mode = if (phase == LightAutoModeDecider.Phase.NIGHT) {
                    prefs.cameraLightNightMode
                } else {
                    prefs.cameraLightDayMode
                }
                val setting = Setting(mode)
                cameraLightSetting.set(setting)
                // A press of the light's button does not stop the remaining tries,
                // deliberately: a mode-state frame from a camera that has just come
                // up reads as a press, and stopping on it would leave the light in
                // the mode the camera started in
                // (`aPressDuringATryDoesNotStopTheRemainingTries`).
                val ok = try {
                    applyWithRetry { controller.setMode(mode) }.also { if (it) cameraLightLastWrittenMode = mode }
                } finally {
                    cameraLightSetting.compareAndSet(setting, null)
                }
                val landedAtFailure = lightWritesLanded
                Log.i(TAG, "$label mode=$mode applied=$ok")
                if (ok) {
                    haPublisher.offerFrontMode(ha, lightSlugEarly, name, mode.name)
                    lightWriteLanded()
                }
                return if (ok) null else mode to landedAtFailure
            }

            // Beside the notify loop below, so a write left unanswered cannot
            // hold the camera's notifications
            // (`anUnansweredLightSetDoesNotHoldTheCamerasNotifications`). Its
            // report is a job of its own, so the setup ends with its writes.
            val setup = jobs.launch {
                if (plan.initial != null) {
                    setLight(plan.initial, "initial")?.let { (mode, landed) ->
                        jobs.launch { reportFailedSet(mode, landed, ::linkGone, ::linkAnswers) }
                    }
                } else {
                    Log.i(TAG, "initial mode skipped (manual override active)")
                }
            }
            val flipAt = plan.flipAtMs
            val flipTo = plan.flipTo
            if (flipAt != null && flipTo != null) {
                jobs.launch {
                    kotlinx.coroutines.delay(flipAt - nowMs)
                    // After the initial set, which may still be retrying
                    // (`aFlipDueDuringTheInitialSetComesAfterIt`).
                    setup.join()
                    if (cameraLightGattActive && !cameraLightUserOverride) {
                        val (mode, landed) = setLight(
                            flipTo,
                            if (flipTo == LightAutoModeDecider.Phase.NIGHT) "sunset" else "sunrise",
                        ) ?: return@launch
                        reportFailedSet(mode, landed, ::linkGone, ::linkAnswers)
                    }
                }
            }

            for ((uuid, bytes) in notifyChannel) {
                when (uuid) {
                    Uuids.CHAR_BATTERY -> {
                        // The bus refuses 0xFF and the like, and then nothing is sent
                        // (`aBatteryByteThatIsNotAPercentageGoesNowhere`).
                        val pct = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                        if (!BatteryStateBus.update(BatteryEntry(lightSlugEarly, name, pct))) {
                            // Still seen; a camera with no earlier valid reading
                            // still reads as not seen (`aRefusedBatteryByteStillMarksTheCameraSeen`).
                            BatteryStateBus.markSeen(lightSlugEarly)
                        } else if (!prefs.isPaused) {
                            haPublisher.offerBattery(name, pct)
                        }
                    }
                    Uuids.SETTINGS_14 -> {
                        val mode = CameraLightController.parseModeStateNotify(bytes) ?: continue
                        if (BuildConfig.DEBUG) Log.d(TAG, "mode-state notify: $mode")
                        // The setting first: a set stores the landed mode and then
                        // clears the setting, so read in the other order a frame
                        // between the two reads sees the old landed mode and no
                        // setting, and takes the echo for a press. No test can
                        // reach it on a single-threaded dispatcher.
                        val setting = cameraLightSetting.get()?.mode
                        val expected = cameraLightLastWrittenMode
                        if (!cameraLightUserOverride &&
                            mode != setting &&
                            CameraLightOverrideDecider.isOverride(expected, mode)
                        ) {
                            cameraLightUserOverride = true
                            Log.i(TAG, "override detected: expected=$expected device=$mode")
                        }
                        haPublisher.offerFrontMode(ha, lightSlugEarly, name, mode.name)
                    }
                }
            }
            false
        } finally {
            // Whatever ended it, a callback after this is the app's close
            // (`aLinkEndedByItsScopeIsNoSwitchOff`).
            endedByApp.set(true)
            dashcamLink.markDashcamLinkDown()
            cameraLightGattActive = false
            if (cameraLightOffSinceMs == null) cameraLightOffSinceMs = clock()
            jobs.cancel()
            queueJob.cancel()
            queue.cancel()
            closeOnce()
        }
    }

    /** An ACKed light write is taken as the end of the fault: the entry leaves
     *  the shade and the next failure alerts in full
     *  (`aLandedSetTakesTheFailureOffTheShade`, `aFailureAfterALandedSetAlertsAgain`). */
    private fun lightWriteLanded() = synchronized(lightFailLock) {
        lightWritesLanded++
        lightFailAlerted = false
        notifications.cancelLightFail()
    }

    /**
     * Reports a failed set once the camera has answered a read of its battery
     * since the failure ([linkAnswers]). The queue reports a write the camera
     * refused and one it never answered alike, so every failure waits on that
     * read, which gives up at the queue's own op timeout: a camera out of
     * range but not yet flagged disconnected reports nothing
     * (`unansweredWritesAreReportedOnceTheCameraAnswersARead`,
     * `unansweredWritesAndAnUnansweredReadReportNothing`,
     * `aRefusedSetWhoseFollowingReadGoesUnansweredReportsNothing`). A link
     * flagged down or ended by the app, a press of the light's button, or a
     * write landed since the failure ends the report
     * (`aSetTheLinksEndFailedReportsNothingBeforeTheTeardownReachesIt`,
     * `aSwitchThatLandsDuringTheHoldStandsTheHeldReportDown`,
     * `aPressDuringTheSetLeavesTheAlertedEntryAsItWas`, and for a press that
     * lands before the read is answered
     * `aPressWhileTheProofReadIsPendingReportsNothing`). A camera with no
     * battery characteristic never answers the read, so reports nothing
     * (`aCameraWithNoBatteryToReadNeverProvesItAnswers`). With nothing
     * holding the tone it is posted alerting at once; otherwise silent, then
     * alerting with the tone once the audio is free and the same checks still
     * pass (`aFailureWithNothingInTheWayIsPostedOnceAndSoundsOnce`,
     * `aHeldFailureIsPostedSilentlyThenAgainWithItsTone`,
     * `aHeldFailToneNeverSoundsOnceTheLinkEnds`,
     * `aHoldEndingAsTheCameraDisconnectsPostsNothingMore`,
     * `aPressDuringTheHoldLeavesTheSilentPostOnly`). After an alert, until a
     * write lands or a new ride starts, it is posted silent only, at once
     * (`aRepeatFailureWhileACueSoundsIsPostedAtOnceAndNeverAgain`). No test
     * reaches a cancel landing just as the wait returns, which the isActive
     * check covers.
     */
    private suspend fun reportFailedSet(
        mode: CameraLightMode,
        landedAtFailure: Int,
        linkGone: () -> Boolean,
        linkAnswers: suspend () -> Boolean,
    ) {
        // A landed write is read where a post is made, under the lock.
        fun landedSince() = lightWritesLanded != landedAtFailure
        fun due() = !linkGone() && !cameraLightUserOverride
        if (!linkAnswers() || !due()) return
        if (!lightFailAlerted) {
            val quiet = lightFailToneQuiet(context, prefs, wallClock, cueSounding)
            if (!quiet.first()) {
                synchronized(lightFailLock) {
                    if (landedSince()) return
                    postLightModeFailNotification(mode, silent = true)
                }
                quiet.first { it }
            }
            if (!currentCoroutineContext().isActive || !due()) return
        }
        val alert = synchronized(lightFailLock) {
            if (landedSince()) return
            val first = !lightFailAlerted
            lightFailAlerted = true
            postLightModeFailNotification(mode, silent = !first)
            first
        }
        if (alert) playLightFailTone(TAG, "dashcam light")
    }

    private fun postLightModeFailNotification(mode: CameraLightMode, silent: Boolean) {
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

        notifications.postLightFail(modeName, silent)
    }

    companion object {
        private const val TAG = "BikeRadar.Light"

        // Override detection: a front link that has been down longer than this
        // clears the manual side-button override on the next connect.
        private const val CAMERA_LIGHT_OVERRIDE_DEADBAND_MS = 120_000L

        /** What one phone reported for one camera ending the link itself: 19,
         *  and 257 after an OS update (257 is also Android's GATT_FAILURE).
         *  Kept apart from the radar's `RadarLeftOnDecider.REMOTE_END_STATUSES`. */
        private val SWITCH_OFF_STATUSES = setOf(19, 257)

        /** A disconnect counted as the camera switched off: one of those
         *  statuses, with Bluetooth on, on a connection whose handshake
         *  completed and that the app did not end. A link still in setup can
         *  end the same way with the camera still on; any other status changes
         *  nothing (`onlyTheCamerasOwnEndingCountsAsASwitchOff`). */
        internal fun isSwitchOff(status: Int, adapterOn: Boolean, handshakeDone: Boolean, endedByApp: Boolean): Boolean = status in SWITCH_OFF_STATUSES && adapterOn && handshakeDone && !endedByApp
    }
}
