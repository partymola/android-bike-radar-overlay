// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.data.PrefsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracted overlay/alert pipeline. Owns the per-frame loop that consumes
 * [RadarStateBus] + [BatteryStateBus] + a tick flow and drives:
 *  - the on-screen [RadarOverlayView] (attach / detach / setState / battery-
 *    low badging / dashcam-status badging),
 *  - the [AlertBeeper] audio cues (proximity, urgent, clear, critical battery,
 *    preflight low battery),
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
 * its cross-connection state (phone-battery sample throttle, critical /
 * preflight battery cue cadence) survives a radar reconnect. Per-connection
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
    private val currentRadarMac: () -> String?,
    private val macToSlug: () -> Map<String, String>,
    private val clog: (String) -> Unit,
) {

    // Cross-connection state. Sampling cadences must NOT reset on every
    // reconnect or a flaky link would replay the cues each time.
    @Volatile private var lastPhoneBatteryLogMs: Long = 0L

    @Volatile private var lastCriticalBatteryCueMs: Long? = null
    private val preflightBatteryCueMs = ConcurrentHashMap<String, Long>()

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
            beeper.setPanning(
                enabled = prefs.experimentalLateralPanning,
                invertLR = prefs.experimentalLateralPanningInvertLR,
            )
            val alerts = AlertDecider()
            val closePassDetector = ClosePassDetector()
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
                        val now = System.currentTimeMillis()
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
                                "dashcam status=$status " +
                                    "warn=${prefs.dashcamWarnWhenOff} " +
                                    "mac=${prefs.dashcamMac ?: "-"} slug=${dashcamSlug ?: "-"} " +
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

                        if (!overlayAdded) {
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

                        val threshold = prefs.batteryLowThresholdPct
                        val lowSlugs = batteries.values
                            .filter { it.pct < threshold && now - it.readAtMs < BikeRadarService.BATTERY_STALE_MS }
                            .map { it.slug }.toSet()
                        view.setBatteryLow(lowSlugs, prefs.batteryShowLabels)

                        if (!prefs.isPaused) {
                            maybeFireBatteryCues(batteries, now, threshold)
                            fireAlertCue(state, alerts, overlayPrefs, now)
                        } else {
                            alerts.reset()
                        }

                        // Close-pass discovery is published lazily once HA is
                        // configured + a radar slug is known. The in-flight
                        // guard suppresses re-issue while the publish is
                        // pending.
                        val cpCfg = ClosePassDetector.Config(
                            enabled = prefs.closePassLoggingEnabled && ha().isConfigured(),
                            riderSpeedFloorMs = prefs.closePassRiderSpeedFloorMs,
                            closingSpeedFloorMs = prefs.closePassClosingSpeedFloorMs.toFloat(),
                            emitMinRangeXM = prefs.closePassEmitMinRangeXM,
                        )
                        val table = macToSlug()
                        val radarMac = currentRadarMac()
                        val radarSlug = radarMac?.let { table[it] ?: table[it.uppercase(Locale.ROOT)] }
                        if (cpCfg.enabled && !closePassDiscoveryPublished && !closePassDiscoveryInFlight && radarSlug != null) {
                            closePassDiscoveryInFlight = true
                            launch(Dispatchers.IO) {
                                val ok = ha().publishClosePassDiscovery(radarSlug, deviceName)
                                if (ok) {
                                    closePassDiscoveryPublished = true
                                } else {
                                    Log.w(TAG, "close-pass discovery publish failed; will retry")
                                }
                                closePassDiscoveryInFlight = false
                            }
                        }
                        val cpEvents = closePassDetector.decide(
                            state.vehicles,
                            state.bikeSpeedMs,
                            now,
                            cpCfg,
                        )
                        if (cpEvents.isNotEmpty()) {
                            ClosePassStateBus.increment(cpEvents.size)
                            for (ev in cpEvents) rideStats().observeClosePass(ev)
                            if (radarSlug != null) {
                                launch(Dispatchers.IO) {
                                    for (ev in cpEvents) {
                                        val ok = ha().publishClosePassEvent(radarSlug, closePassJson(ev))
                                        if (!ok) Log.w(TAG, "close-pass publish failed")
                                    }
                                }
                            }
                        }
                    }
            } finally {
                if (overlayAdded) {
                    overlayHost.detach(view)
                    clog("# overlay removed")
                }
            }
        }
    }

    private fun maybeLogPhoneBattery(nowMs: Long) {
        if (nowMs - lastPhoneBatteryLogMs < BikeRadarService.PHONE_BATTERY_LOG_PERIOD_MS) return
        val reading = phoneBattery.readSnapshot() ?: return
        clog(
            BikeRadarService.formatPhoneBatteryLog(
                unixMs = nowMs,
                level = reading.level,
                scale = reading.scale,
                tempDc = reading.tempDc,
                plugged = reading.plugged,
            ),
        )
        lastPhoneBatteryLogMs = nowMs
    }

    private fun resolveDashcamSlug(): String? = prefs.dashcamMac?.let { mac ->
        val table = macToSlug()
        table[mac]
            ?: table[mac.uppercase(Locale.ROOT)]
            ?: prefs.dashcamDisplayName?.let { BikeRadarService.slug(it) }
    }

    private fun maybeFireBatteryCues(
        batteries: Map<String, BatteryEntry>,
        nowMs: Long,
        lowThresholdPct: Int,
    ) {
        val table = macToSlug()
        val critMac = currentRadarMac()
        val critSlug = critMac?.let { table[it] ?: table[it.uppercase(Locale.ROOT)] }
        val critBatt = critSlug?.let { batteries[it] }
        val critFresh = critBatt != null && nowMs - critBatt.readAtMs < BikeRadarService.BATTERY_STALE_MS
        val critDecision = CriticalBatteryDecider.decide(
            pct = critBatt?.pct,
            fresh = critFresh,
            nowMs = nowMs,
            criticalPct = BikeRadarService.CRITICAL_BATTERY_PCT,
            cadenceMs = BikeRadarService.CRITICAL_BATTERY_CUE_INTERVAL_MS,
            lastCueMs = lastCriticalBatteryCueMs,
        )
        lastCriticalBatteryCueMs = critDecision.lastCueMs
        if (critDecision.fire) {
            beeper.playCriticalBattery()
            clog("# critical_battery radar=$critSlug pct=${critBatt?.pct}")
        }
        for (batt in batteries.values) {
            if (!CriticalBatteryDecider.preflightEligible(
                    batt.slug,
                    batt.pct,
                    critSlug,
                    BikeRadarService.CRITICAL_BATTERY_PCT,
                )
            ) {
                continue
            }
            val pfFresh = nowMs - batt.readAtMs < BikeRadarService.BATTERY_STALE_MS
            val pfDecision = CriticalBatteryDecider.decide(
                pct = batt.pct,
                fresh = pfFresh,
                nowMs = nowMs,
                criticalPct = lowThresholdPct,
                cadenceMs = BikeRadarService.PREFLIGHT_BATTERY_CUE_INTERVAL_MS,
                lastCueMs = preflightBatteryCueMs[batt.slug],
            )
            val pfLast = pfDecision.lastCueMs
            if (pfLast == null) preflightBatteryCueMs.remove(batt.slug) else preflightBatteryCueMs[batt.slug] = pfLast
            if (pfDecision.fire) {
                beeper.playCriticalBattery()
                clog("# preflight_battery ${batt.slug} pct=${batt.pct}")
            }
        }
    }

    private fun fireAlertCue(
        state: RadarState,
        alerts: AlertDecider,
        overlayPrefs: PrefsSnapshot,
        nowMs: Long,
    ) {
        val snap = ebikeSnapshot()
        val preferredBikeSpeedMs = snap?.speedRaw?.let { it / 360f } ?: state.bikeSpeedMs
        val ev = alerts.decide(
            vehicles = state.vehicles,
            alertMaxM = overlayPrefs.alertMaxDistanceM,
            nowMs = nowMs,
            bikeSpeedMs = preferredBikeSpeedMs,
            bikeNotDriving = snap?.bikeNotDriving,
            climbing = climbingNow(),
            urgentLowSpeedEnabled = overlayPrefs.urgentLowSpeedEnabled,
        )
        if (ev !is AlertDecider.Event.None) logAlertEvent(ev, state, nowMs, preferredBikeSpeedMs)
        beeper.setPanning(
            enabled = overlayPrefs.experimentalLateralPanning,
            invertLR = overlayPrefs.experimentalLateralPanningInvertLR,
        )
        when (val cue = AlertCue.forEvent(ev)) {
            is AlertCue.Beep -> beeper.play(cue.count, cue.lateralPos)
            AlertCue.Clear -> beeper.playClear()
            is AlertCue.Urgent -> beeper.playUrgent(cue.lateralPos)
            AlertCue.Silence -> {}
        }
    }

    private fun logAlertEvent(
        ev: AlertDecider.Event,
        state: RadarState,
        nowMs: Long,
        gateBikeSpeedMs: Float?,
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
        val urgentPath = (ev as? AlertDecider.Event.UrgentApproach)?.let {
            " urgent_path=${if (it.viaMovingPath) "moving" else "stationary"}"
        } ?: ""
        val alertMax = prefs.alertMaxDistanceM
        val closest = state.vehicles
            .filter { !it.isBehind && !it.isAlongsideStationary && it.distanceM in 0..alertMax }
            .minByOrNull { it.distanceM }
        clog(
            "# alert ts=$nowMs event=$evStr " +
                "frame_closest_tid=${closest?.id ?: -1} " +
                "frame_closest_d=${closest?.distanceM ?: -1} " +
                "closing_mps=${closest?.let { -it.speedMs } ?: -1f} " +
                "bike_speed_mps=${state.bikeSpeedMs ?: -1f} " +
                "gate_speed_mps=${gateBikeSpeedMs ?: -1f}$urgentPath",
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
