// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [RadarLinkVisualDecider]: the overlay shows RECONNECTING only once the
 * radar has been continuously down past the threshold AND it had connected at
 * least once this session. It is deliberately NOT eBike-gated - the visual
 * status must serve radar-only riders - which is proven structurally here:
 * the function takes no eBike parameter at all.
 */
class RadarLinkVisualDeciderTest {

    private val threshold = 10_000L
    private val visual = RadarLinkVisualDecider::decide
    private val live = RadarLinkVisualDecider.LinkVisual.LIVE
    private val reconnecting = RadarLinkVisualDecider.LinkVisual.RECONNECTING

    @Test
    fun connectedIsLive() {
        // radarDownForMs == null means the radar is currently connected.
        assertEquals(live, visual(true, null, threshold))
    }

    @Test
    fun belowThresholdStaysLive() {
        // A normal 5-10s reconnect must not flip the screen to RECONNECTING.
        assertEquals(live, visual(true, threshold - 1, threshold))
    }

    @Test
    fun atThresholdIsReconnecting() {
        // Inclusive boundary (>=), matching the radar-drop cue's threshold edge.
        assertEquals(reconnecting, visual(true, threshold, threshold))
    }

    @Test
    fun aboveThresholdIsReconnecting() {
        assertEquals(reconnecting, visual(true, threshold + 5_000, threshold))
    }

    @Test
    fun coldStartIsLiveEvenWhenLongDown() {
        // Never connected this session: there was no link to lose, so a long
        // "down" duration must not show a false RECONNECTING on a cold start.
        assertEquals(live, visual(false, threshold * 100, threshold))
    }

    @Test
    fun reconnectingWithNoEbikeInputs() {
        // The whole signature is eBike-free: a radar-only rider (no Bosch eBike,
        // no snapshot) still gets the visual once down past the threshold. This
        // is the graceful-degradation anchor - if a future edit re-couples the
        // visual path to an eBike gate, this test's intent is the tripwire.
        assertEquals(reconnecting, visual(true, threshold, threshold))
    }

    @Test
    fun reconnectEdgeReturnsToLive() {
        // RECONNECTING while down...
        assertEquals(reconnecting, visual(true, threshold + 1, threshold))
        // ...then the radar returns (downForMs back to null) -> LIVE on the next
        // evaluation, with no latch to leak (auto-clears the banner).
        assertEquals(live, visual(true, null, threshold))
    }
}
