// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.RadarLinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the radar surfaces say about the link, through the composition
 * they actually use: [RadarLinkStatus.isConnecting] feeding
 * [deviceLinkState]. Asserting the boolean alone would leave the precedence
 * untested, and precedence is the safety-adjacent half.
 *
 * Two properties. Decoded frames mean LIVE whatever the link flags say, so a
 * radar delivering targets is never described as still connecting. And
 * CONNECTING must never show for a radar that is neither linked nor recently
 * linked, which is the old always-"Not in range" lie inverted.
 */
class RadarLinkStatusTest {

    private fun link(
        fresh: Boolean,
        gattActive: Boolean,
        offSinceMs: Long?,
        nowMs: Long = 100_000L,
        linked: Boolean = true,
    ) = deviceLinkState(
        linked = linked,
        fresh = fresh,
        connecting = RadarLinkStatus.isConnecting(gattActive, offSinceMs, nowMs),
    )

    @Test fun freshDecodedFramesAreLiveWhateverTheLinkSays() {
        assertEquals(
            DeviceLinkState.LIVE,
            link(fresh = true, gattActive = false, offSinceMs = null),
        )
        assertEquals(
            DeviceLinkState.LIVE,
            link(fresh = true, gattActive = true, offSinceMs = 99_000L),
        )
    }

    @Test fun liveGattWithoutFramesYetIsConnecting() {
        assertEquals(
            DeviceLinkState.CONNECTING,
            link(fresh = false, gattActive = true, offSinceMs = null),
        )
    }

    @Test fun recentDropBridgesToConnecting() {
        // 4999 ms after the drop: still inside the abort loop's cadence.
        assertEquals(
            DeviceLinkState.CONNECTING,
            link(fresh = false, gattActive = false, offSinceMs = 95_001L),
        )
    }

    @Test fun exactlyAtTheWindowIsNoSignal() {
        // age == 5000 is out (strictly-less), so a genuinely-gone radar settles
        // within one screen tick rather than one tick plus a boundary frame.
        assertEquals(
            DeviceLinkState.NO_SIGNAL,
            link(fresh = false, gattActive = false, offSinceMs = 95_000L),
        )
    }

    @Test fun oldDropIsNoSignal() {
        assertEquals(
            DeviceLinkState.NO_SIGNAL,
            link(fresh = false, gattActive = false, offSinceMs = 10_000L),
        )
    }

    @Test fun aDropStampedSlightlyInTheFutureStillBridges() {
        // A composition can render with a clock read marginally older than the
        // coordinator's write; a negative age is "just dropped", not "ancient".
        assertEquals(
            DeviceLinkState.CONNECTING,
            link(fresh = false, gattActive = false, offSinceMs = 100_001L),
        )
    }

    @Test fun neverConnectedThisSessionIsNoSignal() {
        assertEquals(
            DeviceLinkState.NO_SIGNAL,
            link(fresh = false, gattActive = false, offSinceMs = null),
        )
    }

    @Test fun anUnbondedRadarIsNotPairedEvenMidConnect() {
        // Bonding outranks the link flags: without this a radar unpaired in
        // system settings while the service was still retrying would read
        // "Connecting…" about a device the app can no longer reach.
        assertEquals(
            DeviceLinkState.NOT_PAIRED,
            link(fresh = false, gattActive = true, offSinceMs = null, linked = false),
        )
    }

    // ── End ride ─────────────────────────────────────────────────────────────

    /** Literal threshold, not the production constant: asserting a constant
     *  against itself stays green when the constant is wrong. 10_000 is
     *  [es.jjrh.bikeradar.RadarLinkCoordinator.RADAR_DROP_VISUAL_THRESHOLD_MS]. */
    private fun offer(downForMs: Long?, everLive: Boolean = true, ended: Boolean = false) = RadarLinkStatus.canEndRide(
        radarEverLive = everLive,
        downForMs = downForMs,
        alreadyEnded = ended,
        visualThresholdMs = 10_000L,
    )

    @Test fun endRideIsOfferedOnceTheBannerHasBeenUp() {
        assertTrue("down past the banner threshold is the whole case", offer(downForMs = 10_000L))
    }

    @Test fun endRideIsNotOfferedWhileTheRadarIsUp() {
        assertFalse(offer(downForMs = null))
    }

    @Test fun endRideIsNotOfferedDuringAnOrdinaryReconnect() {
        // The boundary, pinned from both sides. A routine mid-ride blip runs to
        // a corpus median of 8.4 s, and a control that suppresses a warning must
        // not appear during one. Without the floor this reads true at 1 ms.
        assertFalse("9999 ms is still a blip", offer(downForMs = 9_999L))
        assertTrue("10000 ms is the banner threshold", offer(downForMs = 10_000L))
    }

    @Test fun endRideIsNotOfferedInASessionThatNeverSawTheRadar() {
        // A bench session, or the app opened without the radar on. There is no
        // ride to end, so offering it would be noise.
        assertFalse(offer(downForMs = 60_000L, everLive = false))
    }

    @Test fun endRideIsNotOfferedTwiceForOneOffEpisode() {
        // The declaration is spent until the next radar connect re-arms it.
        assertFalse(offer(downForMs = 60_000L, ended = true))
    }
}
