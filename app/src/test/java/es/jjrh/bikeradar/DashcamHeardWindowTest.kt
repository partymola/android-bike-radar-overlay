// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.DashcamProbeHarness.Companion.DROP_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dashcam walk-away alarm against the probe's real schedule
 * ([DashcamProbeHarness]), with reads at the code's own limits rather than at
 * any measured read time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashcamHeardWindowTest {

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
    fun aCameraThatStopsAnsweringBeforeTheThresholdIsNeverAnnounced() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 30, readMs = 14_999L, answers = { false })
        h.runUntil(DROP_MS + 9_000L)
        h.cameraAnswersNow()
        h.runUntil(DROP_MS + 60_000L)
        assertEquals(0, h.posts)
    }

    @Test
    fun aCameraThatNeverAnswersGoesBlankAtTheStaleWindow() = runTest {
        val h = DashcamProbeHarness(this, app, thresholdSec = 15, readMs = 14_999L, answers = { false })
        h.ridingActivityMs = DROP_MS - 60_000L
        h.runUntil(DROP_MS + 20_000L)
        assertEquals(0, h.rideWakeLockReleases)
        h.runUntil(DROP_MS + 22_000L)
        assertFalse(h.coordinator.snapshot().walkAwayArmed)
        assertEquals(1, h.rideWakeLockReleases)
        assertEquals(1, h.clogged("transition_reason=dashcam-stale"))
        assertEquals(0, h.posts)
    }
}
