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
import kotlinx.coroutines.CancellationException
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
    /** Wall clock, separate from [clock] because the connection probe's stamp
     *  has to survive a reboot and be comparable with the other timestamps in
     *  a diagnostic bundle; elapsedRealtime is neither. Injected so a test can
     *  pin the stamp rather than race it. */
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    /** GATT opener seam, defaulting to the shared LE-transport [connectGattLe].
     *  Injected only by the Robolectric harness so a test can capture the
     *  connection's [BluetoothGattCallback] and drive the callbacks by hand;
     *  production keeps the real connect path unchanged. */
    private val openGatt: (Context, BluetoothDevice, Boolean, BluetoothGattCallback) -> BluetoothGatt? = ::connectGattLe,
    /** GATT-cache clear seam, defaulting to the reflection call. Injected so a
     *  test can drive BOTH outcomes, which no harness can otherwise reach:
     *  under Robolectric the hidden `refresh()` is absent, so the real call
     *  always returns false and every test would silently pin the
     *  refresh-failed branch - which is the branch that refuses the legacy
     *  fallback. Without this seam the fallback's own success path is
     *  unreachable from a test. */
    private val refreshGatt: (BluetoothGatt) -> Boolean = { g -> refreshGattCacheByReflection(g) },
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

    /**
     * The radar whose bond state we are watching, which is NOT the same
     * question as [currentRadarMac] ("which radar are we linked to right
     * now", read by the HA and overlay paths to attribute battery and MQTT).
     *
     * They were one field, and that deadlocked re-pairing: losing the bond
     * cleared the MAC, the receiver matches on the MAC, so the BOND_BONDED
     * branch that clears [bondLost] could never run, and [start] refuses
     * while [bondLost]. This one survives disconnect, so a re-pair on the
     * same address is still recognised from the broadcast.
     *
     * It is not sufficient on its own, and is not the gate's only lift: a
     * re-pair can land on a different address, and a sighting of a second
     * radar moves this field on. [start]'s adapter query covers both.
     */
    @Volatile private var bondWatchMac: String? = null

    // Latches the one-per-episode refusal journal line; cleared when the gate
    // lifts, so the next bond loss records again.
    @Volatile private var bondRefusalJournalled = false

    // Last time the V2 stream produced a frame (watchdog clock); 0 = none yet.
    @Volatile private var lastV2FrameMs: Long = 0L

    // Owns the change-debounce and the per-answer since= stamps; see
    // LinkProbeRecorder, which the front camera shares.
    private val linkProbe = LinkProbeRecorder(
        load = { prefs.radarLinkProbe },
        store = { prefs.radarLinkProbe = it },
        wallClock = { wallClock() },
    )

    // Wall clock of the FIRST sighting of each distinct answer. A single
    // last-answer slot is not enough: a marginal link that stops at one step on
    // one attempt and another on the next differs from the previous answer
    // every time, so it would rewrite every 1.5 s and restamp on every flip -
    // and a flapping link is exactly the case the stamp is meant to describe.

    // True once this reconnect loop has cleared the GATT cache SUCCESSFULLY,
    // so the next discovery's table came from the device rather than from
    // Android's per-device cache. The legacy fallback requires it. Loop-scoped
    // rather than per-attempt so a genuine legacy radar pays the extra cycle
    // once, and reset by runRadarConnection so a fresh link re-earns it.
    //
    // Named for the guarantee, not the attempt: a refresh that FAILED leaves
    // the table exactly as untrustworthy as before, and a flag called
    // "refreshed" invites the next reader to set it either way.
    @Volatile private var legacyTableVerified = false

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
            val expected = bondWatchMac ?: return
            if (!mac.equals(expected, ignoreCase = true)) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            when (state) {
                BluetoothDevice.BOND_NONE -> onRadarBondLost(mac)
                BluetoothDevice.BOND_BONDED -> liftBondGate(mac, via = "broadcast", name = device.name)
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
        Log.w(TAG, "radar bond removed (${LogRedaction.mac(mac)}); stopping reconnect loop")
        journal("radar bond removed; reconnect loop stopped")
        bondLost = true
        // Armed at the START of the episode, not only cleared at its end.
        // liftBondGate is not synchronized and runs on the main thread while
        // start() runs on IO, so a lift landing between start()'s read of
        // bondLost and its write of the latch would leave the latch set with
        // the gate clear - and the NEXT genuine bond loss would then journal
        // nothing at all. Clearing here makes the invariant local.
        bondRefusalJournalled = false
        radarJob?.cancel()
        radarJob = null
        linkState.markDisconnected()
        currentRadarMac = null
        notifications.postBondLost()
    }

    @Synchronized
    fun start(name: String, mac: String) {
        if (radarJob?.isActive == true) return
        // Ask the adapter, do not wait to be told. BOND_BONDED is an edge and
        // there are two ordinary ways to miss it: a re-pair can land on a
        // different address (the peer is free to change it), and a sighting of
        // another radar moves [bondWatchMac] on, after which no broadcast for
        // this one can match. Either way the flag would never lift and the link
        // would stay dead for the process. A device the adapter reports as
        // bonded is worth trying, whatever we did or did not hear.
        if (bondLost && deviceIsBonded(mac)) liftBondGate(mac, via = "adapter", name = name)
        bondWatchMac = mac
        if (bondLost) {
            Log.d(TAG, "skip radar link start: bond lost, waiting for re-pair")
            // Once per refusing episode, not once per sighting: the radar can
            // sit unpaired but advertising for days, and a line per re-acquire
            // would push the surrounding link history out of the journal.
            if (!bondRefusalJournalled) {
                journal("radar link start refused: bond lost")
                bondRefusalJournalled = true
            }
            return
        }
        Log.i(TAG, "starting radar link to $name ${LogRedaction.mac(mac)}")
        journal("radar link start $name")
        radarJob = scope.launch { runRadarConnection(mac, name) }
    }

    /**
     * Lift the bond-lost gate and retract the notification that asked the
     * rider to re-pair.
     *
     * One place, because the gate lifts from two: the BOND_BONDED broadcast
     * and [start]'s adapter query. [via] records which, since "the fallback
     * saved us" and "the normal path worked" are the informative distinction
     * when a reconnect problem is being read back off the journal.
     */
    private fun liftBondGate(mac: String, via: String, name: String?) {
        if (!bondLost) return
        Log.i(TAG, "radar re-paired (${LogRedaction.mac(mac)}, $via); allowing reconnect")
        journal("radar re-paired ($via) $name")
        bondLost = false
        bondRefusalJournalled = false
        // The shade still says "Re-pair in Bluetooth settings" otherwise, for
        // the rest of the process, while the link runs and alerts fire.
        notifications.cancelBondLost()
    }

    /** The adapter's own view of whether [mac] is bonded. False when the
     *  address is malformed or the adapter is unavailable, which keeps the
     *  reconnect loop from spinning against a peer that will refuse it.
     *  Only ever lifts the gate, never sets it, so a false negative preserves
     *  a refusal that already held rather than creating one. */
    private fun deviceIsBonded(mac: String): Boolean = try {
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        btMgr?.adapter?.getRemoteDevice(mac)?.bondState == BluetoothDevice.BOND_BONDED
    } catch (_: Throwable) {
        false
    }

    /** True while the connection coroutine is live. */
    fun isActive(): Boolean = radarJob?.isActive == true

    /** Cancel the whole connection coroutine (current attempt AND its
     *  reconnect loop); the cleanup path closes the GATT and marks the link
     *  disconnected. The next [start] - driven by a scan sighting or the
     *  known-device kickstart - opens a fresh loop. */
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
        legacyTableVerified = false
        var backoffMs = RADAR_RECONNECT_BACKOFF_INITIAL_MS
        try {
            while (true) {
                if (bondLost) {
                    Log.i(TAG, "bond lost for ${LogRedaction.mac(mac)}; reconnect loop suspended")
                    return
                }
                Log.i(TAG, "connect attempt to $name ${LogRedaction.mac(mac)}")
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
            // Transcript mode leaves the file open across attempts; the loop's
            // exit is where it finally closes. Safe no-op otherwise.
            captureLog.close()
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

        // Setup-transcript mode: open the capture file BEFORE the connect, so
        // the connection states, the discovered services and the handshake
        // script lines - dropped on the null writer otherwise - land in it.
        // open() is a no-op when a file is already open, so the aborting
        // attempts of a reconnect loop accumulate into one file rather than
        // spraying one per retry; the per-attempt close below is skipped to
        // match, and the loop's exit closes.
        val setupTranscript = prefs.setupTranscriptEnabled
        if (setupTranscript) captureLog.open()

        gatt = openGatt(context, device, true, cb)
        if (gatt == null) {
            captureLog.clog("# connectGatt returned null")
            journal("radar connectGatt returned null")
            // Record it too: this and the discovery failure below are the two
            // exits that reach no GATT table, and leaving the slot holding the
            // PREVIOUS attempt's answer is worse than saying nothing - a
            // bundle then names a stopping point this attempt never reached.
            recordPreDiscoveryExit(NO_GATT)
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
                recordPreDiscoveryExit(DISCOVERY_FAILED)
                return false
            }

            linkState.markConnected()
            Log.i(TAG, "connected, running handshake")
            journal("radar connected, running handshake")

            // Firmware revision arrives via the DIS read inside the unlock
            // sequence (before runHandshake returns). Persisted so the
            // Settings display and the capture-log line survive a session
            // whose read fails.
            var firmwareRev: String? = null
            val handshakeAbort = RadarUnlock.runHandshake(
                gatt,
                queue,
                notifyChannel,
                onFirmwareRevision = { rev ->
                    firmwareRev = rev
                    prefs.radarFirmwareRev = rev
                },
                // A radar whose handshake aborts never reaches the notify loop
                // that normally feeds this, and the one-shot reader stands
                // down while the link is live, so without this it reports no
                // battery at all for as long as it keeps failing.
                onBatteryPct = { pct ->
                    publishRadarBattery(gatt.device?.address, name, pct)
                },
            ) { msg ->
                captureLog.clog("# script: $msg")
            }
            recordLinkProbe(gatt, handshakeAbort ?: HANDSHAKE_OK)

            if (handshakeAbort != null) {
                val legacyChar = legacyStreamChar(gatt)
                if (legacyChar != null && !legacyTableVerified) {
                    // Do NOT trust a first-look table for this decision. Android
                    // serves discovery from a per-device cache that survives
                    // reboots for a bonded device, so a table can arrive
                    // GATT_SUCCESS yet incomplete - this file already carries
                    // two guards written for exactly that. A cached table
                    // missing only the V2 characteristic would pin a healthy
                    // radar out of its modern stream until it is power-cycled,
                    // which is the one outcome this fallback must never cause.
                    //
                    // So the flag is set from the REFRESH RESULT, not from
                    // having tried. refresh() is a hidden method reached by
                    // reflection and its own KDoc says a future Android could
                    // remove it; on that day every table is unverifiable, and
                    // the honest answer is that this device does not get the
                    // fallback rather than that every radar gets pinned. The
                    // rider degrades to the behaviour they had before the
                    // feature existed. Same handling the stale-cache guard in
                    // onServicesDiscovered already uses on the same call.
                    val refreshed = refreshGattCache(gatt)
                    legacyTableVerified = refreshed
                    captureLog.clog("# legacy candidate; refreshing gatt cache first, refresh=$refreshed")
                    journal(
                        if (refreshed) {
                            "radar legacy candidate; cache refresh=true"
                        } else {
                            "radar legacy fallback unavailable (cache refresh failed)"
                        },
                    )
                    gatt.disconnect()
                    return true
                }
                if (legacyChar != null) {
                    // Opened BEFORE the first clog, or with the setup
                    // transcript off the writer is still null and the legacy
                    // session's capture never says which stream it is or what
                    // the handshake aborted at - and that capture is the whole
                    // artefact a reporter sends back.
                    captureLog.open()
                    captureLog.clog("# handshake aborted at $handshakeAbort; no V2 char, trying the legacy stream")
                    journal("radar legacy stream attempt after $handshakeAbort")
                    // The probe keeps the ABORT token, not a legacy marker:
                    // the token is the diagnostic, and the journal line above
                    // is what records that the fallback ran.
                    overlayJob = overlayPipeline.attach(scope, name)
                    return runLegacyStream(gatt, queue, notifyChannel, legacyChar, name)
                }
                captureLog.clog("# handshake aborted - closing gatt for quick reconnect")
                journal("radar handshake aborted at $handshakeAbort (quick reconnect)")
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
            captureLog.clog(
                "# radar_fw rev=${firmwareRev ?: prefs.radarFirmwareRev ?: "unknown"}" +
                    " lateral_offset_cm=${prefs.radarLateralOffsetCm}",
            )
            val v2Dec = RadarV2Decoder(lateralOffsetCm = prefs.radarLateralOffsetCm)
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
                // Manual coordinates (if set) -> GPS last-known -> London. Log the
                // source only, never the coordinates (a manual location is the
                // rider's home).
                val resolved = RideLocationResolver.resolve(
                    prefs.manualLocationLat,
                    prefs.manualLocationLon,
                    LocationCache.current(),
                )
                val sunriseMs = SunsetCalculator.sunriseEpochMs(today, resolved.lat, resolved.lon)
                val sunsetMs = SunsetCalculator.sunsetEpochMs(today, resolved.lat, resolved.lon)
                Log.i(TAG, "radar light location source=${resolved.source}")
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
                        if (v2FrameCount++ == 0) {
                            // The frame itself is a movement payload, so it
                            // goes the way the handshake replies do. The line
                            // stays on release builds because it is the signal
                            // a live test waits for.
                            Log.i(TAG, if (BuildConfig.DEBUG) "first V2 frame: ${bytes.toHex()}" else "first V2 frame")
                        }
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
                        if (publishRadarBattery(rearMac, name, pct) && !prefs.isPaused) {
                            haPublisher.maybePublishBatteryToHa(name, pct)
                        }
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
            if (!setupTranscript) captureLog.close()
        }
    }

    /** One-shot battery read for the legacy path, best-effort. The standard
     *  service needs no bonding and no unlock on this family, and this is the
     *  only battery such a radar can report: it aborts before the sequence's
     *  own battery step, and the one-shot reader stands down while a link is
     *  live. Swallows failures because a missing battery service must not stop
     *  the radar streaming. */
    private suspend fun readLegacyBattery(gatt: BluetoothGatt, queue: BleOpQueue, name: String) {
        try {
            val ch = gatt.getService(Uuids.SVC_BATTERY)?.getCharacteristic(Uuids.CHAR_BATTERY) ?: return
            val pct = queue.read(gatt, ch)?.firstOrNull()?.toInt()?.and(0xFF) ?: return
            if (!publishRadarBattery(gatt.device?.address, name, pct)) return
            captureLog.clog("# legacy battery $pct%")
            queue.writeCccd(gatt, ch)
        } catch (e: CancellationException) {
            // Cooperative cancellation (link teardown) - structured concurrency
            // says re-throw so the launching scope sees it, rather than logging
            // a shutdown as a battery-read failure and running on inside a job
            // that is already dead.
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "legacy battery read failed: $t")
        }
    }

    /**
     * The legacy-stream characteristic, but ONLY on a radar that has no V2
     * characteristic to lose.
     *
     * That condition is the whole safety argument for the fallback, not a
     * tidy-up. Subscribing this CCCD pins a V2-capable radar into the legacy
     * stream, and the pin outlives the connection: every later reconnect gets
     * a silent V2 too, until the unit is power-cycled. Gating on the absence
     * of [Uuids.RADAR_V2] means a radar that HAS V2 can never reach the
     * subscribe, so the pin cannot be applied to a radar it would cost
     * anything. Read off the discovered GATT table rather than the stored
     * probe string, so it cannot drift from what this connection actually saw.
     *
     * Do not relax this to a retry count. A count fires on a healthy radar
     * after a transient handshake failure, which is exactly the case where
     * the pin is expensive. `RadarLinkControllerHarnessTest` pins both
     * directions.
     */
    private fun legacyStreamChar(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val svc = gatt.getService(Uuids.SVC_RADAR) ?: return null
        if (svc.getCharacteristic(Uuids.RADAR_V2) != null) return null
        return svc.getCharacteristic(Uuids.RADAR_V1)
    }

    /**
     * Decode the legacy stream until the link drops.
     *
     * Returns false so the reconnect loop treats this like an ordinary session
     * end rather than a quick post-abort retry.
     *
     * The stream carries range only. [RadarV1Decoder] documents what that
     * costs and `RadarV1SafetyTest` pins which cues stay closed; nothing here
     * should try to synthesise the missing channels.
     */
    private suspend fun runLegacyStream(
        gatt: BluetoothGatt,
        queue: BleOpQueue,
        notifyChannel: Channel<Pair<UUID, ByteArray>>,
        v1Char: BluetoothGattCharacteristic,
        name: String,
    ): Boolean {
        val subscribed = queue.writeCccd(gatt, v1Char)
        captureLog.clog("# legacy stream subscribe ok=$subscribed")
        journal("radar legacy stream subscribe ok=$subscribed")
        if (!subscribed) {
            gatt.disconnect()
            return false
        }

        // The legacy stream has no device-status frame, so the standard
        // battery service is the only reading available. One read here, on an
        // idle queue, rather than none at all: this hardware aborts before the
        // sequence's own battery step, so nothing else ever fetches one.
        readLegacyBattery(gatt, queue, name)

        // Same radio treatment as the V2 path: the radar pushes at its own
        // cadence, so a tighter connection interval only costs power.
        try {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        } catch (t: Throwable) {
            Log.w(TAG, "requestConnectionPriority threw: $t")
        }

        val dec = RadarV1Decoder()
        var frames = 0
        lastV2FrameMs = clock()

        // Same watchdog as the V2 path. EVERY payload counts as liveness,
        // including heartbeats, because a legacy radar with no traffic behind
        // it emits nothing but heartbeats and is perfectly healthy.
        val capturedGatt = gatt
        val watchdog = scope.launch {
            while (true) {
                delay(V2_WATCHDOG_TICK_MS)
                val now = clock()
                if (V2WatchdogDecider.isStale(now, lastV2FrameMs, V2_FRAME_STALL_MS)) {
                    Log.w(TAG, "legacy stream silent for ${now - lastV2FrameMs}ms; tearing down")
                    journal("radar legacy stream silent; tearing down")
                    try {
                        capturedGatt.disconnect()
                    } catch (_: Throwable) {}
                    return@launch
                }
            }
        }

        try {
            for ((uuid, bytes) in notifyChannel) {
                if (uuid == Uuids.CHAR_BATTERY) {
                    val pct = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                    if (publishRadarBattery(gatt.device?.address, name, pct) && !prefs.isPaused) {
                        haPublisher.maybePublishBatteryToHa(name, pct)
                    }
                    continue
                }
                if (uuid != Uuids.RADAR_V1) continue
                lastV2FrameMs = clock()
                if (frames++ == 0) {
                    Log.i(TAG, "first legacy frame")
                    journal("radar legacy stream live")
                    // Only now is this a session worth resetting the backoff
                    // for. A radar that ACKs the subscribe and then streams
                    // nothing is not delivering, and treating the ACK as
                    // success would pin the retry delay at its floor and
                    // churn the radio for the whole ride.
                    lastConnectionReachedDecode = true
                }
                // Published on EVERY payload, not only the ones that change the
                // track set. Heartbeats are the only traffic on an empty road,
                // and a consumer scoring liveness off the published timestamp
                // would otherwise call a healthy radar stale within seconds of
                // the road clearing.
                RadarStateBus.publish(dec.feed(bytes) ?: dec.currentState())
            }
        } finally {
            watchdog.cancel()
            // The one line that separates the ways this stream can fail on
            // hardware nobody here owns. Silent, heartbeat-only, unparsable,
            // and parsed-to-nothing all present identically from outside:
            // link up, no targets. Journalled unconditionally rather than to
            // the capture log, so a reporter who never finds that toggle
            // still sends something actionable. Stored too, because a
            // flapping link scrolls the journal's line cap.
            val tally = dec.sessionTally(frames)
            journal("radar legacy stream ended $tally")
            captureLog.clog("# legacy stream ended $tally")
            prefs.radarLegacyTally = tally
            dec.reset()
        }
        return false
    }

    /**
     * Persist what this attempt found and where it stopped, for the
     * diagnostic bundle.
     *
     * The debounce, the per-answer `since=` stamps and their eviction all live
     * in [LinkProbeRecorder], which the front camera shares.
     *
     * [HANDSHAKE_OK] means the sequence completed, NOT that targets will
     * stream: the battery read and subscribe inside the handshake ignore their
     * own results, and that step is what keeps the radar off its legacy
     * stream. The discovered table beside it is what shows a missing battery
     * service, so record the table even on success.
     *
     * The two exits before service discovery reach [recordPreDiscoveryExit].
     */
    /**
     * Record an attempt that stopped BEFORE there was a service table to
     * describe.
     *
     * The table is the useful half of a probe, and these two exits have none:
     * one never opened a GATT, the other never completed discovery. They are
     * recorded anyway because the alternative is silence, and silence here is
     * not neutral - the slot keeps the previous attempt's answer, so a bundle
     * reports a stopping point that this attempt never reached. An outcome
     * with no table says plainly how far it got.
     *
     * It shares the debounce with [recordLinkProbe], so a radar failing this
     * way every 1.5 s still writes once and keeps its `since=`.
     */
    private fun recordPreDiscoveryExit(outcome: String) {
        linkProbe.record(LinkProbe.format(emptyList(), outcome))
    }

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
            // Swallowed on purpose: this is a diagnostic, and the connection
            // coroutine rides a scope with no exception handler, so an escaping
            // throw would take the process down over a bug report field.
            Log.w(TAG, "link probe failed: $t")
            return
        }
        linkProbe.record(body)
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
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean = refreshGatt(gatt)

    /**
     * Validate one radar battery reading and put it on the battery bus.
     * Returns false for a reading that is not a percentage, in which case
     * nothing was published and the caller must not forward it either.
     *
     * One helper rather than the same four lines at four call sites, because
     * they had already drifted: only the legacy read range-checked the byte,
     * so a 0xFF "unknown" reply from either notify path reached the rider's
     * chip as 255%. A validation every caller must remember is a validation
     * that gets forgotten, so it lives here.
     *
     * Home Assistant publishing stays at the call sites: it suspends, and one
     * caller is a plain lambda inside the handshake. The return value is what
     * keeps the two in step - an unpublishable reading must not reach HA
     * either, which is the half the old copies got wrong.
     *
     * [mac] is nullable because one caller reads it off a GATT whose device
     * can be absent; a missing MAC costs the slug mapping, not the reading.
     */
    private fun publishRadarBattery(mac: String?, name: String, pct: Int): Boolean {
        if (pct !in 0..100) return false
        val s = slug(name)
        if (mac != null) macToSlug[mac] = s
        BatteryStateBus.update(BatteryEntry(s, name, pct))
        return true
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
     * whether NN selects a cycle-slot or a stable mode-type). The write lands
     * on the live radar connection if one is up. Its only entry point is the
     * dev-only [RemoteControlReceiver] with the probe toggle on, which is not
     * a supported route, so in practice reach the same write through
     * [probeWriteRadarRaw] from the Debug screen's raw-hex field. Not a
     * shipping path - the production controller will be derived once the
     * encoding is pinned.
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

    /** Write a tail-light mode on the live radar connection, if one is open.
     *  Consumed by the IPC service for remote light control. */
    fun setTailLightMode(mode: RadarLightMode) {
        val gatt = liveRadarGatt
        if (gatt == null) {
            Log.i(TAG, "radar_light set ${mode.name} skipped (no live gatt)")
            return
        }
        val queue = liveRadarQueue
        if (queue == null) {
            Log.i(TAG, "radar_light set ${mode.name} skipped (no live queue)")
            return
        }
        scope.launch {
            val ok = runCatching { RadarLightController(gatt, queue).setMode(mode) }.getOrDefault(false)
            Log.i(TAG, "radar_light set ${mode.name} ok=$ok")
        }
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

        /** Probe outcome for a handshake that ran to the end. Says the sequence
         *  completed, not that the radar will stream - see [recordLinkProbe]. */
        const val HANDSHAKE_OK = "handshake-ok"

        /** Stopped before a GATT existed. */
        const val NO_GATT = "no-gatt"

        /** A GATT opened, service discovery did not complete. */
        const val DISCOVERY_FAILED = "discovery-failed"

        private const val RADAR_LIGHT_OVERRIDE_DEADBAND_MS = 120_000L
    }
}

/**
 * Clears Android's cached GATT database for a device via the hidden
 * `BluetoothGatt.refresh()`.
 *
 * Known Android workaround for a stale cache after a firmware-side service
 * change; widely used in OSS BLE projects. Android caches the remote GATT
 * database between connections, so if the peer's services changed since the
 * cache was populated, discovery returns the stale list.
 *
 * The method is `@hide` and a future Android release could remove it, which is
 * why this returns a Boolean rather than throwing: callers must decide what an
 * unverifiable table means for them, and both callers in this file refuse to
 * act on one rather than proceeding.
 */
private fun refreshGattCacheByReflection(gatt: BluetoothGatt): Boolean = try {
    val method = BluetoothGatt::class.java.getMethod("refresh")
    method.invoke(gatt) as? Boolean ?: false
} catch (t: Throwable) {
    Log.w("BikeRadar.Radar", "BluetoothGatt.refresh() unavailable: $t")
    false
}
