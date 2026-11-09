// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.DashcamProbeHarness.Companion.DROP_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashcam walk-away alarm keeps quiet for a drop the radar showed the
 * rider riding up to: within 30 s of a frame above walking pace, unless the
 * bike read locked after that frame. The radar-left-on alarm's rule
 * (`RadarLeftOnAlarmTest.ridingJustBeforeTheDropSilencesIt`), on a camera
 * left on and answering every read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashcamRidingGateTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(PREFS_THROTTLE, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(DashcamProbeHarness.KNOWN_DEVICES, Context.MODE_PRIVATE).edit().clear().commit()
        BatteryStateBus.clearForTest()
    }

    private fun TestScope.harness() = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)

    @Test
    fun aDropWhileRidingDoesNotArmIt() = runTest {
        val h = harness()
        h.ridingActivityMs = DROP_MS - 29_999L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.posts)
        assertFalse(h.coordinator.snapshot().walkAwayArmed)
        assertFalse(h.walkAwayWakeLockHeld)
        assertEquals(
            1,
            h.clogged(
                "# walkaway state=BLANK transition_reason=radar-disconnected-while-riding " +
                    "ebike_locked=null ebike_age_ms=${DROP_MS} riding_age_ms=29999",
            ),
        )
        assertEquals(listOf("walk-away alarm not armed: the radar showed riding"), h.journal)
    }

    @Test
    fun aDropThirtySecondsAfterRidingArmsIt() = runTest {
        val h = harness()
        h.ridingActivityMs = DROP_MS - 30_000L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(listOf(DROP_MS + 16_000L), h.postTimes)
    }

    @Test
    fun anUnlockedEBikeSaysWhyItDidNotArm() = runTest {
        val h = harness()
        h.eBike = LiveDataSnapshot(systemLocked = false)
        h.eBikeAtMs = DROP_MS - 1_000L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.posts)
        assertEquals(listOf("walk-away alarm not armed: the eBike read unlocked"), h.journal)
    }

    @Test
    fun trafficJustBeforeTheDropStillArmsIt() = runTest {
        // A radar that reports distance only has no riding to show: the
        // dashcam alarm arms on its drop, as the copy says.
        val h = harness()
        h.trackActivityMs = DROP_MS - 5_000L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(1, h.clogged("# radar_drop_latch source=track-presence"))
        assertEquals(listOf(DROP_MS + 16_000L), h.postTimes)
    }

    @Test
    fun aLockReadAtTheRidingFramesInstantDoesNotLiftIt() = runTest {
        val h = harness()
        h.ridingActivityMs = DROP_MS - 10_000L
        h.eBike = LiveDataSnapshot(systemLocked = true)
        h.eBikeAtMs = DROP_MS - 10_000L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.posts)
    }

    @Test
    fun aDropWhileRidingTakesTheRideWakeLockOnce() = runTest {
        val h = harness()
        h.ridingActivityMs = DROP_MS - 10_000L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(1, h.rideWakeLockAcquires)
    }

    @Test
    fun aLockAfterTheRidingLetsItSound() = runTest {
        // Locked and walked straight off inside the riding window.
        val h = harness()
        h.ridingActivityMs = DROP_MS - 10_000L
        h.eBike = LiveDataSnapshot(systemLocked = true)
        h.eBikeAtMs = DROP_MS - 5_000L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(listOf(DROP_MS + 16_000L), h.postTimes)
    }

    @Test
    fun aLockReadBeforeTheRidingDoesNotLiftIt() = runTest {
        // An earlier stop's lock, its unlock missed, then ridden on.
        val h = harness()
        h.ridingActivityMs = DROP_MS - 10_000L
        h.eBike = LiveDataSnapshot(systemLocked = true)
        h.eBikeAtMs = DROP_MS - 20_000L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.posts)
    }

    @Test
    fun aDropWhileRidingKeepsTheRideWakeLockForTheDropCue() = runTest {
        // Never armed, so never BLANK: nothing here releases it.
        val h = harness()
        h.ridingActivityMs = DROP_MS - 10_000L
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.rideWakeLockReleases)
    }

    @Test
    fun theProbeKeepsItsRideCadenceAfterARidingDrop() = runTest {
        // Not armed, so no back-to-back reads.
        val h = harness()
        h.ridingActivityMs = DROP_MS - 10_000L
        h.runUntil(DROP_MS + 60_000L)
        val afterDrop = h.launches.count { it >= DROP_MS }
        assertTrue("launches=$afterDrop", afterDrop <= 3)
    }
}
