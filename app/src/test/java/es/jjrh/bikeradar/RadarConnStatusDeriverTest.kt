// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Settings radar card's tri-state. The safety-adjacent property is
 * precedence: a fresh battery reading means CONNECTED whatever the link flags
 * say, and CONNECTING must never show for a radar that is neither linked nor
 * recently linked - that is the old always-"Not in range" lie inverted.
 */
class RadarConnStatusDeriverTest {

    @Test fun freshDecodedFramesAreConnectedWhateverTheLinkSays() {
        assertEquals(
            RadarConnStatus.CONNECTED,
            RadarConnStatusDeriver.derive(dataFresh = true, gattActive = false, offSinceMs = null, nowMs = 100_000L),
        )
        assertEquals(
            RadarConnStatus.CONNECTED,
            RadarConnStatusDeriver.derive(dataFresh = true, gattActive = true, offSinceMs = 99_000L, nowMs = 100_000L),
        )
    }

    @Test fun liveGattWithoutFramesYetIsConnecting() {
        assertEquals(
            RadarConnStatus.CONNECTING,
            RadarConnStatusDeriver.derive(dataFresh = false, gattActive = true, offSinceMs = null, nowMs = 100_000L),
        )
    }

    @Test fun recentDropBridgesToConnecting() {
        // 4999 ms after the drop: still inside the abort loop's cadence.
        assertEquals(
            RadarConnStatus.CONNECTING,
            RadarConnStatusDeriver.derive(dataFresh = false, gattActive = false, offSinceMs = 95_001L, nowMs = 100_000L),
        )
    }

    @Test fun exactlyAtTheWindowIsNotInRange() {
        // age == 5000 is out (strictly-less), so a genuinely-gone radar settles
        // within one screen tick rather than one tick plus a boundary frame.
        assertEquals(
            RadarConnStatus.NOT_IN_RANGE,
            RadarConnStatusDeriver.derive(dataFresh = false, gattActive = false, offSinceMs = 95_000L, nowMs = 100_000L),
        )
    }

    @Test fun oldDropIsNotInRange() {
        assertEquals(
            RadarConnStatus.NOT_IN_RANGE,
            RadarConnStatusDeriver.derive(dataFresh = false, gattActive = false, offSinceMs = 10_000L, nowMs = 100_000L),
        )
    }

    @Test fun aDropStampedSlightlyInTheFutureStillBridges() {
        // A composition can render with a clock read marginally older than the
        // coordinator's write; a negative age is "just dropped", not "ancient".
        assertEquals(
            RadarConnStatus.CONNECTING,
            RadarConnStatusDeriver.derive(dataFresh = false, gattActive = false, offSinceMs = 100_001L, nowMs = 100_000L),
        )
    }

    @Test fun neverConnectedThisSessionIsNotInRange() {
        assertEquals(
            RadarConnStatus.NOT_IN_RANGE,
            RadarConnStatusDeriver.derive(dataFresh = false, gattActive = false, offSinceMs = null, nowMs = 100_000L),
        )
    }
}
