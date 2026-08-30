// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one question two screens ask about the radar.
 *
 * The home System card and the Settings radar card both score "is the radar
 * live" off the published stream, and they used to answer it differently -
 * which is how one screen could call a radar Live while the other called it
 * absent. This pins the shared answer, including the part that is easy to
 * regress: a legacy source is live, and asking "is it the modern source"
 * instead would deny a rider the overlay is actively drawing for.
 *
 * Expected values are literals rather than references to the constant, so
 * moving the window the wrong way fails here instead of agreeing with itself.
 */
class RadarStreamIsLiveTest {

    private fun state(source: DataSource, tsMs: Long) = RadarState(timestamp = tsMs, source = source)

    @Test
    fun aLegacySourceIsLive() {
        assertTrue(radarStreamIsLive(state(DataSource.V1, 100_000L), nowMs = 100_500L))
    }

    @Test
    fun aModernSourceIsLive() {
        assertTrue(radarStreamIsLive(state(DataSource.V2, 100_000L), nowMs = 100_500L))
    }

    @Test
    fun noSourceIsNeverLiveHoweverRecentTheStamp() {
        // The default RadarState carries source NONE and a zero stamp; a
        // freshness test that read the stamp alone would call a bus that has
        // never published anything "live" for the first ten seconds after the
        // epoch, and more usefully, would call a cleared bus live for ten
        // seconds after every teardown.
        assertFalse(radarStreamIsLive(state(DataSource.NONE, 100_000L), nowMs = 100_000L))
    }

    @Test
    fun theWindowIsTenSecondsAndItsEdgeIsExclusive() {
        assertTrue("9999 ms old is live", radarStreamIsLive(state(DataSource.V1, 0L), nowMs = 9_999L))
        assertFalse("exactly 10000 ms old is not", radarStreamIsLive(state(DataSource.V1, 0L), nowMs = 10_000L))
    }

    @Test
    fun aStampFromTheFutureIsLive() {
        // A wall-clock step backwards on the reading side leaves now < stamp.
        // Live is the safe reading: the alternative is a screen calling a
        // streaming radar absent because the phone corrected its clock.
        assertTrue(radarStreamIsLive(state(DataSource.V2, 100_000L), nowMs = 99_000L))
    }
}
