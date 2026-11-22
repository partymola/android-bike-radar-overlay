// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.data.PrefsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end driving tests for [OverlayPipeline]. The pipeline collects from
 * the process-wide [RadarStateBus] + [BatteryStateBus] + a private ticker
 * and emits side effects on the injected [OverlayHost] / [AlertBeeper] /
 * [HaClient]. These tests substitute a fake [OverlayHost] + null-returning
 * [PhoneBatterySource], run the pipeline on an unconfined test dispatcher,
 * publish state into the buses, and assert observable side effects.
 *
 * Lifecycle is the pipeline's contract surface; behavioural depth lives in
 * the per-component tests ([AlertDeciderTest], [ClosePassDetectorTest],
 * [DashcamStatusDeriverTest]) and the upstream-chain replay
 * ([PipelineReplayTest]).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OverlayPipelineDrivingTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private lateinit var beeper: AlertBeeper
    private lateinit var fakeHost: FakeOverlayHost

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        prefs = Prefs(context)
        beeper = AlertBeeper(
            audioManager = context.getSystemService(AudioManager::class.java),
            onCue = {},
        )
        fakeHost = FakeOverlayHost(context)
        // The pipeline subscribes to the process-wide RadarStateBus +
        // BatteryStateBus. Clear them so a previous test's residue does
        // not leak in as the initial value.
        RadarStateBus.clear()
        BatteryStateBus.clearForTest()
        ClosePassStateBus.reset()
        es.jjrh.bikeradar.ipc.RadarOverlayGate.reset()
    }

    @After
    fun tearDown() {
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        Dispatchers.resetMain()
        beeper.release()
        RadarStateBus.clear()
        BatteryStateBus.clearForTest()
        ClosePassStateBus.reset()
        // Process-global, and this file deliberately leaves a hold set in one
        // test. Classes share JVMs under parallel forks, so not resetting here
        // is an order-dependent failure in whatever runs next.
        es.jjrh.bikeradar.ipc.RadarOverlayGate.reset()
    }

    @Test
    fun attachReturnsAJobThatCancelsCleanlyWhenNothingPublished() = runTest {
        val pipeline = buildPipeline()
        val job: Job = pipeline.attach(this, "TestRadar")
        // Let the initial RadarState() (source = NONE) flow through the
        // combine + take the early-return branch on the first frame.
        runCurrent()
        assertTrue("job should be active after attach", job.isActive)
        job.cancel()
        job.join()
        assertFalse("job should be inactive after cancel", job.isActive)
        // NONE source must not attach the overlay.
        assertEquals(0, fakeHost.attachCount)
    }

    @Test
    fun noneSourceStateDoesNotAttachOverlay() = runTest {
        val pipeline = buildPipeline()
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        // Confirm the bus genuinely held a NONE frame.
        val state = RadarStateBus.state.firstOrNull()
        assertEquals(DataSource.NONE, state?.source)
        assertEquals(0, fakeHost.attachCount)
        job.cancel()
        job.join()
    }

    @Test
    fun aGrantedAppCanHideTheOverlayAndReleasingRestoresIt() = runTest {
        // The gate object has its own tests; this is the wiring. Without it
        // `setOverlayVisible` records a hold that nothing on screen reads,
        // and every one of those tests still passes.
        val pipeline = buildPipeline()
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()

        RadarStateBus.publish(liveFrame(100L))
        withTimeoutOrNull(1_000) {
            while (fakeHost.attachCount == 0) {
                runCurrent()
                kotlinx.coroutines.delay(10)
            }
            true
        }
        assertEquals(1, fakeHost.attachCount)

        es.jjrh.bikeradar.ipc.RadarOverlayGate.hide("com.example.trailbuddy")
        RadarStateBus.publish(liveFrame(200L))
        withTimeoutOrNull(1_000) {
            while (fakeHost.detachCount == 0) {
                runCurrent()
                kotlinx.coroutines.delay(10)
            }
            true
        }
        assertEquals("a hold must take the overlay off screen", 1, fakeHost.detachCount)

        // The rider gets it back without the consumer doing anything further,
        // which is what an unbind, a crash or a revoke all reduce to.
        es.jjrh.bikeradar.ipc.RadarOverlayGate.reset()
        RadarStateBus.publish(liveFrame(300L))
        withTimeoutOrNull(1_000) {
            while (fakeHost.attachCount < 2) {
                runCurrent()
                kotlinx.coroutines.delay(10)
            }
            true
        }
        assertEquals("releasing the hold must put it back", 2, fakeHost.attachCount)

        job.cancel()
        job.join()
    }

    @Test
    fun aHoldHeldFromTheStartNeverAttachesTheOverlay() = runTest {
        // The consumer asked before the ride began. Attaching and immediately
        // detaching would flash the overlay over their map.
        es.jjrh.bikeradar.ipc.RadarOverlayGate.hide("com.example.trailbuddy")
        val pipeline = buildPipeline()
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()

        RadarStateBus.publish(liveFrame(100L))
        repeat(5) {
            runCurrent()
            kotlinx.coroutines.delay(10)
        }

        assertEquals("must never have attached", 0, fakeHost.attachCount)

        // The positive control, and it is what makes the zero above mean
        // anything: a pipeline that had simply not processed the frame yet
        // would also report zero. Dropping the hold has to produce an attach
        // from the same pump, so the zero was the gate and not the clock.
        es.jjrh.bikeradar.ipc.RadarOverlayGate.show("com.example.trailbuddy")
        RadarStateBus.publish(liveFrame(200L))
        val attached = kotlinx.coroutines.withTimeoutOrNull(1_000) {
            while (fakeHost.attachCount == 0) {
                runCurrent()
                kotlinx.coroutines.delay(10)
            }
            true
        }

        assertEquals("the frames were reaching the pipeline all along", true, attached)
        job.cancel()
        job.join()
    }

    @Test
    fun aCallPutsAHeldOverlayBackAndTheHoldResumesAfter() = runTest {
        // During a call the beeper is silent, so an overlay a granted app is
        // holding hidden would leave the rider with no warning from Bike Radar.
        val audio = context.getSystemService(AudioManager::class.java)
        val clogLines = mutableListOf<String>()
        es.jjrh.bikeradar.ipc.RadarOverlayGate.hide("com.example.trailbuddy")
        val pipeline = buildPipeline(clog = { clogLines += it })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()

        RadarStateBus.publish(liveFrame(100L))
        runCurrent()
        assertEquals("held and no call: hidden", 0, fakeHost.attachCount)

        audio.mode = AudioManager.MODE_IN_CALL
        RadarStateBus.publish(liveFrame(200L))
        runCurrent()
        assertEquals("a call shows it", 1, fakeHost.attachCount)
        assertTrue(clogLines.toString(), clogLines.contains("# overlay shown during a call over a granted app's hold"))

        audio.mode = AudioManager.MODE_NORMAL
        RadarStateBus.publish(liveFrame(300L))
        runCurrent()
        assertEquals("the hold applies again after the call", 1, fakeHost.detachCount)

        job.cancel()
        job.join()
    }

    @Test
    fun aVoipCallAlsoPutsAHeldOverlayBack() = runTest {
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        es.jjrh.bikeradar.ipc.RadarOverlayGate.hide("com.example.trailbuddy")
        val pipeline = buildPipeline()
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        RadarStateBus.publish(liveFrame(100L))
        runCurrent()
        assertEquals(1, fakeHost.attachCount)
        job.cancel()
        job.join()
    }

    @Test
    fun aHeldOverlayDoesNotSilenceTheBeeps() = runTest {
        // The behavioural half of OverlayHideDoesNotReachTheAlertPathTest: a
        // granted app hiding the overlay takes the display, never the warning.
        // Asserted at the beeper, so a gate at the play site fails here too.
        val cues = mutableListOf<String>()
        beeper.release()
        beeper = AlertBeeper(
            audioManager = context.getSystemService(AudioManager::class.java),
            executor = java.util.concurrent.Executor { it.run() },
            playTrackOverride = { true },
            onCue = { cues += it },
        )
        es.jjrh.bikeradar.ipc.RadarOverlayGate.hide("com.example.trailbuddy")
        var mono = 1_000L
        val pipeline = buildPipeline(clockMono = { mono })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        val car = Vehicle(id = 7, distanceM = 6, speedMs = -3f, rangeXm = 1f)
        for (t in listOf(1_000L, 1_100L)) {
            mono = t
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = t, vehicles = listOf(car), bikeSpeedMs = 5f))
            runCurrent()
        }
        assertEquals("hidden throughout", 0, fakeHost.attachCount)
        assertEquals("but the beep plays", listOf("beep count=3"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun aFailingAttachIsLoggedOnceNotEveryFrame() = runTest {
        val clogLines = mutableListOf<String>()
        fakeHost.canDraw = false
        val pipeline = buildPipeline(clog = { clogLines += it })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        (1..5).forEach {
            RadarStateBus.publish(liveFrame(it * 100L))
            runCurrent()
        }
        assertEquals(clogLines.toString(), 1, clogLines.count { it == "# overlay: SYSTEM_ALERT_WINDOW not granted" })
        fakeHost.canDraw = true
        RadarStateBus.publish(liveFrame(600L))
        runCurrent()
        assertEquals(1, fakeHost.attachCount)
        assertTrue(clogLines.toString(), clogLines.contains("# overlay added"))
        job.cancel()
        job.join()
    }

    @Test
    fun aFailingAddViewIsLoggedOnceWhateverItsMessage() = runTest {
        // A refused window names a fresh window in its message on every
        // attempt, so comparing the text would log every frame.
        val clogLines = mutableListOf<String>()
        var attempt = 0
        fakeHost.attachResult = { IllegalStateException("window ${attempt++} refused") }
        val pipeline = buildPipeline(clog = { clogLines += it })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        (1..5).forEach {
            RadarStateBus.publish(liveFrame(it * 100L))
            runCurrent()
        }
        assertTrue("every frame tried: $attempt", attempt >= 5)
        assertEquals(clogLines.toString(), 1, clogLines.count { it.startsWith("# overlay addView failed") })
        job.cancel()
        job.join()
    }

    private fun liveFrame(ts: Long) = RadarState(
        source = DataSource.V2,
        timestamp = ts,
        vehicles = emptyList(),
        bikeSpeedMs = 5f,
    )

    @Test
    fun nonNoneSourceStateAttachesOverlayAndForwardsToHost() = runTest {
        val pipeline = buildPipeline()
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        // Publish a non-NONE state. The pipeline's combine() re-emits and
        // the overlayAdded gate flips, calling fakeHost.attach exactly once
        // (createView is called once when attach() begins).
        RadarStateBus.publish(
            RadarState(
                source = DataSource.V2,
                timestamp = 100L,
                vehicles = emptyList(),
                bikeSpeedMs = 5f,
            ),
        )
        // Wait for the collect to drain (small budget; attach should be
        // synchronous-ish on UnconfinedTestDispatcher).
        val attached = withTimeoutOrNull(1_000) {
            while (fakeHost.attachCount == 0) {
                runCurrent()
                kotlinx.coroutines.delay(10)
            }
            true
        }
        assertEquals("overlay must attach for a non-NONE frame", true, attached)
        assertEquals(1, fakeHost.attachCount)
        job.cancel()
        job.join()
        // Detach fires in the attach()'s finally block.
        assertEquals(1, fakeHost.detachCount)
    }

    @Test
    fun closePassCountingWorksWithoutHomeAssistant() = runTest {
        // The close-pass count card + ride history are local features:
        // detection must run with the user toggle on even when HA was
        // never configured (buildPipeline injects a blank HaClient).
        prefs.closePassLoggingEnabled = true
        try {
            val pipeline = buildPipeline()
            val job = pipeline.attach(this, "TestRadar")
            runCurrent()
            // Drive an arming overtake: approaching fast (-8 m/s), inside
            // the 1.0 m emit threshold (lateralPos 0.25 * LATERAL_FULL_M
            // 3.0 = 0.75 m), rider
            // moving, >= minFramesToArm frames, and coming alongside so the
            // clearance is measured at all. rangeXmRaw is the sensor's own
            // reading: left at 0 it is the radar's declined-to-answer value and
            // the detector skips every frame.
            val base = System.currentTimeMillis()
            repeat(4) { i ->
                RadarStateBus.publish(
                    RadarState(
                        source = DataSource.V2,
                        timestamp = base + i * 100L,
                        vehicles = listOf(
                            Vehicle(id = 7, distanceM = listOf(20, 14, 3, 2)[i], speedMs = -8f, lateralPos = 0.25f, rangeXmRaw = 0.75f),
                        ),
                        bikeSpeedMs = 5f,
                    ),
                )
                runCurrent()
            }
            // Track disappears -> detector terminates it and emits.
            RadarStateBus.publish(
                RadarState(
                    source = DataSource.V2,
                    timestamp = base + 500L,
                    vehicles = emptyList(),
                    bikeSpeedMs = 5f,
                ),
            )
            val counted = withTimeoutOrNull(2_000) {
                while (ClosePassStateBus.sessionCount.value == 0) {
                    runCurrent()
                    kotlinx.coroutines.delay(10)
                }
                true
            }
            assertEquals("close pass must count with HA unconfigured", true, counted)
            job.cancel()
            job.join()
        } finally {
            prefs.closePassLoggingEnabled = false
        }
    }

    private fun buildPipeline(
        ha: () -> HaClient = { HaClient("", "") },
        currentRadarMac: () -> String? = { null },
        macToSlug: () -> Map<String, String> = { emptyMap() },
        turnState: () -> TurnStateDecider.State = { TurnStateDecider.State.IDLE },
        turnSensorStart: () -> Unit = {},
        turnSensorStop: () -> Unit = {},
        clog: (String) -> Unit = {},
        clockMono: (() -> Long)? = null,
        overlayPrefsSnapshot: () -> PrefsSnapshot = { prefs.snapshot() },
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        ebike: EBikeSnapshotCoordinator = coordinator(MonoClock(0L)),
        rideStats: () -> RideStatsAccumulator = { RideStatsAccumulator() },
    ): OverlayPipeline = OverlayPipeline(
        prefs = prefs,
        ha = ha,
        beeper = beeper,
        overlayHost = fakeHost,
        phoneBattery = object : PhoneBatterySource {
            override fun readSnapshot(): PhoneBatteryReading? = null
        },
        rideStats = rideStats,
        overlayPrefsSnapshot = overlayPrefsSnapshot,
        ebike = ebike,
        turnState = turnState,
        turnSensorStart = turnSensorStart,
        turnSensorStop = turnSensorStop,
        currentRadarMac = currentRadarMac,
        macToSlug = macToSlug,
        clog = clog,
        clockMono = clockMono ?: { SystemClock.elapsedRealtime() },
        // Publishes run inline on the test's own thread. On a real dispatcher
        // they run on wall-clock threads while runTest's virtual clock races
        // ahead, so a test that waits for one can time out before it has had
        // any real time to execute.
        ioDispatcher = ioDispatcher,
    )

    /** Drive one stationary urgent and return the alert line it logged:
     *  confirm the dwell with an empty frame, age it out, then two frames
     *  carrying a slow near car and the fast far trigger - the second
     *  fires. Two frames of history leave the pass fit unconfident, so the
     *  pass gate fails open whatever margin is configured. */
    private suspend fun kotlinx.coroutines.test.TestScope.driveStationaryUrgent(
        overlayPrefsSnapshot: () -> PrefsSnapshot = { prefs.snapshot() },
        slowNearDistanceM: Int = 8,
    ): String {
        var mono = 1_000L
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(
            clog = { clogLines += it },
            clockMono = { mono },
            overlayPrefsSnapshot = overlayPrefsSnapshot,
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 1_000L, vehicles = emptyList(), bikeSpeedMs = 0f),
        )
        runCurrent()
        mono = 3_500L
        val slowNear = Vehicle(id = 3, distanceM = slowNearDistanceM, speedMs = -1f, rangeXm = 1f)
        val fastFar = Vehicle(id = 9, distanceM = 15, speedMs = -8f, rangeXm = -1f)
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 3_500L, vehicles = listOf(slowNear, fastFar), bikeSpeedMs = 0f),
        )
        runCurrent()
        mono = 3_600L
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 3_600L, vehicles = listOf(slowNear, fastFar), bikeSpeedMs = 0f),
        )
        runCurrent()
        val urgentLine = clogLines.firstOrNull { it.contains("event=UrgentApproach") }
        assertTrue("expected an UrgentApproach alert line, got $clogLines", urgentLine != null)
        job.cancel()
        job.join()
        return urgentLine!!
    }

    @Test
    fun urgentAlertLogCarriesTheTriggerVehicle() = runTest {
        // The capture-log audit contract: an UrgentApproach line must name
        // the vehicle that opened the gate (trigger_*), because
        // frame_closest_* records the nearest car - in the field often a
        // different, slower one, which made urgents unauditable.
        val urgentLine = driveStationaryUrgent()
        assertTrue(
            "urgent line must carry the trigger vehicle, got $urgentLine",
            urgentLine.contains("trigger_tid=9") &&
                urgentLine.contains("trigger_d=15") &&
                urgentLine.contains("trigger_closing_mps=8.0") &&
                urgentLine.contains("trigger_rx=-1.0"),
        )
    }

    @Test
    fun alertLogNamesTheClosestCarTheDeciderActuallySaw() = runTest {
        // frame_closest_* is filtered by the alert envelope, and it must be
        // the envelope decide() gated on. Reading it fresh from prefs would
        // name a different car on the same line under a mid-ride change.
        // prefs says 10 m here, the snapshot says 20: a fresh read filters
        // both cars out and reports no closest at all.
        prefs.alertMaxDistanceM = 10
        val doctored = { prefs.snapshot().copy(alertMaxDistanceM = 20) }
        val urgentLine = driveStationaryUrgent(overlayPrefsSnapshot = doctored, slowNearDistanceM = 12)
        assertTrue(
            "alert line must name the near car the decider saw, got $urgentLine",
            urgentLine.contains("frame_closest_tid=3") && urgentLine.contains("frame_closest_d=12"),
        )
    }

    @Test
    fun urgentAlertLogCarriesTheMarginItWasGatedOn() = runTest {
        // The pass gate records a threshold only when it VETOES, so without
        // this a reported spurious cue cannot be told apart from one the
        // rider had widened the margin into. It must be the value decide()
        // was called with, not a fresh read at log time: the snapshot here
        // deliberately disagrees with what prefs holds.
        val doctored = { prefs.snapshot().copy(urgentPassClearanceM = 2.75f, closingSpeedCeilingMs = 50) }
        assertTrue("prefs must not already hold the doctored margin", prefs.urgentPassClearanceM != 2.75f)
        assertTrue("prefs must not already hold the doctored ceiling", prefs.closingSpeedCeilingMs != 50)
        val urgentLine = driveStationaryUrgent(overlayPrefsSnapshot = doctored)
        assertTrue(
            "urgent line must carry the gated margin and ceiling, got $urgentLine",
            urgentLine.contains("gate_clearance_m=2.75") && urgentLine.contains("gate_ceiling_mps=50.0"),
        )
    }

    /** A stopped rider and a car closing fast 3 m to the side, measured at one
     *  spot, so it has no confident fit. Returns how many urgent lines had been logged
     *  after each of its frames, and the urgent lines themselves. */
    private suspend fun kotlinx.coroutines.test.TestScope.driveSideCar(waitOn: Boolean): Pair<List<Int>, List<String>> {
        var mono = 1_000L
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(
            clog = { clogLines += it },
            clockMono = { mono },
            overlayPrefsSnapshot = { prefs.snapshot().copy(urgentUnconfidentWaitEnabled = waitOn) },
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = 1_000L, vehicles = emptyList(), bikeSpeedMs = 0f))
        runCurrent()
        val side = Vehicle(id = 9, distanceM = 15, speedMs = -8f, rangeXm = 3f, rangeXmRaw = 3f, bornAtMs = 3_000L)
        val counts = listOf(3_500L, 3_600L, 3_900L).map { t ->
            mono = t
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = t, vehicles = listOf(side), bikeSpeedMs = 0f))
            runCurrent()
            clogLines.count { it.contains("event=UrgentApproach") }
        }
        job.cancel()
        job.join()
        RadarStateBus.clear()
        return counts to clogLines.filter { it.contains("event=UrgentApproach") }
    }

    @Test
    fun theUnconfidentWaitSettingReachesTheDecider() = runTest {
        // The decider's tests drive the parameter directly, so they stay
        // green if the pipeline stops passing it. Off, the urgent sounds on
        // the car's first qualifying frame; on, 300 ms later. The line says
        // which, so a ride with it on can be told apart afterwards.
        val (offCounts, offLines) = driveSideCar(waitOn = false)
        assertEquals(listOf(0, 1, 1), offCounts)
        assertTrue(offLines.toString(), offLines.single().contains("gate_unconf_wait=off"))
        val (onCounts, onLines) = driveSideCar(waitOn = true)
        assertEquals(listOf(0, 0, 1), onCounts)
        assertTrue(onLines.toString(), onLines.single().contains("gate_unconf_wait=on"))
    }

    /** Two frames of one car closing at 42.5 m/s with the rider moving, and
     *  the capture-log lines they produce. */
    private suspend fun kotlinx.coroutines.test.TestScope.driveFastCloser(
        overlayPrefsSnapshot: () -> PrefsSnapshot,
    ): List<String> {
        var mono = 1_000L
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(clog = { clogLines += it }, clockMono = { mono }, overlayPrefsSnapshot = overlayPrefsSnapshot)
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        for ((t, d) in listOf(1_000L to 15, 1_100L to 12)) {
            mono = t
            val car = Vehicle(id = 8, distanceM = d, speedMs = -42.5f, rangeXm = 1f)
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = t, vehicles = listOf(car), bikeSpeedMs = 5f))
            runCurrent()
        }
        job.cancel()
        job.join()
        RadarStateBus.clear()
        return clogLines
    }

    @Test
    fun theRidersClosingCeilingReachesTheDecider() = runTest {
        // The decider's own tests drive the parameter directly, so they stay
        // green if the pipeline stops passing it. Under a snapshot carrying
        // 35 m/s the car is silenced; under 45, or the shipped no limit, it is
        // announced, and the alert line says which ceiling let it through.
        val strict = driveFastCloser { prefs.snapshot().copy(closingSpeedCeilingMs = 35) }
        assertEquals(strict.toString(), 0, strict.count { it.contains("event=Beep") })
        assertTrue(strict.toString(), strict.any { it.startsWith("# gate ceiling tid=8") })
        val raised = driveFastCloser { prefs.snapshot().copy(closingSpeedCeilingMs = 45) }
        assertEquals(raised.toString(), 1, raised.count { it.contains("event=Beep") && it.contains("gate_ceiling_mps=45.0") })
        val shipped = driveFastCloser { prefs.snapshot() }
        assertEquals(shipped.toString(), 1, shipped.count { it.contains("event=Beep") && it.contains("gate_ceiling_mps=none") })
        assertFalse(shipped.toString(), shipped.any { it.startsWith("# gate ceiling") })
    }

    /** Swap in a beeper that records what it actually played. */
    private fun recordingBeeper(): MutableList<String> {
        val cues = mutableListOf<String>()
        beeper.release()
        beeper = AlertBeeper(
            audioManager = context.getSystemService(AudioManager::class.java),
            executor = java.util.concurrent.Executor { it.run() },
            playTrackOverride = { true },
            onCue = { cues += it },
        )
        return cues
    }

    @Test
    fun theUrgentWarningReachesTheBeeper() = runTest {
        // The alert line is written before the cue is played, so it cannot
        // show the cue reached the speaker; this asserts at the beeper.
        val cues = recordingBeeper()
        driveStationaryUrgent()
        assertTrue("the urgent must be played, got $cues", cues.contains("urgent"))
    }

    @Test
    fun theAllClearReachesTheBeeper() = runTest {
        val cues = recordingBeeper()
        var mono = 1_000L
        val pipeline = buildPipeline(clockMono = { mono })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        val car = Vehicle(id = 7, distanceM = 6, speedMs = -3f, rangeXm = 1f)
        for (t in 1_000L..6_000L step 100L) {
            mono = t
            val vehicles = if (t <= 1_100L) listOf(car) else emptyList()
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = t, vehicles = vehicles, bikeSpeedMs = 5f))
            runCurrent()
        }
        assertEquals("the car is announced, then the road behind clears", listOf("beep count=3", "clear"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun aCarInOneFrameIsNotAnnouncedOnTicksAlone() = runTest {
        // The decider counts a car only once it is in two frames, and the
        // pipeline also runs on its own tick with the radar state unchanged.
        // `runs` counts snapshot reads, which the pipeline makes on every
        // emission before it looks at the frame, so it shows the ticks ran.
        val cues = recordingBeeper()
        var runs = 0
        val pipeline = buildPipeline(
            clockMono = { 1_000L + testScheduler.currentTime },
            overlayPrefsSnapshot = {
                runs++
                prefs.snapshot()
            },
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        val car = Vehicle(id = 7, distanceM = 6, speedMs = -3f, rangeXm = 1f)
        fun frame() {
            val t = 1_000L + testScheduler.currentTime
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = t, vehicles = listOf(car), bikeSpeedMs = 5f))
            runCurrent()
        }
        frame()
        val runsAfterFrame = runs
        advanceTimeBy(4_900L)
        runCurrent()
        assertTrue("the ticks ran the pipeline: ${runs - runsAfterFrame}", runs - runsAfterFrame >= 2)
        assertEquals("one frame and ticks are not two frames", emptyList<String>(), cues)
        frame()
        assertEquals("a second frame is", listOf("beep count=3"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun aStallRestartsTheAllClearGraceOnTheFirstFrameAfterIt() = runTest {
        // The car is announced, the next frame is empty, and the stream stops
        // for 4.7 s. The grace needs 2 s of frames, so it starts again on the
        // first frame after the stall and the all-clear lands 2 s after that.
        // That a tick inside a gap chimes nothing is pinned by
        // theAllClearLandsOnAFrameNotOnTheTickBeforeIt, whose gap the grace
        // counts across; RadarV1SafetyTest drives the legacy stream.
        val cues = timedBeeper()
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        runCurrent()
        frameAt(0L, listOf(nearCar))
        frameAt(100L, listOf(nearCar))
        frameAt(200L, emptyList())
        for (t in 4_900L..6_800L step 100L) frameAt(t, emptyList())
        assertEquals("1.9 s of frames after the stall is short of the grace", listOf("100 beep count=3"), cues)
        frameAt(6_900L, emptyList())
        assertEquals(listOf("100 beep count=3", "6900 clear"), cues)
        job.cancel()
        job.join()
    }

    // ── frames, not ticks ────────────────────────────────────────────────
    //
    // The pipeline also runs on its 2 s tick and on every battery update,
    // with the radar state unchanged. These tests run it on a clock tied to
    // virtual time, as on a phone: with a fixed clock a tick lands at the
    // same instant as the frame before it, and none of them could fail.

    private fun kotlinx.coroutines.test.TestScope.virtualMono(): () -> Long = { 1_000L + testScheduler.currentTime }

    /** Run virtual time to [t], ticks due by then included. */
    private fun kotlinx.coroutines.test.TestScope.advanceTo(t: Long) {
        advanceTimeBy(t - testScheduler.currentTime)
        runCurrent()
    }

    /** Publish a V2 frame at virtual time [t]. */
    private fun kotlinx.coroutines.test.TestScope.frameAt(t: Long, vehicles: List<Vehicle>, bikeSpeedMs: Float = 5f) {
        advanceTo(t)
        RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = 1_000L + t, vehicles = vehicles, bikeSpeedMs = bikeSpeedMs))
        runCurrent()
    }

    /** As [recordingBeeper], each cue led by the virtual time it played at. */
    private fun kotlinx.coroutines.test.TestScope.timedBeeper(): MutableList<String> {
        val cues = mutableListOf<String>()
        beeper.release()
        beeper = AlertBeeper(
            audioManager = context.getSystemService(AudioManager::class.java),
            executor = java.util.concurrent.Executor { it.run() },
            playTrackOverride = { true },
            onCue = { cues += "${testScheduler.currentTime} $it" },
        )
        return cues
    }

    private val nearCar = Vehicle(id = 7, distanceM = 6, speedMs = -3f, rangeXm = 1f)
    private val slowNear = Vehicle(id = 3, distanceM = 8, speedMs = -1f, rangeXm = 1f)
    private val fastFar = Vehicle(id = 9, distanceM = 15, speedMs = -8f, rangeXm = -1f)

    @Test
    fun theAllClearLandsOnAFrameNotOnTheTickBeforeIt() = runTest {
        // The road reads empty from 1.4 s, so the 2 s grace is up at 3.4 s,
        // and a radio gap short enough for the grace to count across holds
        // the next frame from 3.3 s until 4.05 s. The tick at 4 s falls in
        // the gap.
        val cues = timedBeeper()
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        runCurrent()
        frameAt(1_200L, listOf(nearCar))
        frameAt(1_300L, listOf(nearCar))
        for (t in 1_400L..3_300L step 100L) frameAt(t, emptyList())
        advanceTo(4_000L)
        frameAt(4_050L, emptyList())
        assertEquals(listOf("1300 beep count=3", "4050 clear"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun twoFramesInOneMillisecondAreTwoFrames() = runTest {
        // Frames often share a millisecond, so a frame is told from a re-sent
        // one by identity, not by its timestamp.
        val cues = timedBeeper()
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        runCurrent()
        frameAt(0L, listOf(nearCar), bikeSpeedMs = 5f)
        frameAt(0L, listOf(nearCar), bikeSpeedMs = 5.25f)
        assertEquals(listOf("0 beep count=3"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun theUrgentIsNotRepeatedOnTicksAfterTheStreamStops() = runTest {
        // Inside one episode the urgent repeats 3 s after it sounded, on the
        // next frame that still holds the car; a tick is not such a frame.
        val cues = timedBeeper()
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        runCurrent()
        frameAt(0L, emptyList(), bikeSpeedMs = 0f)
        frameAt(2_500L, listOf(slowNear, fastFar), bikeSpeedMs = 0f)
        frameAt(2_600L, listOf(slowNear, fastFar), bikeSpeedMs = 0f)
        assertEquals(listOf("2600 urgent"), cues)
        advanceTo(6_400L)
        assertEquals("ticks at 4 s and 6 s do not repeat it", listOf("2600 urgent"), cues)
        frameAt(6_500L, listOf(slowNear, fastFar), bikeSpeedMs = 0f)
        assertEquals("the next frame does", listOf("2600 urgent", "6500 urgent"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun anUrgentNeedsTwoFramesNotAFrameAndATick() = runTest {
        val cues = timedBeeper()
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        runCurrent()
        frameAt(0L, emptyList(), bikeSpeedMs = 0f)
        frameAt(2_500L, listOf(slowNear, fastFar), bikeSpeedMs = 0f)
        advanceTo(4_000L)
        assertEquals("one frame and a tick", emptyList<String>(), cues)
        frameAt(4_100L, listOf(slowNear, fastFar), bikeSpeedMs = 0f)
        assertEquals("two frames", listOf("4100 urgent"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun aBatteryUpdateIsNotARadarFrame() = runTest {
        val cues = timedBeeper()
        var runs = 0
        val pipeline = buildPipeline(
            clockMono = virtualMono(),
            overlayPrefsSnapshot = {
                runs++
                prefs.snapshot()
            },
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        frameAt(0L, listOf(nearCar))
        val runsAfterFrame = runs
        advanceTo(100L)
        BatteryStateBus.update(BatteryEntry(slug = "dashcam", name = "Dashcam", pct = 80))
        runCurrent()
        assertEquals("the update ran the pipeline", runsAfterFrame + 1, runs)
        assertEquals("one frame and a battery update", emptyList<String>(), cues)
        frameAt(200L, listOf(nearCar))
        assertEquals("two frames", listOf("200 beep count=3"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun aFramePublishedWhilePausedIsNotCountedAfterTheResume() = runTest {
        val cues = timedBeeper()
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        runCurrent()
        try {
            prefs.pausedUntilEpochMs = Long.MAX_VALUE
            frameAt(0L, listOf(nearCar))
            prefs.pausedUntilEpochMs = 0L
            // A tick after the resume, still on the frame from the pause.
            advanceTo(2_000L)
            frameAt(2_100L, listOf(nearCar))
            assertEquals("the paused frame is not one of the two", emptyList<String>(), cues)
            frameAt(2_200L, listOf(nearCar))
            assertEquals(listOf("2200 beep count=3"), cues)
        } finally {
            prefs.pausedUntilEpochMs = 0L
            job.cancel()
            job.join()
        }
    }

    @Test
    fun closePassesAreStillCountedWhilePaused() = runTest {
        // A pause silences the cues; the ride's record keeps being kept.
        prefs.closePassLoggingEnabled = true
        prefs.pausedUntilEpochMs = Long.MAX_VALUE
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        try {
            runCurrent()
            driveOneOvertake()
            runCurrent()
            assertEquals(1, ClosePassStateBus.sessionCount.value)
        } finally {
            prefs.pausedUntilEpochMs = 0L
            prefs.closePassLoggingEnabled = false
            job.cancel()
            job.join()
        }
    }

    @Test
    fun rideStatisticsStillRunWhilePaused() = runTest {
        val stats = RideStatsAccumulator(monoMsProvider = virtualMono())
        prefs.pausedUntilEpochMs = Long.MAX_VALUE
        val job = buildPipeline(clockMono = virtualMono(), rideStats = { stats }).attach(this, "TestRadar")
        try {
            runCurrent()
            frameAt(0L, emptyList())
            frameAt(100L, emptyList())
            assertEquals("0.1 s at 5 m/s", 0.0005f, stats.snapshot().distanceRiddenKm, 0.000001f)
        } finally {
            prefs.pausedUntilEpochMs = 0L
            job.cancel()
            job.join()
        }
    }

    @Test
    fun aTickDoesNotArmACloseBesideTheRider() = runTest {
        // The detector arms a track on its third frame.
        prefs.closePassLoggingEnabled = true
        fun overtaker(id: Int, d: Int) = Vehicle(id = id, distanceM = d, speedMs = -8f, lateralPos = 0.25f, rangeXmRaw = 0.75f)
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        try {
            runCurrent()
            frameAt(0L, listOf(overtaker(7, 14)))
            frameAt(100L, listOf(overtaker(7, 2)))
            advanceTo(2_000L)
            frameAt(2_100L, emptyList())
            assertEquals("two frames and a tick", 0, ClosePassStateBus.sessionCount.value)
            frameAt(2_200L, listOf(overtaker(8, 14)))
            frameAt(2_300L, listOf(overtaker(8, 3)))
            frameAt(2_400L, listOf(overtaker(8, 2)))
            frameAt(2_500L, emptyList())
            assertEquals("three frames", 1, ClosePassStateBus.sessionCount.value)
        } finally {
            prefs.closePassLoggingEnabled = false
            job.cancel()
            job.join()
        }
    }

    @Test
    fun theRideClearanceIsNotWeightedByTicks() = runTest {
        // The figure is the upper median of the frames alongside: of 0.6 m and
        // 1.2 m it is 1.2 m, and two ticks repeating the 0.6 m frame would make
        // it 0.6 m.
        val original = prefs.closePassClosingSpeedFloorMs
        prefs.closePassClosingSpeedFloorMs = 5
        prefs.closePassLoggingEnabled = false
        val stats = RideStatsAccumulator(monoMsProvider = virtualMono())
        val job = buildPipeline(clockMono = virtualMono(), rideStats = { stats }).attach(this, "TestRadar")
        try {
            runCurrent()
            frameAt(0L, listOf(Vehicle(id = 7, distanceM = 20, speedMs = -5.5f, lateralPos = 0.8f, rangeXmRaw = 2.4f, bornAtMs = 1L)))
            frameAt(100L, listOf(Vehicle(id = 7, distanceM = 2, speedMs = -1f, lateralPos = 0.2f, rangeXmRaw = 0.6f, bornAtMs = 1L)))
            advanceTo(4_000L)
            frameAt(4_100L, listOf(Vehicle(id = 7, distanceM = 1, speedMs = -1f, lateralPos = 0.4f, rangeXmRaw = 1.2f, bornAtMs = 1L)))
            frameAt(4_200L, emptyList())
            assertEquals("the median of the frames", 1.2f, stats.snapshot().minLateralClearanceM!!, 0.001f)
        } finally {
            prefs.closePassClosingSpeedFloorMs = original
            job.cancel()
            job.join()
        }
    }

    @Test
    fun aStalledStreamAddsNoRideDistanceOnTicks() = runTest {
        // A gap between frames wider than the link's 5 s stall threshold adds
        // no distance; ticks every 2 s across it must not bridge it.
        val stats = RideStatsAccumulator(monoMsProvider = virtualMono())
        val job = buildPipeline(clockMono = virtualMono(), rideStats = { stats }).attach(this, "TestRadar")
        try {
            runCurrent()
            frameAt(0L, emptyList())
            advanceTo(12_000L)
            frameAt(12_100L, emptyList())
            frameAt(12_200L, emptyList())
            assertEquals("0.1 s at 5 m/s, after the gap", 0.0005f, stats.snapshot().distanceRiddenKm, 0.000001f)
        } finally {
            job.cancel()
            job.join()
        }
    }

    @Test
    fun aReleasedHoldPutsTheOverlayBackOnATick() = runTest {
        // The overlay is time-driven: no radar frame is needed to restore it.
        es.jjrh.bikeradar.ipc.RadarOverlayGate.hide("com.example.trailbuddy")
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        runCurrent()
        frameAt(0L, emptyList())
        assertEquals("held", 0, fakeHost.attachCount)
        es.jjrh.bikeradar.ipc.RadarOverlayGate.reset()
        advanceTo(2_000L)
        assertEquals("back on the tick", 1, fakeHost.attachCount)
        job.cancel()
        job.join()
    }

    @Test
    fun bornCloseEvidenceComesFromFramesOnly() = runTest {
        // A car first seen 8 m back needs two consecutive frames closing at
        // 2.5 m/s or more before it is heard. One such frame and a tick
        // repeating it are not two.
        val cues = timedBeeper()
        fun ghost(closingMs: Float) = Vehicle(id = 7, distanceM = 8, speedMs = -closingMs, bornDistanceM = 8, bornInformative = true, bornAtMs = 1_000L)
        val job = buildPipeline(clockMono = virtualMono()).attach(this, "TestRadar")
        runCurrent()
        frameAt(0L, listOf(ghost(0f)))
        frameAt(100L, listOf(ghost(3f)))
        advanceTo(2_000L)
        frameAt(2_100L, listOf(ghost(0f)))
        assertEquals("one closing frame and a tick", emptyList<String>(), cues)
        frameAt(2_200L, listOf(ghost(3f)))
        frameAt(2_300L, listOf(ghost(3f)))
        assertEquals("two closing frames", listOf("2300 beep count=2"), cues)
        job.cancel()
        job.join()
    }

    @Test
    fun theLivePipelineHearsTheCueLedgerFixtureAsTheLedgerDoes() = runTest {
        // CueLedgerReplayTest calls the decider once per decoded frame. Here the
        // same frames reach the real pipeline on virtual time with its ticks
        // running, and must produce the same cues at the same moments.
        val fixture = javaClass.classLoader!!.getResourceAsStream("replay-fixture.txt")!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split(' ', limit = 2).let { (ms, hex) -> ms.toLong() to hex.hexToBytes() } }
                .toList()
        }
        fun decodeAll(): List<Pair<Long, RadarState>> {
            var clock = 0L
            val decoder = RadarV2Decoder(nowMs = { clock })
            return fixture.mapNotNull { (ms, bytes) ->
                clock = ms
                decoder.feed(bytes)?.let { ms to it }
            }
        }
        // The bus drops a snapshot equal to the one it holds, and the ledger
        // decides on it: so the pipeline sees three fewer than the ledger here,
        // and none of the three moves a cue.
        val decoded = decodeAll()
        assertEquals("repeated snapshots", 3, decoded.zipWithNext().count { (a, b) -> a.second == b.second })

        val ledger = mutableListOf<String>()
        val pipeline = buildPipeline(
            clog = { line ->
                Regex("^# alert ts=\\d+ event=(\\S+)").find(line)?.let { m ->
                    val ev = when (val name = m.groupValues[1]) {
                        "UrgentApproach" -> if ("urgent_path=moving" in line) "Urgent(moving)" else "Urgent(stationary)"
                        else -> name
                    }
                    ledger += "${testScheduler.currentTime} $ev"
                }
            },
            clockMono = { testScheduler.currentTime },
            // The ledger's wiring: 20 m, no turn data, the shipped urgent settings.
            overlayPrefsSnapshot = {
                prefs.snapshot().copy(
                    alertMaxDistanceM = 20,
                    turnAwareAlertsEnabled = false,
                    urgentLowSpeedEnabled = true,
                    urgentUnconfidentWaitEnabled = false,
                    urgentPassClearanceM = AlertDecider.DEFAULT_PASS_CLEARANCE_M,
                    closingSpeedCeilingMs = null,
                )
            },
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        for ((ms, state) in decoded) {
            advanceTo(ms)
            RadarStateBus.publish(state)
            runCurrent()
        }
        assertTrue("the replay spans many ticks", testScheduler.currentTime > 10 * BikeRadarService.DASHCAM_TICK_MS)
        assertEquals(CueLedgerReplayTest.DEFAULT_GOLDEN, ledger)
        job.cancel()
        job.join()
    }

    @Test
    fun beepAlertLogCarriesTheTierTrigger() = runTest {
        // Same audit contract for the awareness beeps. Tiers score on true
        // range while frame_closest_* stays the nearest car by along-axis
        // distance, so the two name different vehicles exactly in the
        // off-axis case worth reviewing after a ride. Here the ghost 9 m to
        // the side is nearer along the axis; the tier comes from the car
        // behind it.
        var mono = 1_000L
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(clog = { clogLines += it }, clockMono = { mono })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        val ghost = Vehicle(id = 4, distanceM = 5, speedMs = -1f, rangeXm = 9f)
        val real = Vehicle(id = 7, distanceM = 6, speedMs = -3f, rangeXm = 1f)
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 1_000L, vehicles = listOf(ghost, real), bikeSpeedMs = 5f),
        )
        runCurrent()
        mono = 1_100L
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 1_100L, vehicles = listOf(ghost, real), bikeSpeedMs = 5f),
        )
        runCurrent()
        val beepLine = clogLines.firstOrNull { it.contains("event=Beep") }
        assertTrue("expected a Beep alert line, got $clogLines", beepLine != null)
        assertTrue(
            "beep line must name the track the tier came from, got $beepLine",
            beepLine!!.contains("tier_tid=7") && beepLine.contains("tier_d=6"),
        )
        assertTrue(
            "and the nearest car must still be reported separately, got $beepLine",
            beepLine.contains("frame_closest_tid=4") && beepLine.contains("closing_mps=1.0"),
        )
        assertTrue(
            "the line carries the tier car's own speed and the rule that fired, got $beepLine",
            beepLine.contains("tier_closing_mps=3.0") && beepLine.contains("cue_rule=new_entry"),
        )
        job.cancel()
        job.join()
    }

    @Test
    fun pausingSilencesTheAlertsAndResumingReannouncesTheCarBehind() = runTest {
        // While paused the decider is not consulted at all, and its state is
        // cleared, so resuming describes the road as it is rather than staying
        // latched on what was heard before.
        var mono = 1_000L
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(clog = { clogLines += it }, clockMono = { mono })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        val car = Vehicle(id = 7, distanceM = 6, speedMs = -3f, rangeXm = 1f)
        val fastFar = Vehicle(id = 9, distanceM = 15, speedMs = -8f, rangeXm = -1f)
        fun frame(t: Long, vehicles: List<Vehicle>, bikeSpeedMs: Float) {
            mono = t
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = t, vehicles = vehicles, bikeSpeedMs = bikeSpeedMs))
            runCurrent()
        }
        fun alerts() = clogLines.count { it.startsWith("# alert") }
        fun beeps() = clogLines.count { it.contains("event=Beep") }
        try {
            frame(1_000L, listOf(car), 5f)
            frame(1_100L, listOf(car), 5f)
            assertEquals("the car is announced: $clogLines", 1, beeps())

            // Stopped, with a fast closer arriving: unpaused this is the
            // imminent-impact cue within a few frames.
            prefs.pausedUntilEpochMs = Long.MAX_VALUE
            val before = alerts()
            (2..20).forEach { frame(1_000L + it * 100L, listOf(car, fastFar), 0f) }
            assertEquals("nothing is said while paused: $clogLines", before, alerts())

            prefs.pausedUntilEpochMs = 0L
            frame(3_100L, listOf(car), 5f)
            frame(3_200L, listOf(car), 5f)
            assertEquals("resuming announces the car still there: $clogLines", 2, beeps())
        } finally {
            prefs.pausedUntilEpochMs = 0L
            job.cancel()
            job.join()
        }
    }

    @Test
    fun theFramesAPauseSilencesSoundTheUrgentCueUnpaused() = runTest {
        // The positive control for the pause test above: without it, "nothing
        // is said while paused" would also pass on frames that say nothing.
        var mono = 1_000L
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(clog = { clogLines += it }, clockMono = { mono })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        val car = Vehicle(id = 7, distanceM = 6, speedMs = -3f, rangeXm = 1f)
        val fastFar = Vehicle(id = 9, distanceM = 15, speedMs = -8f, rangeXm = -1f)
        fun frame(t: Long, vehicles: List<Vehicle>, bikeSpeedMs: Float) {
            mono = t
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = t, vehicles = vehicles, bikeSpeedMs = bikeSpeedMs))
            runCurrent()
        }
        frame(1_000L, listOf(car), 5f)
        frame(1_100L, listOf(car), 5f)
        (2..20).forEach { frame(1_000L + it * 100L, listOf(car, fastFar), 0f) }
        assertTrue(clogLines.toString(), clogLines.any { it.contains("event=UrgentApproach") })
        job.cancel()
        job.join()
    }

    // ── turn-aware flag gating (glue) ────────────────────────────────────
    //
    // The KDoc on OverlayPipeline.turnState promises a mid-ride toggle-off
    // takes effect immediately (per-frame flag read), and the sensor is
    // only started when the flag is on at session start. Pin both gates -
    // the decider- and UI-level tests cannot see this wiring.

    @Test
    fun turnSensorNotStartedAndStateNotConsultedWhenFlagOff() = runTest {
        // Turn-aware defaults to on; disable explicitly to exercise the
        // flag-off path.
        prefs.turnAwareAlertsEnabled = false
        var starts = 0
        var stateReads = 0
        val pipeline = buildPipeline(
            turnState = {
                stateReads++
                TurnStateDecider.State.TURNING
            },
            turnSensorStart = { starts++ },
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 100L, vehicles = emptyList(), bikeSpeedMs = 5f),
        )
        runCurrent()
        assertEquals(0, starts)
        assertEquals(0, stateReads)
        job.cancel()
        job.join()
    }

    @Test
    fun turnSensorStartedAndStateConsultedWhenFlagOn() = runTest {
        prefs.turnAwareAlertsEnabled = true
        var starts = 0
        var stops = 0
        var stateReads = 0
        val pipeline = buildPipeline(
            turnState = {
                stateReads++
                TurnStateDecider.State.IDLE
            },
            turnSensorStart = { starts++ },
            turnSensorStop = { stops++ },
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 100L, vehicles = emptyList(), bikeSpeedMs = 5f),
        )
        runCurrent()
        assertEquals(1, starts)
        assertTrue("turnState must be consulted per frame when the flag is on", stateReads >= 1)
        // Toggle off mid-session: the per-frame gate stops consulting the
        // sensor immediately, before any reconnect.
        val readsAtToggle = stateReads
        prefs.turnAwareAlertsEnabled = false
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 200L, vehicles = emptyList(), bikeSpeedMs = 5f),
        )
        runCurrent()
        assertEquals(readsAtToggle, stateReads)
        job.cancel()
        job.join()
        // The session teardown always stops the sensor.
        assertEquals(1, stops)
    }

    @Test
    fun theCornerSettingAlsoGovernsTheCloseBehindFilter() = runTest {
        // A car first seen 8 m behind, closing at 3 m/s, while the sensor
        // says TURNING. With the setting on, the filter for cars first seen
        // close holds its beep, because a turn can fake that much closing.
        // Off, the decider sees no turn and the same frames beep.
        val on = closeBehindWhileTurning(settingOn = true)
        assertEquals(on.toString(), 0, on.count { it.contains("event=Beep") })
        assertTrue(on.toString(), on.any { it.startsWith("# gate suppress tid=5") && it.contains("turn=TURNING") })
        val off = closeBehindWhileTurning(settingOn = false)
        assertEquals(off.toString(), 1, off.count { it.contains("event=Beep") })
        assertEquals(off.toString(), 1, off.count { it.contains("event=Beep(2)") })
    }

    @Test
    fun theCornerSettingAlsoGovernsWhichCarsFirstSeenCloseArePassed() = runTest {
        // A car first seen 3 m behind, closing at 3 m/s while the sensor says
        // TURNING, then lost beside a moving rider. The ride figure's filter
        // sees the turn as the decider's does, so with the setting on it is
        // not a pass, and off it is.
        assertEquals(0, passedAfterCloseBehindWhileTurning(settingOn = true))
        assertEquals(1, passedAfterCloseBehindWhileTurning(settingOn = false))
    }

    @Test
    fun aPauseLeavesTheTurnWithTheRideFiguresFilter() = runTest {
        // The beeps are not decided while paused, but the ride figure is kept,
        // and its filter still sees the turn.
        prefs.pausedUntilEpochMs = Long.MAX_VALUE
        try {
            assertEquals(0, passedAfterCloseBehindWhileTurning(settingOn = true))
            assertEquals(1, passedAfterCloseBehindWhileTurning(settingOn = false))
        } finally {
            prefs.pausedUntilEpochMs = 0L
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.passedAfterCloseBehindWhileTurning(settingOn: Boolean): Int? {
        prefs.turnAwareAlertsEnabled = settingOn
        var mono = 1_000L
        val stats = RideStatsAccumulator(monoMsProvider = { mono })
        val pipeline = buildPipeline(
            clockMono = { mono },
            turnState = { TurnStateDecider.State.TURNING },
            rideStats = { stats },
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        repeat(5) { i ->
            mono = 1_000L + i * 100L
            val car = Vehicle(
                id = 5,
                distanceM = 3 - i % 2,
                speedMs = -3f,
                bornDistanceM = 3,
                bornInformative = true,
                bornAtMs = 1_000L,
                heardAtMs = mono,
            )
            RadarStateBus.publish(
                RadarState(source = DataSource.V2, timestamp = mono, vehicles = listOf(car), bikeSpeedMs = 5f),
            )
            runCurrent()
        }
        mono = 1_500L
        RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = mono, vehicles = emptyList(), bikeSpeedMs = 5f))
        runCurrent()
        job.cancel()
        job.join()
        RadarStateBus.clear()
        return stats.snapshot().vehiclesPassed
    }

    private suspend fun kotlinx.coroutines.test.TestScope.closeBehindWhileTurning(settingOn: Boolean): List<String> {
        prefs.turnAwareAlertsEnabled = settingOn
        var mono = 1_000L
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(
            clog = { clogLines += it },
            clockMono = { mono },
            turnState = { TurnStateDecider.State.TURNING },
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        val car = Vehicle(id = 5, distanceM = 8, speedMs = -3f, bornDistanceM = 8, bornInformative = true, bornAtMs = 1_000L)
        repeat(5) { i ->
            mono = 1_000L + i * 100L
            RadarStateBus.publish(
                RadarState(source = DataSource.V2, timestamp = mono, vehicles = listOf(car), bikeSpeedMs = 5f),
            )
            runCurrent()
        }
        job.cancel()
        job.join()
        RadarStateBus.clear()
        return clogLines
    }

    // ── eBike data on the alert path (real coordinator, controlled clock) ──
    //
    // Each aStale* case runs the same frames twice: once with Flow still
    // streaming, where the eBike reading must win, and once with Flow gone
    // quiet, where the radar's own speed must. The first half is what makes
    // the second mean anything - without it a pipeline ignoring the eBike
    // entirely passes too.

    private class MonoClock(var ms: Long)

    private companion object {
        const val WALL_BASE_MS = 1_790_000_000_000L
    }

    private suspend fun kotlinx.coroutines.test.TestScope.driveWithEBike(
        clock: MonoClock,
        coord: EBikeSnapshotCoordinator,
        radarBikeSpeedMs: Float?,
        frames: List<Pair<Long, List<Vehicle>>>,
        stillStreaming: LiveDataSnapshot?,
    ): List<String> {
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(
            clog = { clogLines += it },
            clockMono = { clock.ms },
            ebike = coord,
        )
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        for ((t, vehicles) in frames) {
            clock.ms = t
            stillStreaming?.let { coord.onSnapshot(it) }
            // A frame's own timestamp is wall clock, as the decoder stamps it
            // live; it must not be what the snapshot is aged against.
            RadarStateBus.publish(
                RadarState(source = DataSource.V2, timestamp = WALL_BASE_MS + t, vehicles = vehicles, bikeSpeedMs = radarBikeSpeedMs),
            )
            runCurrent()
        }
        job.cancel()
        job.join()
        RadarStateBus.clear()
        return clogLines
    }

    private fun coordinator(clock: MonoClock) = EBikeSnapshotCoordinator(
        clock = { clock.ms },
        clog = {},
        publishRideEdge = { _, _ -> },
        nowIso = { "" },
    )

    @Test
    fun aStaleEBikeSnapshotDoesNotMuteTheBeeps() = runTest {
        // The rider stopped with Flow streaming "not driving", then Flow went
        // quiet and they rode off. The radar says 5 m/s. A car comes up.
        val car = Vehicle(id = 7, distanceM = 6, speedMs = -3f, rangeXm = 1f)
        val frames = listOf(5_000L to emptyList(), 7_500L to listOf(car), 7_600L to listOf(car))
        val stopped = LiveDataSnapshot(bikeNotDriving = true)

        val streaming = MonoClock(1_000L)
        val live = driveWithEBike(streaming, coordinator(streaming).also { it.onSnapshot(stopped) }, 5f, frames, stillStreaming = stopped)
        assertEquals("a live 'not driving' still mutes: $live", 0, live.count { it.contains("event=Beep") })

        val quiet = MonoClock(1_000L)
        val stale = driveWithEBike(quiet, coordinator(quiet).also { it.onSnapshot(stopped) }, 5f, frames, stillStreaming = null)
        assertEquals("a six-second-old one must not: $stale", 1, stale.count { it.contains("event=Beep") })
    }

    @Test
    fun aStaleEBikeOnARadarWithNoSpeedLeavesNoUrgentPath() = runTest {
        // Deliberate, not a gap: with Flow quiet and a radar that reports no
        // rider speed there is no speed at all, which is the radar-only rider
        // on such a radar. Believing the stale "not driving" instead would
        // keep the stationary urgent path open, and also mute every beep once
        // the rider rides off, which is the defect this age check removes.
        val slowNear = Vehicle(id = 3, distanceM = 8, speedMs = -1f, rangeXm = 1f)
        val fastFar = Vehicle(id = 9, distanceM = 15, speedMs = -8f, rangeXm = -1f)
        val frames = listOf(1_000L to emptyList(), 3_500L to listOf(slowNear, fastFar), 3_600L to listOf(slowNear, fastFar))
        val stopped = LiveDataSnapshot(speedRaw = 0, bikeNotDriving = true)

        val streaming = MonoClock(0L)
        val live = driveWithEBike(streaming, coordinator(streaming).also { it.onSnapshot(stopped) }, null, frames, stillStreaming = stopped)
        assertEquals("a live 'not driving' arms it: $live", 1, live.count { it.contains("event=UrgentApproach") })

        val quiet = MonoClock(0L)
        val stale = driveWithEBike(quiet, coordinator(quiet).also { it.onSnapshot(stopped) }, null, frames, stillStreaming = null)
        assertEquals("a stale one leaves no speed and no urgent path: $stale", 0, stale.count { it.contains("event=UrgentApproach") })
    }

    @Test
    fun aStaleEBikeSpeedDoesNotBlockTheUrgentCue() = runTest {
        // Flow last said 18 km/h, above the moving urgent path's ceiling, then
        // went quiet. The radar says the rider is stopped and a fast car
        // closes: the stationary urgent path must open on the radar's word.
        val slowNear = Vehicle(id = 3, distanceM = 8, speedMs = -1f, rangeXm = 1f)
        val fastFar = Vehicle(id = 9, distanceM = 15, speedMs = -8f, rangeXm = -1f)
        val frames = listOf(1_000L to emptyList(), 3_500L to listOf(slowNear, fastFar), 3_600L to listOf(slowNear, fastFar))
        val moving = LiveDataSnapshot(speedRaw = 1800)

        val streaming = MonoClock(0L)
        val live = driveWithEBike(streaming, coordinator(streaming).also { it.onSnapshot(moving) }, 0f, frames, stillStreaming = moving)
        assertEquals("a live 18 km/h still keeps it shut: $live", 0, live.count { it.contains("event=UrgentApproach") })

        val quiet = MonoClock(0L)
        val stale = driveWithEBike(quiet, coordinator(quiet).also { it.onSnapshot(moving) }, 0f, frames, stillStreaming = null)
        assertEquals("a stale one must not: $stale", 1, stale.count { it.contains("event=UrgentApproach") })
    }

    @Test
    fun hardPedallingDoesNotKeepTheUrgentCueShut() = runTest {
        // Pins an absence, so instead of a quiet half it runs the same frames
        // with and without a long spell of hard pedalling: a stopped rider,
        // Flow streaming 300 W for over 30 s, and an 8 m/s closer. Rider power
        // has no say in whether the rider is stationary, so the stationary
        // urgent path opens on the radar's zero speed and every alert matches.
        val slowNear = Vehicle(id = 3, distanceM = 8, speedMs = -1f, rangeXm = 1f)
        val fastFar = Vehicle(id = 9, distanceM = 15, speedMs = -8f, rangeXm = -1f)
        val frames = listOf(31_000L to emptyList(), 33_500L to listOf(slowNear, fastFar), 33_600L to listOf(slowNear, fastFar))
        suspend fun alertsWith(riderPower: Int?): List<String> {
            val snap = LiveDataSnapshot(riderPower = riderPower)
            val clock = MonoClock(0L)
            val coord = coordinator(clock).also {
                it.onSnapshot(snap)
                clock.ms = 30_000L
                it.onSnapshot(snap)
            }
            return driveWithEBike(clock, coord, 0f, frames, stillStreaming = snap)
                .filter { it.startsWith("# alert") }
                .map { it.replace(Regex("ts=\\d+ "), "") }
        }
        val hard = alertsWith(300)
        assertEquals("the urgent sounds: $hard", 1, hard.count { it.contains("event=UrgentApproach") })
        assertEquals("rider power changes no alert", alertsWith(null), hard)
    }

    /** Drive one arming overtake (4 closing frames then track-drop) into
     *  [RadarStateBus] and pump the test scheduler. Mirrors the geometry of
     *  [closePassCountingWorksWithoutHomeAssistant]: lateralPos 0.25 *
     *  LATERAL_FULL_M 3.0 = 0.75 m (< 1.0 m emit), -8 m/s closing, rider
     *  moving, >= minFramesToArm frames, and drawing level so the clearance
     *  is measured alongside. rangeXmRaw is the sensor's own reading: left at
     *  0 it means "no reading" and the detector skips the frame. */
    private fun kotlinx.coroutines.test.TestScope.driveOneOvertake() {
        val base = System.currentTimeMillis()
        repeat(4) { i ->
            RadarStateBus.publish(
                RadarState(
                    source = DataSource.V2,
                    timestamp = base + i * 100L,
                    vehicles = listOf(
                        Vehicle(id = 7, distanceM = listOf(20, 14, 3, 2)[i], speedMs = -8f, lateralPos = 0.25f, rangeXmRaw = 0.75f),
                    ),
                    bikeSpeedMs = 5f,
                ),
            )
            runCurrent()
        }
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = base + 500L, vehicles = emptyList(), bikeSpeedMs = 5f),
        )
    }

    /** The ride's tightest clearance after a car that closed at 5.5 m/s draws
     *  level 0.9 m away, under the rider's close-pass closing floor [floorMs]. */
    private suspend fun kotlinx.coroutines.test.TestScope.clearanceUnderFloor(floorMs: Int): Float? {
        prefs.closePassClosingSpeedFloorMs = floorMs
        prefs.closePassLoggingEnabled = false
        val stats = RideStatsAccumulator()
        val pipeline = buildPipeline(rideStats = { stats })
        val job = pipeline.attach(this, "TestRadar")
        try {
            runCurrent()
            val frames = listOf(
                listOf(Vehicle(id = 7, distanceM = 20, speedMs = -5.5f, lateralPos = 0.8f, rangeXmRaw = 2.4f, bornAtMs = 1L)),
                listOf(Vehicle(id = 7, distanceM = 2, speedMs = -1f, lateralPos = 0.3f, rangeXmRaw = 0.9f, bornAtMs = 1L)),
                // The car's track ends, which is when its clearance counts.
                emptyList(),
            )
            frames.forEachIndexed { i, vehicles ->
                RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = 100L + i, vehicles = vehicles, bikeSpeedMs = 5f))
                runCurrent()
            }
        } finally {
            job.cancel()
            job.join()
            RadarStateBus.clear()
        }
        return stats.snapshot().minLateralClearanceM
    }

    @Test
    fun theRidersClosingFloorReachesTheRideClearance() = runTest {
        val original = prefs.closePassClosingSpeedFloorMs
        try {
            assertEquals("a 5 m/s floor admits it", 0.9f, clearanceUnderFloor(5)!!, 0.001f)
            assertEquals("a 6 m/s floor does not", null, clearanceUnderFloor(6))
        } finally {
            prefs.closePassClosingSpeedFloorMs = original
        }
    }

    /** Whether a ride fed [frame] with counting [on] is marked counted. */
    private suspend fun kotlinx.coroutines.test.TestScope.countedAfter(on: Boolean, frame: RadarState): Boolean {
        prefs.closePassLoggingEnabled = on
        val stats = RideStatsAccumulator()
        val job = buildPipeline(rideStats = { stats }).attach(this, "TestRadar")
        try {
            runCurrent()
            RadarStateBus.publish(frame)
            runCurrent()
        } finally {
            job.cancel()
            job.join()
            RadarStateBus.clear()
            prefs.closePassLoggingEnabled = false
        }
        return stats.snapshot().closePassesCounted
    }

    @Test
    fun theRideRecordsWhetherClosePassesWereCounted() = runTest {
        assertEquals("counting off", false, countedAfter(on = false, frame = liveFrame(100L)))
        assertEquals("counting on", true, countedAfter(on = true, frame = liveFrame(100L)))
    }

    @Test
    fun aRangeOnlyRideIsNotCounted() = runTest {
        // The detector cannot count without a lateral channel or the rider's
        // speed, so neither frame may mark the ride as counted.
        val rangeOnly = RadarState(source = DataSource.V1, timestamp = 100L, vehicles = emptyList(), bikeSpeedMs = 5f)
        assertEquals("no lateral", false, countedAfter(on = true, frame = rangeOnly))
        assertEquals("no rider speed", false, countedAfter(on = true, frame = liveFrame(100L).copy(bikeSpeedMs = null)))
    }

    @Test
    fun aCarCutOffByARadarDropIsNotCountedAfterTheReconnect() = runTest {
        // The frames clearanceUnderFloor(5) counts at 0.9 m, but the link
        // drops before the track ends and the next connection never sees it.
        val original = prefs.closePassClosingSpeedFloorMs
        prefs.closePassClosingSpeedFloorMs = 5
        prefs.closePassLoggingEnabled = false
        val stats = RideStatsAccumulator()
        val pipeline = buildPipeline(rideStats = { stats })
        try {
            val first = pipeline.attach(this, "TestRadar")
            runCurrent()
            listOf(
                Vehicle(id = 7, distanceM = 20, speedMs = -5.5f, lateralPos = 0.8f, rangeXmRaw = 2.4f, bornAtMs = 1L),
                Vehicle(id = 7, distanceM = 2, speedMs = -1f, lateralPos = 0.3f, rangeXmRaw = 0.9f, bornAtMs = 1L),
            ).forEachIndexed { i, v ->
                RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = 100L + i, vehicles = listOf(v), bikeSpeedMs = 5f))
                runCurrent()
            }
            first.cancel()
            first.join()
            RadarStateBus.clear()

            val second = pipeline.attach(this, "TestRadar")
            runCurrent()
            RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = 200L, vehicles = emptyList(), bikeSpeedMs = 5f))
            runCurrent()
            second.cancel()
            second.join()
            assertEquals("a pass cut off by the drop is not counted", null, stats.snapshot().minLateralClearanceM)
        } finally {
            prefs.closePassClosingSpeedFloorMs = original
            RadarStateBus.clear()
        }
    }

    @Test
    fun aFastApproachFromFarBackLeavesTheP90AtThePeak() = runTest {
        // 20 m/s beyond 40 m, 8 m/s inside it: the event's closing speed and
        // the ride's peak both read 28 km/h, so the p90 is not above the peak.
        prefs.closePassLoggingEnabled = true
        val stats = RideStatsAccumulator()
        val pipeline = buildPipeline(rideStats = { stats })
        try {
            val job = pipeline.attach(this, "TestRadar")
            runCurrent()
            listOf(
                listOf(Vehicle(id = 7, distanceM = 60, speedMs = -20f, lateralPos = 0.4f, rangeXmRaw = 1.2f, bornAtMs = 1L)),
                listOf(Vehicle(id = 7, distanceM = 50, speedMs = -20f, lateralPos = 0.4f, rangeXmRaw = 1.2f, bornAtMs = 1L)),
                listOf(Vehicle(id = 7, distanceM = 30, speedMs = -8f, lateralPos = 0.4f, rangeXmRaw = 1.2f, bornAtMs = 1L)),
                listOf(Vehicle(id = 7, distanceM = 20, speedMs = -8f, lateralPos = 0.4f, rangeXmRaw = 1.2f, bornAtMs = 1L)),
                listOf(Vehicle(id = 7, distanceM = 2, speedMs = -1f, lateralPos = 0.2f, rangeXmRaw = 0.6f, bornAtMs = 1L)),
                emptyList(),
            ).forEachIndexed { i, vehicles ->
                RadarStateBus.publish(RadarState(source = DataSource.V2, timestamp = 100L + i, vehicles = vehicles, bikeSpeedMs = 5f))
                runCurrent()
            }
            job.cancel()
            job.join()
            val s = stats.snapshot()
            assertEquals("one close pass", 1, s.closePassCount)
            assertEquals("a ride with a counted pass reads as counted", true, s.closePassesCounted)
            assertEquals("the ride's peak", 28, s.peakClosingKmh)
            assertEquals("the close passes' p90", 28, s.closingSpeedP90Kmh)
        } finally {
            prefs.closePassLoggingEnabled = false
            RadarStateBus.clear()
        }
    }

    /** HaClient double recording close-pass publish attempts. `configured`
     *  drives [HaClient.isConfigured] via non-blank constructor args; the
     *  overridden publishers record and skip the real MQTT path. */
    private class RecordingHaClient(configured: Boolean) : HaClient(if (configured) "http://ha.test" else "", if (configured) "tok" else "") {
        val discoveryCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val eventCalls = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun publishClosePassDiscovery(slug: String, deviceName: String): Boolean {
            discoveryCalls.incrementAndGet()
            return true
        }

        override suspend fun publishClosePassEvent(slug: String, eventJson: org.json.JSONObject): Boolean {
            eventCalls.incrementAndGet()
            return true
        }
    }

    @Test
    fun closePassPublishesToHaWhenConfigured() = runTest {
        // The other half of the un-gating: an HA-configured user must still get
        // the discovery + per-event publishes. radarSlug must resolve, so feed
        // a MAC + macToSlug mapping.
        prefs.closePassLoggingEnabled = true
        val ha = RecordingHaClient(configured = true)
        try {
            val pipeline = buildPipeline(
                ha = { ha },
                currentRadarMac = { "AA:BB:CC:DD:EE:FF" },
                macToSlug = { mapOf("AA:BB:CC:DD:EE:FF" to "testradar") },
            )
            val job = pipeline.attach(this, "TestRadar")
            runCurrent()
            driveOneOvertake()
            runCurrent()
            assertEquals(
                "configured HA must get the close-pass event publish",
                1,
                ha.eventCalls.get(),
            )
            assertTrue("discovery must publish once HA is configured", ha.discoveryCalls.get() >= 1)
            job.cancel()
            job.join()
        } finally {
            prefs.closePassLoggingEnabled = false
        }
    }

    @Test
    fun closePassNeverPublishesWhenHaUnconfigured() = runTest {
        // The detection-runs-locally half must NOT leak to HA: a blank client
        // gets zero publish attempts even though the count increments.
        prefs.closePassLoggingEnabled = true
        val ha = RecordingHaClient(configured = false)
        try {
            val pipeline = buildPipeline(
                ha = { ha },
                currentRadarMac = { "AA:BB:CC:DD:EE:FF" },
                macToSlug = { mapOf("AA:BB:CC:DD:EE:FF" to "testradar") },
            )
            val job = pipeline.attach(this, "TestRadar")
            runCurrent()
            driveOneOvertake()
            // Pump to the point the count would have registered, so a publish
            // (if it were going to happen) would have been attempted too.
            withTimeoutOrNull(2_000) {
                while (ClosePassStateBus.sessionCount.value == 0) {
                    runCurrent()
                    kotlinx.coroutines.delay(10)
                }
                true
            }
            runCurrent()
            assertEquals("no discovery publish without HA", 0, ha.discoveryCalls.get())
            assertEquals("no event publish without HA", 0, ha.eventCalls.get())
            job.cancel()
            job.join()
        } finally {
            prefs.closePassLoggingEnabled = false
        }
    }

    /** Test double that owns view-creation + tracks attach/detach calls. */
    private class FakeOverlayHost(private val ctx: Context) : OverlayHost {
        var attachCount = 0
        var detachCount = 0
        var configChangedCount = 0
        var canDraw = true
        var attachResult: () -> Throwable? = { null }
        override fun createView(): RadarOverlayView = RadarOverlayView(ctx)
        override fun canDrawOverlays(): Boolean = canDraw
        override fun attach(view: RadarOverlayView): Throwable? {
            val failed = attachResult()
            if (failed == null) attachCount++
            return failed
        }
        override fun detach(view: RadarOverlayView) {
            detachCount++
        }
        override fun onConfigurationChanged() {
            configChangedCount++
        }
    }

    // Helpers re-imported here so the test file is self-contained.
    private fun assertEquals(expected: Any?, actual: Any?) = org.junit.Assert.assertEquals(expected, actual)
    private fun assertEquals(message: String, expected: Any?, actual: Any?) = org.junit.Assert.assertEquals(message, expected, actual)
    private fun assertEquals(message: String, expected: Float, actual: Float, delta: Float) = org.junit.Assert.assertEquals(message, expected, actual, delta)
    private fun assertTrue(message: String, condition: Boolean) = org.junit.Assert.assertTrue(message, condition)
    private fun assertFalse(message: String, condition: Boolean) = org.junit.Assert.assertFalse(message, condition)
}
