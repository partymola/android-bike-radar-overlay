// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.os.SystemClock
import android.util.Log
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.data.PrefsSnapshot
import es.jjrh.bikeradar.ipc.RadarOverlayGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Extracted overlay/alert pipeline. Owns the per-frame loop that consumes
 * [RadarStateBus] + [BatteryStateBus] + a tick flow and drives:
 *  - the on-screen [RadarOverlayView] (attach / detach / setState / battery-
 *    low badging / dashcam-status badging),
 *  - the [AlertBeeper] audio cues (proximity, urgent, clear),
 *  - close-pass detection (state-machine emission + HA event publish +
 *    [ClosePassStateBus] count + [RideStatsAccumulator] tally),
 *  - the per-ride dashcam status derivation,
 *  - the per-ride phone-battery sample line in the capture log.
 *
 * Side-effect boundaries ([OverlayHost], [PhoneBatterySource]) are injected
 * so the class is JVM-constructible without an Android Context - real callers
 * pass production wrappers, tests pass stubs.
 *
 * The class is service-scoped (allocated in [BikeRadarService.onCreate]) so
 * its cross-connection state (the phone-battery sample throttle) survives a
 * radar reconnect. Per-connection
 * state (overlay attach flag, decider instances, session-start timestamp,
 * dashcam-seen flag, close-pass discovery flags) is allocated fresh inside
 * each [attach] call and torn down when the returned [Job] cancels.
 *
 * Inputs that change at runtime (e.g. `cachedOverlayPrefs`, the eBike
 * snapshot) are provided as zero-arg sources so the pipeline always sees the
 * latest value without bouncing through a re-subscription.
 */
