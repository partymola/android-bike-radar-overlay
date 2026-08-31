// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.RadarLinkStatus
import org.junit.Assert.assertEquals
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
}
