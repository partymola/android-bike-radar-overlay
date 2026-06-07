// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [RadarLinkVisualDecider]: the overlay shows RECONNECTING only once the
 * radar has been continuously down past the threshold AND it had connected at
 * least once this session AND alerts are not paused. It is deliberately NOT
 * eBike-gated - the visual status must serve radar-only riders - which is
 * proven structurally here: the function takes no eBike parameter at all.
 */
class RadarLinkVisualDeciderTest {

    private val threshold = 10_000L
    private val live = RadarLinkVisualDecider.LinkVisual.LIVE
    private val reconnecting = RadarLinkVisualDecider.LinkVisual.RECONNECTING

    /** Thin wrapper so each case reads as (everLive, downForMs[, paused]). */
    private fun decide(everLive: Boolean, downForMs: Long?, paused: Boolean = false) = RadarLinkVisualDecider.decide(everLive, downForMs, threshold, paused)

    @Test
    fun connectedIsLive() {
        // downForMs == null means the radar is currently connected.
        assertEquals(live, decide(true, null))
    }

    @Test
    fun belowThresholdStaysLive() {
        // A normal 5-10s reconnect must not flip the screen to RECONNECTING.
        assertEquals(live, decide(true, threshold - 1))
    }

    @Test
    fun atThresholdIsReconnecting() {
        // Inclusive boundary (>=), matching the radar-drop cue's threshold edge.
        assertEquals(reconnecting, decide(true, threshold))
    }

    @Test
    fun aboveThresholdIsReconnecting() {
        assertEquals(reconnecting, decide(true, threshold + 5_000))
    }

    @Test
    fun coldStartIsLiveEvenWhenLongDown() {
        // Never connected this session: there was no link to lose, so a long
        // "down" duration must not show a false RECONNECTING on a cold start.
        assertEquals(live, decide(false, threshold * 100))
    }

    @Test
    fun pausedStaysLiveEvenWhenDown() {
        // Alerts paused: the rider muted the app, so the banner must not show
        // even when the radar is well past the threshold. This is the
        // load-bearing hide-while-paused branch, pinned here rather than left
        // to the caller's call-ordering.
        assertEquals(live, decide(true, threshold + 5_000, paused = true))
    }

    @Test
    fun reconnectingWithNoEbikeInputs() {
        // The whole signature is eBike-free: a radar-only rider (no Bosch eBike,
        // no snapshot) still gets the visual once down past the threshold. This
        // is the graceful-degradation anchor - if a future edit re-couples the
        // visual path to an eBike gate, this test's intent is the tripwire.
        assertEquals(reconnecting, decide(true, threshold))
    }

    @Test
    fun reconnectEdgeReturnsToLive() {
        // RECONNECTING while down...
        assertEquals(reconnecting, decide(true, threshold + 1))
        // ...then the radar returns (downForMs back to null) -> LIVE on the next
        // evaluation, with no latch to leak (auto-clears the banner).
        assertEquals(live, decide(true, null))
    }
}
