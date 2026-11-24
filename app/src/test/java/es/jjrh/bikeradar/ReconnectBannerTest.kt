// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.RadarLinkVisualDecider.LinkVisual
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dead-radar banner as [ReconnectBanner] applies it on the main thread,
 * fed by the real [RadarLinkCoordinator]. The coordinator's posts are queued
 * in [posted] and applied in order, as the main thread applies them.
 */
@RunWith(RobolectricTestRunner::class)
class ReconnectBannerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private lateinit var coordinator: RadarLinkCoordinator
    private lateinit var host: FakeHost
    private lateinit var banner: ReconnectBanner
    private val posted = mutableListOf<LinkVisual>()
    private val streamingAtPost = mutableListOf<Boolean>()
    private val lines = mutableListOf<String>()
    private val journaled = mutableListOf<String>()
    private var streaming = false
    private var connectDuringTick: (() -> Unit)? = null
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
            setReconnectBanner = {
                posted += it
                streamingAtPost += coordinator.snapshot().radarStreaming
            },
            resolveDashcamSlug = { null },
            dashcamAnsweredAtMs = { null },
            eBikeSnapshot = { null },
            eBikeSnapshotAtMs = { 0L },
            eBikeRidingSinceMs = { null },
            hasLinkableRadar = { true },
            hasEBikeSignal = { false },
            // Read after the tick has taken its snapshot and before it posts.
            everSawTrack = {
                connectDuringTick?.invoke()
                connectDuringTick = null
                true
            },
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
        host = FakeHost(app)
        banner = ReconnectBanner(host, radarStreaming = { streaming }, clog = { lines += it }, journal = { journaled += it })
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun drain(): List<LinkVisual> {
        val batch = posted.toList()
        posted.clear()
        streaming = coordinator.snapshot().radarStreaming
        batch.forEach { banner.show(it) }
        return batch
    }

    @Test
    fun aDecisionFromBeforeTheFirstFrameDoesNotOutliveIt() {
        coordinator.markConnected()
        now = 1_060_000L
        coordinator.markDisconnected()
        now = 1_072_000L
        coordinator.evaluateRadarDrop(now)
        drain()
        assertEquals(1, host.attached.size)

        // The first frame lands while the tick is between its snapshot and
        // its post, so the stale decision is queued behind the frame's LIVE.
        now = 1_074_000L
        connectDuringTick = { coordinator.markConnected() }
        coordinator.evaluateRadarDrop(now)
        assertEquals(listOf(LinkVisual.LIVE, LinkVisual.RECONNECTING_PLAIN), drain())
        assertTrue(host.attached.isEmpty())
    }

    @Test
    fun theFirstFramePostsItsLiveOnlyOnceTheStreamIsRecorded() {
        // Otherwise a stale visual applied between the post and the record
        // would still read the radar as down.
        coordinator.markConnected()
        now = 1_060_000L
        coordinator.markDisconnected()
        streamingAtPost.clear()
        now = 1_074_000L
        coordinator.markConnected()
        assertEquals(listOf(true), streamingAtPost)
    }

    @Test
    fun noVisualShowsTheBannerWhileTheRadarStreams() {
        streaming = true
        for (v in listOf(LinkVisual.RECONNECTING_PLAIN, LinkVisual.RECONNECTING_UNLOCKED, LinkVisual.NO_RADAR)) {
            banner.show(v)
            assertTrue("$v was shown", host.attached.isEmpty())
        }
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aBannerUpWhenTheRadarStreamsIsTakenDownByTheNextVisual() {
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        assertEquals(1, host.attached.size)
        streaming = true
        banner.show(LinkVisual.RECONNECTING_UNLOCKED)
        assertTrue(host.attached.isEmpty())
        assertEquals(listOf("# reconnect_banner shown variant=RECONNECTING_PLAIN", "# reconnect_banner hidden"), lines)
    }

    @Test
    fun withTheRadarDownTheBannerShowsRetitlesAndHides() {
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        val shown = host.attached.single()
        assertEquals("Rear radar disconnected", shown.contentDescription?.toString())
        banner.show(LinkVisual.RECONNECTING_UNLOCKED)
        assertSame(shown, host.attached.single())
        assertEquals("Rear radar disconnected, but bike unlocked", shown.contentDescription?.toString())
        banner.show(LinkVisual.LIVE)
        assertTrue(host.attached.isEmpty())
        banner.show(LinkVisual.LIVE)
        assertEquals(listOf("# reconnect_banner shown variant=RECONNECTING_PLAIN", "# reconnect_banner hidden"), lines)
    }

    @Test
    fun aFailedAttachIsLoggedWhenItsKindChanges() {
        host.attachFailure = IllegalStateException("window gone")
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        host.canDraw = false
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        host.canDraw = true
        host.attachFailure = null
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        assertEquals("Rear radar disconnected", host.attached.single().contentDescription?.toString())
        assertEquals(
            listOf(
                "# reconnect_banner addView failed: java.lang.IllegalStateException: window gone",
                "# reconnect_banner: SYSTEM_ALERT_WINDOW not granted",
                "# reconnect_banner shown variant=RECONNECTING_PLAIN",
            ),
            lines,
        )
        // The journal is open when the capture log is not, and takes no
        // exception message.
        assertEquals(
            listOf(
                "reconnect banner not shown: IllegalStateException",
                "reconnect banner not shown: overlay permission not granted",
            ),
            journaled,
        )
    }

    @Test
    fun theSameFailureInTheNextDropIsLoggedAgain() {
        host.canDraw = false
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        banner.show(LinkVisual.LIVE)
        banner.show(LinkVisual.RECONNECTING_PLAIN)
        assertEquals(
            listOf(
                "# reconnect_banner: SYSTEM_ALERT_WINDOW not granted",
                "# reconnect_banner: SYSTEM_ALERT_WINDOW not granted",
            ),
            lines,
        )
        assertEquals(
            listOf(
                "reconnect banner not shown: overlay permission not granted",
                "reconnect banner not shown: overlay permission not granted",
            ),
            journaled,
        )
    }

    @Test
    fun withTheRadarDownEveryOtherVisualPutsTheBannerUpAndLiveTakesItDown() {
        val shownLines = mapOf(
            LinkVisual.RECONNECTING_PLAIN to "# reconnect_banner shown variant=RECONNECTING_PLAIN",
            LinkVisual.RECONNECTING_UNLOCKED to "# reconnect_banner shown variant=RECONNECTING_UNLOCKED",
            LinkVisual.NO_RADAR to "# reconnect_banner shown variant=NO_RADAR",
        )
        for ((v, shownLine) in shownLines) {
            lines.clear()
            banner.show(v)
            assertEquals("$v attached", 1, host.attached.size)
            banner.show(LinkVisual.LIVE)
            assertTrue("$v outlived LIVE", host.attached.isEmpty())
            assertEquals(listOf(shownLine, "# reconnect_banner hidden"), lines)
        }
    }

    private class FakeHost(private val ctx: Context) : OverlayHost {
        var canDraw = true
        var attachFailure: Throwable? = null
        val attached = mutableListOf<RadarOverlayView>()
        override fun createView() = RadarOverlayView(ctx)
        override fun canDrawOverlays() = canDraw
        override fun attach(view: RadarOverlayView): Throwable? = attachFailure ?: run {
            attached += view
            null
        }
        override fun detach(view: RadarOverlayView) {
            attached.remove(view)
        }
        override fun onConfigurationChanged() {}
    }
}
