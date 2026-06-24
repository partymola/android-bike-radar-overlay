// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [overlayRenderEquivalent]: per-frame timestamp/source churn must NOT
 * force an overlay redraw, but any change to a field onDraw reads must.
 */
class OverlayRenderKeyTest {

    private val v1 = Vehicle(id = 1, distanceM = 5, speedMs = 3f)
    private val v2 = Vehicle(id = 1, distanceM = 8, speedMs = 3f)

    @Test
    fun identicalStatesAreEquivalent() {
        val s = RadarState(vehicles = listOf(v1), scenarioTimeMs = 10L, bikeSpeedMs = 4f)
        assertTrue(overlayRenderEquivalent(s, s.copy()))
    }

    @Test
    fun timestampOnlyDifferenceIsEquivalent() {
        // The whole point: a new frame's fresh timestamp must not redraw.
        val a = RadarState(vehicles = listOf(v1), timestamp = 1_000L)
        val b = RadarState(vehicles = listOf(v1), timestamp = 2_000L)
        assertTrue(overlayRenderEquivalent(a, b))
    }

    @Test
    fun sourceOnlyDifferenceIsEquivalent() {
        val a = RadarState(vehicles = listOf(v1), source = DataSource.NONE)
        val b = RadarState(vehicles = listOf(v1), source = DataSource.V2)
        assertTrue(overlayRenderEquivalent(a, b))
    }

    @Test
    fun differentVehiclesAreNotEquivalent() {
        assertFalse(
            overlayRenderEquivalent(RadarState(vehicles = listOf(v1)), RadarState(vehicles = listOf(v2))),
        )
        assertFalse(
            overlayRenderEquivalent(RadarState(vehicles = emptyList()), RadarState(vehicles = listOf(v1))),
        )
        // Same list length, vehicle differs only in a rendered field (lateralPos
        // moves the box). Structural Vehicle equality must still force a redraw -
        // guards against a future narrowing of the vehicle comparison leaving a
        // moved box stale.
        assertFalse(
            overlayRenderEquivalent(
                RadarState(vehicles = listOf(v1)),
                RadarState(vehicles = listOf(v1.copy(lateralPos = 0.9f))),
            ),
        )
    }

    @Test
    fun nullVsValueBoundariesAreNotEquivalent() {
        // onDraw branches on scenarioTimeMs == null (label shown vs hidden) and
        // bikeSpeedMs == null (fixed vs adaptive colour bands), so null-vs-zero
        // changes the frame and must redraw.
        assertFalse(overlayRenderEquivalent(RadarState(scenarioTimeMs = null), RadarState(scenarioTimeMs = 0L)))
        assertFalse(overlayRenderEquivalent(RadarState(bikeSpeedMs = null), RadarState(bikeSpeedMs = 0f)))
    }

    @Test
    fun differentScenarioTimeIsNotEquivalent() {
        assertFalse(overlayRenderEquivalent(RadarState(scenarioTimeMs = 1L), RadarState(scenarioTimeMs = 2L)))
    }

    @Test
    fun differentBikeSpeedIsNotEquivalent() {
        assertFalse(overlayRenderEquivalent(RadarState(bikeSpeedMs = 3f), RadarState(bikeSpeedMs = 5f)))
    }
}