internal class OverlayPipeline(
    private val prefs: Prefs,
    /** Provider, not an instance: the service rebuilds its HaClient when
     *  the stored credentials change, and a captured instance would keep
     *  publishing with the stale token until restart. The lambda deref is
     *  free on the per-frame `isConfigured` check (no decryption - the
     *  service hands out an already-built client). */
    private val ha: () -> HaClient,
    private val beeper: AlertBeeper,
    private val overlayHost: OverlayHost,
    private val phoneBattery: PhoneBatterySource,
    private val rideStats: () -> RideStatsAccumulator,
    private val overlayPrefsSnapshot: () -> PrefsSnapshot,
    private val ebikeSnapshot: () -> LiveDataSnapshot?,
    private val climbingNow: () -> Boolean,
    /** Turn-aware alerting: the rider's current turn state
     *  ([TurnSensorController]) - TURNING defers the all-clear, HOLD
     *  anchors the adaptive post-turn tail. Consulted per frame;
     *  additionally gated on the Settings flag so a mid-ride toggle-off
     *  takes effect immediately. */
    private val turnState: () -> TurnStateDecider.State = { TurnStateDecider.State.IDLE },
    /** Start/stop hooks for the turn sensor, tied to the overlay session
     *  so the gyroscope only runs while a ride is live and the flag is
     *  on. Injected as lambdas to keep the pipeline JVM-constructible. */
    private val turnSensorStart: () -> Unit = {},
    private val turnSensorStop: () -> Unit = {},
    private val currentRadarMac: () -> String?,
    private val macToSlug: () -> Map<String, String>,
    private val clog: (String) -> Unit,
    /** Monotonic clock (elapsedRealtime) for the in-ride cue cadence -
     *  AlertDecider's urgent-repeat gap - so a wall-clock jump can't stall or
     *  early-fire a safety cue.
     *  Wall time (the per-frame `now`) is kept for the cosmetic battery /
     *  dashcam freshness gates, the capture-log lines, and the close-pass emit
     *  cooldown (whose `now` is also the emitted unix-epoch event timestamp). */
    private val clockMono: () -> Long = { SystemClock.elapsedRealtime() },
    /** Dispatcher for the HA publish calls, which do blocking network I/O
     *  off the collect loop. Injectable so a test can put them on its own
     *  scheduler: under `runTest` a real dispatcher runs on wall-clock
     *  threads while the test's `delay` advances virtual time instantly, so
     *  a test waiting on a publish can time out before the publish has had
     *  any real time to run. Typed as a dispatcher, not a bare
     *  [kotlin.coroutines.CoroutineContext]: a context carrying no dispatcher
     *  would inherit `Dispatchers.Main` from the enclosing collect loop and
     *  put blocking network I/O on the main thread with nothing to catch it. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // Cross-connection state. Sampling cadences must NOT reset on every
    // reconnect or a flaky link would replay the cues each time.
    @Volatile private var lastPhoneBatteryLogMs: Long = 0L

    /**
     * Start the pipeline. Returns the [Job] driving the collect loop; the
     * caller cancels it on radar disconnect. Caller is responsible for
     * passing the [CoroutineScope] (service scope) and the BLE-advertised
     * `deviceName` (used as the HA close-pass entity name + diagnostic
     * suffix).
     */
    fun attach(scope: CoroutineScope, deviceName: String): Job {
        return scope.launch(Dispatchers.Main) {
            var overlayAdded = false
            val view = overlayHost.createView()
            beeper.setVolumePct(prefs.alertVolume)
            val alerts = AlertDecider(
                onTurnDefer = { tailMs -> clog("# turn clear-defer tail_ms=$tailMs") },
                onGateEvent = clog,
            )
            val closePassDetector = ClosePassDetector()
            if (prefs.turnAwareAlertsEnabled) turnSensorStart()
            var closePassDiscoveryPublished = false
            var closePassDiscoveryInFlight = false
            val sessionStartMs = System.currentTimeMillis()
            var seenDashcamThisSession = false
            var lastLoggedDashcamStatus: DashcamStatus? = null
            val ticker = flow {
                while (true) {
                    emit(Unit)
                    delay(BikeRadarService.DASHCAM_TICK_MS)
                }
            }
            try {
                combine(RadarStateBus.state, BatteryStateBus.entries, ticker) { s, b, _ -> s to b }
                    .collect { (state, batteries) ->
                        // Wall clock for the cosmetic freshness gates and the
                        // capture-log lines; monotonic for the in-ride cue
                        // cadences so a wall-clock jump can't stall/early-fire them.
                        val now = System.currentTimeMillis()
                        val nowMono = clockMono()
                        val overlayPrefs = overlayPrefsSnapshot()

                        maybeLogPhoneBattery(now)
                        val dashcamSlug = resolveDashcamSlug()
                        val dashcamEntry = dashcamSlug?.let { batteries[it] }
                        if (dashcamEntry != null) seenDashcamThisSession = true
                        val status = DashcamStatusDeriver.derive(
                            config = DashcamStatusDeriver.Config(
                                warnWhenOff = prefs.dashcamWarnWhenOff,
                                selectedSlug = dashcamSlug,
                            ),
                            entries = batteries,
                            nowMs = now,
                            sessionStartMs = sessionStartMs,
                            seenThisSession = seenDashcamThisSession,
                            freshMs = BikeRadarService.DASHCAM_FRESH_MS,
                            coldStartMs = BikeRadarService.DASHCAM_COLD_START_MS,
                        )
                        if (status != lastLoggedDashcamStatus) {
                            Log.i(
                                TAG,
                                // No MAC: this is logcat, which a release build
                                // also writes and `adb bugreport` collects, so
                                // it leaves the device. The slug identifies the
                                // same device for anyone reading their own log,
                                // and KnownDevices maps it back on this phone.
                                "dashcam status=$status " +
                                    "warn=${prefs.dashcamWarnWhenOff} " +
                                    "slug=${dashcamSlug ?: "-"} " +
                                    "entries=${batteries.size} " +
                                    "seen=$seenDashcamThisSession " +
                                    "ageMs=${dashcamEntry?.let { now - it.readAtMs } ?: -1L} " +
                                    "sessionAgeMs=${now - sessionStartMs}",
                            )
                            lastLoggedDashcamStatus = status
                        }
                        view.setDashcamStatus(status, dashcamSlug)

                        if (state.source == DataSource.NONE) return@collect

                        rideStats().observeFrame(state)

                        // A granted app drawing its own map can ask for ours to
                        // get out of the way. Checked every frame rather than
                        // latched, so the hold being dropped - by an unbind, a
                        // crash, or the rider revoking - puts the overlay back
                        // without anything having to notice.
                        val hiddenForConsumer = RadarOverlayGate.hidden
                        if (hiddenForConsumer && overlayAdded) {
                            overlayHost.detach(view)
                            overlayAdded = false
                            clog("# overlay hidden for a granted app")
                        }

                        if (!overlayAdded && !hiddenForConsumer) {
                            if (overlayHost.canDrawOverlays()) {
                                val attachErr = overlayHost.attach(view)
                                if (attachErr == null) {
                                    overlayAdded = true
                                    clog("# overlay added")
                                } else {
                                    clog("# overlay addView failed: $attachErr")
                                }
                            } else {
                                clog("# overlay: SYSTEM_ALERT_WINDOW not granted")
                            }
                        }

                        view.setVisualMaxM(overlayPrefs.visualMaxDistanceM)
                        view.alpha = overlayPrefs.overlayOpacity
                        view.setAlertMaxM(overlayPrefs.alertMaxDistanceM)
                        view.setAdaptiveAlerts(overlayPrefs.adaptiveAlertsEnabled)
                        view.setPrecog(overlayPrefs.precogEnabled)
                        view.setState(state)

                        val lowSlugs = lowBatterySlugs(
                            entries = batteries.values,
                            lowThresholdPct = prefs.batteryLowThresholdPct,
                            nowMs = now,
                            staleAfterMs = BikeRadarService.BATTERY_STALE_MS,
                        )
                        view.setBatteryLow(lowSlugs, prefs.batteryShowLabels)

                        if (!prefs.isPaused) {
                            fireAlertCue(state, alerts, overlayPrefs, nowMono, now)
                        } else {
                            alerts.reset()
                        }

                        // Close-pass detection runs whenever the user enabled
                        // it - the count card, ride stats, and ride history
                        // are local features. HA is an optional egress: the
                        // discovery + event publishes below additionally
                        // require ha().isConfigured(). Discovery is published
                        // lazily once HA is configured + a radar slug is
                        // known; the in-flight guard suppresses re-issue
                        // while the publish is pending.
                        val cpCfg = ClosePassDetector.Config(
                            enabled = prefs.closePassLoggingEnabled,
                            riderSpeedFloorMs = prefs.closePassRiderSpeedFloorMs,
                            closingSpeedFloorMs = prefs.closePassClosingSpeedFloorMs.toFloat(),
                            emitMinRangeXM = prefs.closePassEmitMinRangeXM,
                        )
                        val table = macToSlug()
                        val radarMac = currentRadarMac()
                        val radarSlug = radarMac?.let { table[it] ?: table[it.uppercase(Locale.ROOT)] }
                        if (cpCfg.enabled && ha().isConfigured() && !closePassDiscoveryPublished && !closePassDiscoveryInFlight && radarSlug != null) {
                            closePassDiscoveryInFlight = true
                            launch(ioDispatcher) {
                                val client = ha()
                                val ok = client.publishClosePassDiscovery(radarSlug, deviceName)
                                if (ok) {
                                    closePassDiscoveryPublished = true
                                } else {
                                    Log.w(TAG, "close-pass discovery publish failed; will retry")
                                    HaHealthBus.reportError(
                                        HaFamily.CLOSE_PASS,
                                        "close-pass discovery failed",
                                        client.lastFailure ?: HaFailure.UNKNOWN,
                                    )
                                }
                                closePassDiscoveryInFlight = false
                            }
                        }
                        // Wall, not nowMono: the detector's `now` doubles as the
                        // emitted Event.timestampMs, which is rendered as a unix
                        // epoch (HA event ts, ride-summary tightest_pass.ts). Its
                        // 2 s emit-dedup cooldown is the only duration use and a
                        // wall jump there is a negligible single-event edge.
                        val cpEvents = closePassDetector.decide(
                            state.vehicles,
                            state.bikeSpeedMs,
                            now,
                            cpCfg,
                        )
                        if (cpEvents.isNotEmpty()) {
                            ClosePassStateBus.increment(cpEvents.size)
                            for (ev in cpEvents) rideStats().observeClosePass(ev)
                            if (radarSlug != null && ha().isConfigured()) {
                                launch(ioDispatcher) {
                                    for (ev in cpEvents) {
                                        // Hold the instance: ha() returns the
                                        // service's shared client, and a
                                        // credential save mid-batch swaps that
                                        // field. Publishing and reading the
                                        // failure off the same object is what
                                        // keeps the cause attributable.
                                        val client = ha()
                                        val ok = client.publishClosePassEvent(radarSlug, closePassJson(ev))
                                        if (ok) {
                                            HaHealthBus.reportOk(HaFamily.CLOSE_PASS)
                                        } else {
                                            Log.w(TAG, "close-pass publish failed")
                                            HaHealthBus.reportError(
                                                HaFamily.CLOSE_PASS,
                                                "close-pass publish failed",
                                                client.lastFailure ?: HaFailure.UNKNOWN,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            } finally {
                turnSensorStop()
                if (overlayAdded) {
                    overlayHost.detach(view)
                    clog("# overlay removed")
                }
            }
        }
    }

    /** [nowWallMs] is wall clock: it is stamped into the capture line as a
     *  unix epoch, so the base is part of the contract, not an implementation
     *  detail of the throttle it also drives. */
    private fun maybeLogPhoneBattery(nowWallMs: Long) {
        if (nowWallMs - lastPhoneBatteryLogMs < BikeRadarService.PHONE_BATTERY_LOG_PERIOD_MS) return
        val reading = phoneBattery.readSnapshot() ?: return
        clog(
            BikeRadarService.formatPhoneBatteryLog(
                unixMs = nowWallMs,
                level = reading.level,
                scale = reading.scale,
                tempDc = reading.tempDc,
                plugged = reading.plugged,
            ),
        )
        lastPhoneBatteryLogMs = nowWallMs
    }

    private fun resolveDashcamSlug(): String? = prefs.dashcamMac?.let { mac ->
        val table = macToSlug()
        table[mac]
            ?: table[mac.uppercase(Locale.ROOT)]
            ?: prefs.dashcamDisplayName?.let { BikeRadarService.slug(it) }
    }

    private fun fireAlertCue(
        state: RadarState,
        alerts: AlertDecider,
        overlayPrefs: PrefsSnapshot,
        nowMonoMs: Long,
        nowWallMs: Long,
    ) {
        val snap = ebikeSnapshot()
        val preferredBikeSpeedMs = snap?.speedRaw?.let { it / 360f } ?: state.bikeSpeedMs
        val ev = alerts.decide(
            vehicles = state.vehicles,
            alertMaxM = overlayPrefs.alertMaxDistanceM,
            nowMs = nowMonoMs,
            bikeSpeedMs = preferredBikeSpeedMs,
            bikeNotDriving = snap?.bikeNotDriving,
            climbing = climbingNow(),
            urgentLowSpeedEnabled = overlayPrefs.urgentLowSpeedEnabled,
            turnState = if (overlayPrefs.turnAwareAlertsEnabled) {
                turnState()
            } else {
                TurnStateDecider.State.IDLE
            },
            passClearanceM = overlayPrefs.urgentPassClearanceM,
        )
        if (ev !is AlertDecider.Event.None) {
            logAlertEvent(
                ev = ev,
                state = state,
                nowWallMs = nowWallMs,
                gateBikeSpeedMs = preferredBikeSpeedMs,
                gatePassClearanceM = overlayPrefs.urgentPassClearanceM,
                gateAlertMaxM = overlayPrefs.alertMaxDistanceM,
                alerts = alerts,
            )
        }
        when (val cue = AlertCue.forEvent(ev)) {
            is AlertCue.Beep -> beeper.play(cue.count)
            AlertCue.Clear -> beeper.playClear()
            AlertCue.Urgent -> beeper.playUrgent()
            AlertCue.Silence -> {}
        }
    }

    private fun logAlertEvent(
        ev: AlertDecider.Event,
        state: RadarState,
        /** Wall clock. `# alert ts=` is a unix-epoch field, and callers
         *  hold both bases, so a base-neutral name here would be one
         *  positional slip away from stamping the line with
         *  elapsedRealtime. The sole caller passes every argument by name,
         *  which is what rules the slip out; nothing downstream checks the
         *  base of this field. */
        nowWallMs: Long,
        gateBikeSpeedMs: Float?,
        gatePassClearanceM: Float,
        gateAlertMaxM: Int,
        alerts: AlertDecider,
    ) {
        val evStr = when (ev) {
            is AlertDecider.Event.Beep -> "Beep(${ev.count})"
            AlertDecider.Event.Clear -> "Clear"
            is AlertDecider.Event.UrgentApproach -> "UrgentApproach"
            AlertDecider.Event.None -> "None"
        }
        // urgent_path attributes each urgent fire to the gate that opened it
        // (low-speed moving extension vs stationary path) so post-ride
        // threshold tuning can count moving fires directly. gate_speed_mps
        // is the speed decide() actually gated on (eBike wheel speed when
        // bonded), which can differ from the radar's bike_speed_mps.
        // gate_clearance_m is the rider's pass margin as decide() was called
        // with it, so a cue reported as spurious can be told apart from one
        // the rider widened the margin into. The trigger_* fields are the
        // vehicle that opened the urgent gate -
        // frame_closest_* below is just the nearest car, often a different,
        // slower one, so without these an urgent cannot be audited from the
        // capture log.
        val urgentPath = (ev as? AlertDecider.Event.UrgentApproach)?.let {
            " urgent_path=${if (it.viaMovingPath) "moving" else "stationary"}" +
                " gate_clearance_m=$gatePassClearanceM" +
                " trigger_tid=${it.triggerTid} trigger_d=${it.triggerDistanceM}" +
                " trigger_closing_mps=${it.triggerClosingMs} trigger_rx=${it.triggerRangeXm}"
        } ?: ""
        // A beep's tier comes from the closest track by TRUE range, which is
        // not always the nearest by along-axis distance - and where the two
        // disagree is exactly the off-axis case worth reviewing. frame_closest_*
        // below stays as it is (nearest car, whatever the tier said), so
        // without these a tier decision cannot be audited from the log.
        val tierTrigger = (ev as? AlertDecider.Event.Beep)?.let {
            alerts.lastTierTrigger?.let { v ->
                " tier_tid=${v.id} tier_d=${v.distanceM} tier_rx=${v.rangeXm}" +
                    " tier_true_d=${alerts.lastTierDistanceM}"
            }
        } ?: ""
        // The envelope decide() gated on, not a fresh read: a mid-ride change
        // would otherwise name a different "closest car" here than the
        // decider actually saw, on the same line.
        val closest = state.vehicles
            .filter { !it.isBehind && !it.isAlongsideStationary && it.distanceM in 0..gateAlertMaxM }
            .minByOrNull { it.distanceM }
        clog(
            "# alert ts=$nowWallMs event=$evStr " +
                "frame_closest_tid=${closest?.id ?: -1} " +
                "frame_closest_d=${closest?.distanceM ?: -1} " +
                "closing_mps=${closest?.let { -it.speedMs } ?: -1f} " +
                "bike_speed_mps=${state.bikeSpeedMs ?: -1f} " +
                "gate_speed_mps=${gateBikeSpeedMs ?: -1f}$urgentPath$tierTrigger",
        )
    }

    companion object {
        private const val TAG = "BikeRadar.Overlay"

        internal fun closePassJson(ev: ClosePassDetector.Event): JSONObject = JSONObject()
            .put("ts", java.time.Instant.ofEpochMilli(ev.timestampMs).toString())
            .put("min_range_x_m", String.format(Locale.US, "%.2f", ev.minRangeXM).toFloat())
            .put("side", ev.side.name.lowercase(Locale.ROOT))
            .put("range_y_at_min_m", String.format(Locale.US, "%.1f", ev.rangeYAtMinM).toFloat())
            .put("closing_speed_kmh", ev.closingSpeedKmh)
            .put("rider_speed_kmh", ev.riderSpeedKmh)
            .put("vehicle_size", ev.vehicleSize.name)
            .put("threshold_m", ev.thresholdArmedM)
            .put("severity", ev.severity.name.lowercase(Locale.ROOT))
    }
}
