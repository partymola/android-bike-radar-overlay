// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
            rotationProvider = { android.view.Surface.ROTATION_0 },
            onCue = {},
        )
        fakeHost = FakeOverlayHost(context)
        // The pipeline subscribes to the process-wide RadarStateBus +
        // BatteryStateBus. Clear them so a previous test's residue does
        // not leak in as the initial value.
        RadarStateBus.clear()
        BatteryStateBus.clearForTest()
        ClosePassStateBus.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        beeper.release()
        RadarStateBus.clear()
        BatteryStateBus.clearForTest()
        ClosePassStateBus.reset()
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
            // the urban 1.5 m arm threshold and the 1.0 m emit threshold
            // (lateralPos 0.25 * LATERAL_FULL_M 3.0 = 0.75 m), rider
            // moving, >= minFramesToArm frames.
            val base = System.currentTimeMillis()
            repeat(4) { i ->
                RadarStateBus.publish(
                    RadarState(
                        source = DataSource.V2,
                        timestamp = base + i * 100L,
                        vehicles = listOf(
                            Vehicle(id = 7, distanceM = 20 - i * 3, speedMs = -8f, lateralPos = 0.25f),
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
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ): OverlayPipeline = OverlayPipeline(
        prefs = prefs,
        ha = ha,
        beeper = beeper,
        overlayHost = fakeHost,
        phoneBattery = object : PhoneBatterySource {
            override fun readSnapshot(): PhoneBatteryReading? = null
        },
        rideStats = { RideStatsAccumulator() },
        overlayPrefsSnapshot = { prefs.snapshot() },
        ebikeSnapshot = { null },
        climbingNow = { false },
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

    @Test
    fun urgentAlertLogCarriesTheTriggerVehicle() = runTest {
        // The capture-log audit contract: an UrgentApproach line must name
        // the vehicle that opened the gate (trigger_*), because
        // frame_closest_* records the nearest car - in the field often a
        // different, slower one, which made urgents unauditable.
        var mono = 1_000L
        val clogLines = mutableListOf<String>()
        val pipeline = buildPipeline(clog = { clogLines += it }, clockMono = { mono })
        val job = pipeline.attach(this, "TestRadar")
        runCurrent()
        // Confirm the stationary dwell with an empty frame, then age it out.
        RadarStateBus.publish(
            RadarState(source = DataSource.V2, timestamp = 1_000L, vehicles = emptyList(), bikeSpeedMs = 0f),
        )
        runCurrent()
        mono = 3_500L
        // A slow near car plus the fast far trigger; two frames build the
        // sustain, the second fires the urgent.
        val slowNear = Vehicle(id = 3, distanceM = 8, speedMs = -1f, rangeXm = 1f)
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
        assertTrue(
            "urgent line must carry the trigger vehicle, got $urgentLine",
            urgentLine!!.contains("trigger_tid=9") &&
                urgentLine.contains("trigger_d=15") &&
                urgentLine.contains("trigger_closing_mps=8.0") &&
                urgentLine.contains("trigger_rx=-1.0"),
        )
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
        val ghost = Vehicle(id = 4, distanceM = 5, speedMs = -3f, rangeXm = 9f)
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
            beepLine.contains("frame_closest_tid=4"),
        )
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

    /** Drive one arming overtake (4 closing frames then track-drop) into
     *  [RadarStateBus] and pump the test scheduler. Mirrors the geometry of
     *  [closePassCountingWorksWithoutHomeAssistant]: lateralPos 0.25 *
     *  LATERAL_FULL_M 3.0 = 0.75 m (< 1.0 m emit), -8 m/s closing, rider
     *  moving, >= minFramesToArm frames. */
    private fun kotlinx.coroutines.test.TestScope.driveOneOvertake() {
        val base = System.currentTimeMillis()
        repeat(4) { i ->
            RadarStateBus.publish(
                RadarState(
                    source = DataSource.V2,
                    timestamp = base + i * 100L,
                    vehicles = listOf(
                        Vehicle(id = 7, distanceM = 20 - i * 3, speedMs = -8f, lateralPos = 0.25f),
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
        override fun createView(): RadarOverlayView = RadarOverlayView(ctx)
        override fun canDrawOverlays(): Boolean = true
        override fun attach(view: RadarOverlayView): Throwable? {
            attachCount++
            return null
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
    private fun assertTrue(message: String, condition: Boolean) = org.junit.Assert.assertTrue(message, condition)
    private fun assertFalse(message: String, condition: Boolean) = org.junit.Assert.assertFalse(message, condition)
}
