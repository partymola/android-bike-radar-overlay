// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.ConcurrentHashMap

/**
 * The dashcam walk-away alarm and the dashcam liveness probe running together
 * in virtual time, on the real [RadarLinkCoordinator], [DashcamProbe] and
 * [BatteryReader]. The radar streams from [CONNECT_MS] and drops at [DROP_MS];
 * the probe ticks every [TICK_MS] on its own phase ([probePhaseMs] after the
 * drop's grid), the walk-away loop every [TICK_MS] from the drop, and each
 * read the probe launches ends [readMs] later, answering when [answers] says
 * so for its launch instant. Nothing here reads a real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DashcamProbeHarness(
    private val test: TestScope,
    app: Application,
    thresholdSec: Int,
    private val readMs: Long,
    private val probePhaseMs: Long = 0L,
    private val publishMs: Long = 0L,
    private val answers: (launchedAtMs: Long) -> Boolean = { true },
) {
    val prefs: Prefs = Prefs(app).apply {
        walkAwayAlarmEnabled = true
        walkAwayAlarmThresholdSec = thresholdSec
        dashcamOwnership = DashcamOwnership.YES
        dashcamMac = CAM
        dashcamDisplayName = "Cam"
    }

    var posts = 0
    var cancels = 0
    var stops = 0
    var rideWakeLockReleases = 0
    var ridingActivityMs: Long? = null
    val postTimes = mutableListOf<Long>()
    val launches = mutableListOf<Long>()
    val clogs = mutableListOf<String>()
    val journal = mutableListOf<String>()

    private val lastProbeMs = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()
    private var answerAtOnce = false

    fun now(): Long = CONNECT_MS + test.testScheduler.currentTime

    val coordinator = RadarLinkCoordinator(
        clock = ::now,
        prefs = prefs,
        postWalkAwayNotification = {
            posts++
            postTimes += now()
        },
        cancelWalkAwayNotification = { cancels++ },
        startWalkAwayAlarm = {},
        stopWalkAwayAlarm = { stops++ },
        alertBeeper = { null },
        clog = { clogs += it },
        journal = { journal += it },
        setReconnectBanner = {},
        resolveDashcamSlug = { "cam" },
        eBikeSnapshot = { null },
        eBikeSnapshotAtMs = { 0L },
        eBikeRidingSinceMs = { null },
        hasLinkableRadar = { true },
        hasEBikeSignal = { false },
        everSawTrack = { true },
        postForgotToLock = {},
        cancelForgotToLock = {},
        cancelWalkAwaySnooze = {},
        clearDashcamBackoff = {},
        lastRidingActivityMs = { ridingActivityMs },
        lastTrackActivityMs = { null },
        clearTrackActivity = {},
        forgetEBikeLock = {},
        wakeTick = {},
        acquireRideWakeLock = {},
        releaseRideWakeLock = { rideWakeLockReleases++ },
    )

    val reader = BatteryReader(
        context = app,
        scope = test.backgroundScope,
        prefs = prefs,
        knownDevices = KnownDevices(app.getSharedPreferences(KNOWN_DEVICES, Context.MODE_PRIVATE)),
        publishBattery = { _, _ ->
            delay(publishMs)
            true
        },
        macToSlug = mutableMapOf(),
        slug = { it.lowercase() },
        dashcamProbeFailures = failures,
        readBatteryFn = { read() },
        monoClock = ::now,
        wallClock = ::now,
    )

    private val probe = DashcamProbe(
        prefs = prefs,
        linkState = coordinator::snapshot,
        dashcamSlug = { "cam" },
        batteryReader = reader,
        lastProbeMs = lastProbeMs,
        probeFailures = failures,
    )

    init {
        coordinator.markLinkOpen(RADAR)
        coordinator.markConnected()
    }

    private suspend fun read(): Int? {
        if (answerAtOnce) return PCT
        val at = now()
        launches += at
        delay(readMs)
        return if (answers(at)) PCT else null
    }

    /** The camera answers a read that ends now, through the reader's own
     *  success path. */
    fun cameraAnswersNow() {
        test.backgroundScope.launch {
            answerAtOnce = true
            reader.doReadBattery("Cam", CAM)
            answerAtOnce = false
        }
        test.runCurrent()
    }

    /** Runs both loops to [untilMs] in [STEP_MS] steps. A read ending on a
     *  tick's instant lands before that tick. */
    fun runUntil(untilMs: Long) {
        var t = now()
        while (t < untilMs) {
            t += STEP_MS
            test.advanceTimeBy(t - now())
            test.runCurrent()
            if (t == DROP_MS) coordinator.markDisconnected()
            if ((t - DROP_MS - probePhaseMs).mod(TICK_MS) == 0L) probe.tick(t)
            if (t >= DROP_MS && (t - DROP_MS) % TICK_MS == 0L) {
                coordinator.tickWalkAwayState(t, TICK_MS)
                coordinator.evaluateWalkAway(t)
            }
            test.runCurrent()
        }
    }

    fun clogged(token: String) = clogs.count { it.contains(token) }

    companion object {
        const val CONNECT_MS = 1_000_000L
        const val DROP_MS = 1_061_000L
        const val TICK_MS = 2_000L
        const val STEP_MS = 100L
        const val RADAR = "AA:AA:AA:AA:AA:01"
        const val CAM = "CC:CC:CC:CC:CC:01"
        const val KNOWN_DEVICES = "harness_known_devices"
        private const val PCT = 80
    }
}
