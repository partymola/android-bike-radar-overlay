// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slider's rungs against the store's clamp. Two lists of the same thing
 * with nothing in the type system tying them together, which is the drift this
 * pins: a rung outside the clamp reads back as a different window than the one
 * the rider set, and the screen then shows a value the app is not using.
 */
class RadarDropWindowLadderTest {

    @Test
    fun theLaddersEndsAreTheStoresBounds() {
        assertEquals(Prefs.RADAR_DROP_TRACK_WINDOW_MIN_SEC, RadarDropWindowLadder.RUNGS_SEC.first())
        assertEquals(Prefs.RADAR_DROP_TRACK_WINDOW_MAX_SEC, RadarDropWindowLadder.RUNGS_SEC.last())
    }

    @Test
    fun theFirstRungIsTheMeasuredDefault() {
        // A rider who never touches the slider and one who drags it to the
        // bottom must land on the same window, or the corpus figures behind
        // the default stop describing either of them.
        assertEquals(Prefs.RADAR_DROP_TRACK_WINDOW_DEFAULT_SEC, RadarDropWindowLadder.RUNGS_SEC.first())
    }

    @Test
    fun theRungsAreTheLadderThatShipped() {
        // Literal, not derived: a generated ladder would agree with whatever
        // the generator became. 30 s to an hour, coarsening as it climbs.
        assertEquals(listOf(30, 60, 120, 300, 600, 1800, 3600), RadarDropWindowLadder.RUNGS_SEC)
    }

    @Test
    fun everyRungRoundTripsThroughTheSliderPosition() {
        RadarDropWindowLadder.RUNGS_SEC.forEachIndexed { index, sec ->
            assertEquals(index, RadarDropWindowLadder.indexOf(sec))
            assertEquals(sec, RadarDropWindowLadder.secondsAt(index))
        }
    }

    @Test
    fun aValueOffTheLadderRendersOnTheNearestRung() {
        // A hand-edited pref, or one restored from a backup written by a build
        // with different rungs. Snapping such a value to an end would silently
        // change a setting the rider never touched.
        // Literal positions, not a lookup on the list under test: comparing
        // production output against `RUNGS_SEC.indexOf(...)` asks the same list
        // both questions.
        assertEquals(4, RadarDropWindowLadder.indexOf(700))
        assertEquals(5, RadarDropWindowLadder.indexOf(1500))
        // A tie takes the shorter window, which is the one that cues less.
        assertEquals(0, RadarDropWindowLadder.indexOf(45))
    }

    @Test
    fun anOutOfRangeSliderPositionCannotThrow() {
        // Compose hands back a float; rounding at either end must land on a
        // rung rather than off the list.
        assertEquals(RadarDropWindowLadder.RUNGS_SEC.first(), RadarDropWindowLadder.secondsAt(-1))
        assertEquals(RadarDropWindowLadder.RUNGS_SEC.last(), RadarDropWindowLadder.secondsAt(99))
    }

    @Test
    fun theStepCountIsThePositionsBetweenTheEnds() {
        // Compose counts intermediate stops, so this is the rung count minus
        // two. Off by one and the slider stops short of an hour, or offers a
        // position with no rung behind it. The literal is the whole test:
        // asserting `RUNGS_SEC.size - 2` restates the production expression and
        // could never fail.
        assertEquals(5, RadarDropWindowLadder.sliderSteps)
    }

    @Test
    fun theRungsClimb() {
        assertTrue(
            "rungs must be strictly increasing, got ${RadarDropWindowLadder.RUNGS_SEC}",
            RadarDropWindowLadder.RUNGS_SEC.zipWithNext().all { (a, b) -> a < b },
        )
    }
}
