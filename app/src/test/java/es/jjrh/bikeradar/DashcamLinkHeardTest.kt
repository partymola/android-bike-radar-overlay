// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.DashcamProbeHarness.Companion.CAM
import es.jjrh.bikeradar.DashcamProbeHarness.Companion.DROP_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The dashcam walk-away alarm with the camera's light link established (the
 * Dashcam light's auto-switch on): the camera is heard while that link is up,
 * whatever its battery reads do, and as of its end once it ends. A switch-off
 * outranks it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashcamLinkHeardTest {

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

    @Test
    fun aCameraWhoseLinkStaysUpSoundsEveryFiveMinutesPastTheMonitoringWindow() = runTest {
        // No read ever answers: the link is all that hears it. The probe's
        // window closing at 15 min does not end it.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.coordinator.markDashcamHeard(CAM)
        h.runUntil(DROP_MS + 1_300_000L)
        assertEquals(
            listOf(16_000L, 316_000L, 616_000L, 916_000L, 1_216_000L).map { DROP_MS + it },
            h.postTimes,
        )
        assertEquals(0, h.stops)
    }

    @Test
    fun anotherCamerasLinkIsNotHeard() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.coordinator.markDashcamHeard("BB:BB:BB:BB:BB:02")
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.posts)
    }

    @Test
    fun theSelectedCameraInAnotherCaseIsStillHeard() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.coordinator.markDashcamHeard(CAM.lowercase())
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(1, h.posts)
    }

    @Test
    fun aLinkThatEndsLeavesTheCameraHeardAsOfThen() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.coordinator.markDashcamHeard(CAM)
        h.runUntil(DROP_MS + 30_000L)
        h.coordinator.markDashcamLinkDown()
        h.runUntil(DROP_MS + 50_000L)
        assertEquals(0, h.stops)
        h.runUntil(DROP_MS + 52_000L)
        assertEquals(1, h.stops)
        assertEquals("walk-away alarm stopped: dashcam no longer heard", h.journal.last())
    }

    @Test
    fun aLinkThatEndsGivesTheProbeTheWholeWindow() = runTest {
        // A link lost while the camera stays on: the next read answers by the
        // timeout, inside the 20 s.
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { it >= DROP_MS + 30_000L })
        h.coordinator.markDashcamHeard(CAM)
        h.runUntil(DROP_MS + 30_000L)
        h.coordinator.markDashcamLinkDown()
        h.runUntil(DROP_MS + 120_000L)
        assertEquals(1, h.posts)
        assertEquals(0, h.stops)
    }

    @Test
    fun theLoopEndingEndsTheLinkToo() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.coordinator.markDashcamHeard(CAM)
        h.runUntil(DROP_MS + 30_000L)
        h.coordinator.markDashcamLinkGone()
        h.runUntil(DROP_MS + 52_000L)
        assertEquals(1, h.stops)
    }

    @Test
    fun aLinkThatHasEndedIsNotHeardAgainByTheLoopEnding() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.coordinator.markDashcamHeard(CAM)
        h.runUntil(DROP_MS + 30_000L)
        h.coordinator.markDashcamLinkDown()
        h.runUntil(DROP_MS + 40_000L)
        h.coordinator.markDashcamLinkGone()
        h.runUntil(DROP_MS + 52_000L)
        assertEquals(1, h.stops)
    }

    @Test
    fun aSwitchOffOutranksALinkThatHasNotReportedItsEnd() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L)
        h.coordinator.markDashcamHeard(CAM)
        h.runUntil(DROP_MS + 9_000L)
        h.coordinator.markDashcamSwitchedOff(CAM)
        h.runUntil(DROP_MS + 10_000L)
        assertFalse(h.coordinator.snapshot().walkAwayArmed)
        assertEquals(1, h.clogged("transition_reason=dashcam-switched-off"))
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.posts)
    }

    @Test
    fun anUpLinkSpendsNoReadsWhileArmed() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 100L)
        h.coordinator.markDashcamHeard(CAM)
        h.runUntil(DROP_MS + 60_000L)
        assertTrue(h.launches.none { it >= DROP_MS })
        assertEquals(1, h.posts)
    }
}
