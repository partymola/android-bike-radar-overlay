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
 * the probe ticks every [PROBE_TICK_MS] on its own phase ([probePhaseMs] after
 * the drop's grid), the walk-away loop every [WALK_AWAY_TICK_MS] from the drop,
 * and each read the probe launches ends [readMs] later ([readMsFor] for the
 * read with that index, when given), answering when [answers] says so for its
 * launch instant. [dozeUntil] stops both loops, as a phone asleep would.
 * Nothing here reads a real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DashcamProbeHarness(
    private val test: TestScope,
    app: Application,
    thresholdSec: Int,
    private val readMs: Long,
    private val probePhaseMs: Long = 0L,
    private val publishMs: Long = 0L,
    private val readMsFor: ((index: Int) -> Long)? = null,
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
    var rideWakeLockAcquires = 0
    var rideWakeLockReleases = 0
    var ridingActivityMs: Long? = null
    var trackActivityMs: Long? = null
    var hasEBikeSignal = false
    var eBike: LiveDataSnapshot? = null
    var eBikeAtMs = 0L

    /** How the radar link ends at [DROP_MS]; null measures nothing. */
    var dropLoss: LinkLoss? = null
    val postTimes = mutableListOf<Long>()
    val stopTimes = mutableListOf<Long>()
    val launches = mutableListOf<Long>()
    val clogs = mutableListOf<String>()
    val journal = mutableListOf<String>()

    val lastProbeMs = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val answered = ConcurrentHashMap<String, Long>()
    private var answerAtOnce = false
    private var lastWalkAwayTickMs: Long? = null
    var walkAwayWakeLockHeld = false
    var walkAwayWakeLockCapMs: Long? = null

    /** Runs inside the walk-away lock's acquire, before it is held: another
     *  thread's edge landing between the state the drop wrote and the lock. */
    var onWalkAwayWakeLockAcquire: () -> Unit = {}

    /** When the coordinator asked for the walk-away tick to run at once. */
    val kickTimes = mutableListOf<Long>()

    /** A kick runs a walk-away tick at that instant, as the service's loop
     *  does when its delay is cut short. Off by default so the fixed grid
     *  stays what the other tests measure against. */
    var ticksOnKick = false

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
        stopWalkAwayAlarm = {
            stops++
            stopTimes += now()
        },
        alertBeeper = { null },
        clog = { clogs += it },
        journal = { journal += it },
        setReconnectBanner = {},
        resolveDashcamSlug = { prefs.activeDashcamMac?.let { "cam" } },
        dashcamAnsweredAtMs = { answered[CAM] },
        eBikeSnapshot = { eBike },
        eBikeSnapshotAtMs = { eBikeAtMs },
        eBikeRidingSinceMs = { null },
        hasLinkableRadar = { true },
        hasEBikeSignal = { hasEBikeSignal },
        everSawTrack = { true },
        postForgotToLock = {},
        cancelForgotToLock = {},
        cancelWalkAwaySnooze = {},
        clearDashcamBackoff = {},
        lastRidingActivityMs = { ridingActivityMs },
        lastTrackActivityMs = { trackActivityMs },
        clearTrackActivity = {},
        forgetEBikeLock = {},
        wakeTick = {
            kickTimes += now()
            if (ticksOnKick) test.backgroundScope.launch { walkAwayTick(now()) }
        },
        acquireRideWakeLock = { rideWakeLockAcquires++ },
        releaseRideWakeLock = { rideWakeLockReleases++ },
        acquireWalkAwayWakeLock = { capMs ->
            onWalkAwayWakeLockAcquire()
            walkAwayWakeLockHeld = true
            walkAwayWakeLockCapMs = capMs
        },
        releaseWalkAwayWakeLock = { walkAwayWakeLockHeld = false },
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
        dashcamAnsweredAtMs = answered,
        onDashcamAnswered = coordinator::onDashcamAnswered,
        readBatteryFn = { read() },
        monoClock = ::now,
        wallClock = ::now,
    )

    private val probe = DashcamProbe(
        prefs = prefs,
        coordinator = coordinator,
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
        delay(readMsFor?.invoke(launches.size - 1) ?: readMs)
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

    /** Runs both loops to [untilMs], ticks at [untilMs] included, stopping at
     *  every tick instant in between. A read ending on a tick's instant lands
     *  before that tick. */
    fun runUntil(untilMs: Long) {
        while (now() < untilMs) {
            val t = minOf(nextTickAfter(now()), untilMs)
            test.advanceTimeBy(t - now())
            test.runCurrent()
            if (t == DROP_MS) coordinator.markDisconnected(dropLoss)
            if (onProbeGrid(t)) probe.tick(t)
            if (t >= DROP_MS && (t - DROP_MS) % WALK_AWAY_TICK_MS == 0L) walkAwayTick(t)
            test.runCurrent()
        }
    }

    /** One pass of the service's walk-away loop at [t]. */
    private fun walkAwayTick(t: Long) {
        val elapsedMs = t - (lastWalkAwayTickMs ?: (t - WALK_AWAY_TICK_MS))
        lastWalkAwayTickMs = t
        coordinator.tickWalkAwayState(t, elapsedMs)
        coordinator.evaluateWalkAway(t)
        coordinator.evaluateRadarLeftOn(t)
    }

    /** Time passes to [untilMs] with neither loop ticking. A read already in
     *  flight still ends on time, as its Bluetooth callback would wake the
     *  phone; start a doze with none in flight to model one fully asleep. */
    fun dozeUntil(untilMs: Long) {
        test.advanceTimeBy(untilMs - now())
        test.runCurrent()
    }

    private fun onProbeGrid(t: Long) = (t - DROP_MS - probePhaseMs).mod(PROBE_TICK_MS) == 0L

    private fun nextTickAfter(n: Long): Long {
        val probe = n + (DROP_MS + probePhaseMs - n).mod(PROBE_TICK_MS).let { if (it == 0L) PROBE_TICK_MS else it }
        val walkAway = if (n < DROP_MS) DROP_MS else n + (DROP_MS - n).mod(WALK_AWAY_TICK_MS).let { if (it == 0L) WALK_AWAY_TICK_MS else it }
        return minOf(probe, walkAway)
    }

    fun clogged(token: String) = clogs.count { it.contains(token) }

    companion object {
        const val CONNECT_MS = 1_000_000L
        const val DROP_MS = 1_061_000L

        // The service's own cadences, so the harness moves with them.
        const val PROBE_TICK_MS = BikeRadarService.DASHCAM_TICK_MS
        const val WALK_AWAY_TICK_MS = BikeRadarService.WALKAWAY_TICK_MS
        const val RADAR = "AA:AA:AA:AA:AA:01"
        const val CAM = "CC:CC:CC:CC:CC:01"
        const val KNOWN_DEVICES = "harness_known_devices"
        private const val PCT = 80
    }
}
