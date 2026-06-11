// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.media.AudioManager
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

    private fun buildPipeline(): OverlayPipeline = OverlayPipeline(
        prefs = prefs,
        ha = { HaClient("", "") },
        beeper = beeper,
        overlayHost = fakeHost,
        phoneBattery = object : PhoneBatterySource {
            override fun readSnapshot(): PhoneBatteryReading? = null
        },
        rideStats = { RideStatsAccumulator() },
        overlayPrefsSnapshot = { prefs.snapshot() },
        ebikeSnapshot = { null },
        climbingNow = { false },
        currentRadarMac = { null },
        macToSlug = { emptyMap() },
        clog = {},
    )

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
