// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [RadarLinkState.connectingSinceMs] on the real [RadarLinkCoordinator]: when a
 * stretch of connecting with no data starts, what carries it across a failing
 * radar's pauses between attempts, and what ends it. The home card's
 * power-cycle advice counts from it, so a stretch that broke at every pause
 * would never reach the advice, and one that never broke would show it at
 * once on an ordinary connect hours later.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectingStretchTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private lateinit var coordinator: RadarLinkCoordinator
    private var now = 1_000_000L

    @Before
    fun setUp() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        prefs = Prefs(app)
        coordinator = RadarLinkCoordinator(
            clock = { now },
            prefs = prefs,
            postWalkAwayNotification = {},
            cancelWalkAwayNotification = {},
            startWalkAwayAlarm = {},
            stopWalkAwayAlarm = {},
            alertBeeper = { null },
            clog = {},
            journal = {},
            setReconnectBanner = {},
            resolveDashcamSlug = { null },
            eBikeSnapshot = { null },
            eBikeSnapshotAtMs = { 0L },
            eBikeRidingSinceMs = { null },
            hasLinkableRadar = { true },
            hasEBikeSignal = { false },
            everSawTrack = { false },
            postForgotToLock = {},
            cancelForgotToLock = {},
            cancelWalkAwaySnooze = {},
            clearDashcamBackoff = {},
            lastRidingActivityMs = { null },
            lastTrackActivityMs = { null },
            clearTrackActivity = {},
            forgetEBikeLock = {},
            wakeTick = {},
            acquireRideWakeLock = {},
            releaseRideWakeLock = {},
        )
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun open(t: Long) {
        now = t
        coordinator.markLinkOpen()
    }

    private fun close(t: Long) {
        now = t
        coordinator.markDisconnected()
    }

    private fun since() = coordinator.snapshot().connectingSinceMs

    @Test
    fun aStretchStartsAtTheFirstLinkOpen() {
        assertNull(since())
        open(1_000_000L)
        assertEquals(1_000_000L, since())
        // A second open while the link is still up is the same stretch.
        open(1_003_000L)
        assertEquals(1_000_000L, since())
    }

    @Test
    fun aFailingRadarsPausesDoNotBreakTheStretch() {
        // Closed at the instant of the abort, reopened after the longest
        // ordinary backoff.
        open(1_000_000L)
        close(1_002_000L)
        open(1_002_000L + 9_600L)
        assertEquals(1_000_000L, since())
    }

    @Test
    fun theStretchSurvivesTheLongestRetryGapAndNoLonger() {
        // Default idle interval 30 s: 30 s plus a fifth of jitter plus 30 s
        // for the attempt.
        open(1_000_000L)
        close(1_002_000L)
        open(1_002_000L + 66_000L)
        assertEquals(1_000_000L, since())
        close(1_070_000L)
        open(1_070_000L + 66_001L)
        assertEquals(1_136_001L, since())
    }

    @Test
    fun theGapFollowsTheRidersIdleInterval() {
        prefs.radarLongOfflineCapSec = 120
        open(1_000_000L)
        close(1_002_000L)
        open(1_002_000L + 174_000L)
        assertEquals(1_000_000L, since())
        close(1_180_000L)
        open(1_180_000L + 174_001L)
        assertEquals(1_354_001L, since())

        // Below the ordinary 8 s ceiling the ceiling still sets the gap.
        prefs.radarLongOfflineCapSec = 5
        close(1_400_000L)
        open(1_400_000L + 39_600L)
        assertEquals(1_354_001L, since())
        close(1_500_000L)
        open(1_500_000L + 39_601L)
        assertEquals(1_539_601L, since())
    }

    @Test
    fun attemptsThatFailBeforeTheLinkOpensDoNotBreakTheStretch() {
        // A radar that sometimes fails before discovery: those attempts end
        // with a disconnect and no open, each within the gap of the last, so
        // the opens around them are far apart and still one stretch.
        open(1_000_000L)
        close(1_002_000L)
        close(1_062_000L)
        close(1_122_000L)
        open(1_182_000L)
        assertEquals(1_000_000L, since())
        // One past the gap from the last attempt's end starts afresh.
        close(1_184_000L)
        open(1_184_000L + 66_001L)
        assertEquals(1_250_001L, since())
    }

    @Test
    fun aRadarBackAfterADropEndsTheStretch() {
        // The way a stretch ends in the field: the radar was down, so its
        // first frame takes the off-episode branch.
        open(1_000_000L)
        close(1_002_000L)
        open(1_005_000L)
        now = 1_007_000L
        coordinator.markConnected()
        assertNull(since())
        close(1_060_000L)
        open(1_061_000L)
        assertEquals(1_061_000L, since())
    }

    @Test
    fun theFirstFrameEndsTheStretch() {
        open(1_000_000L)
        now = 1_004_000L
        coordinator.markConnected()
        assertNull(since())
        // A drop and a reconnect start their own, even one second later.
        close(1_060_000L)
        open(1_061_000L)
        assertEquals(1_061_000L, since())
    }
}
