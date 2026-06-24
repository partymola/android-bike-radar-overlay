// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the [RadarOverlayView] render math extracted into
 * [RadarThreatRender]. The Canvas view itself is Roborazzi-only, so these are
 * the line/branch asserts for the safety-adjacent threat-colour, fade and
 * edge-dock decisions.
 */
class RadarThreatRenderTest {

    // --- adaptiveSpeedBands ------------------------------------------------

    @Test
    fun adaptiveSpeedBands_nullSpeed_fallsBackToFixed() {
        assertEquals(FIXED_SPEED_BANDS, adaptiveSpeedBands(null))
        assertEquals(SpeedBands(25, 50), adaptiveSpeedBands(null))
    }

    @Test
    fun adaptiveSpeedBands_stoppedRider_tightestBands() {
        // s=0: amber max(15,10)=15, red max(30,20)=30
        assertEquals(SpeedBands(15, 30), adaptiveSpeedBands(0))
    }

    @Test
    fun adaptiveSpeedBands_cruising_nearLegacyThresholds() {
        // s=30: amber 15+15=30, red 30+30=60
        assertEquals(SpeedBands(30, 60), adaptiveSpeedBands(30))
        // s=20: amber 15+10=25, red 30+20=50
        assertEquals(SpeedBands(25, 50), adaptiveSpeedBands(20))
    }

    @Test
    fun adaptiveSpeedBands_usesIntegerHalving() {
        // s=5: amber 15 + (5/2=2) = 17, red 30+5 = 35
        assertEquals(SpeedBands(17, 35), adaptiveSpeedBands(5))
    }

    @Test
    fun adaptiveSpeedBands_negativeSpeed_hitsBothFloors() {
        // Defensive coerceAtLeast: s=-20 -> amber 15-10=5 -> floored to 10,
        // red 30-20=10 -> floored to 20.
        assertEquals(SpeedBands(10, 20), adaptiveSpeedBands(-20))
    }

    // --- threatLevel -------------------------------------------------------

    @Test
    fun threatLevel_bandBoundaries() {
        val bands = SpeedBands(amberKmh = 25, redKmh = 50)
        assertEquals(ThreatLevel.SAFE, threatLevel(24, bands))
        assertEquals(ThreatLevel.WARNING, threatLevel(25, bands)) // amber inclusive
        assertEquals(ThreatLevel.WARNING, threatLevel(49, bands))
        assertEquals(ThreatLevel.DANGER, threatLevel(50, bands)) // red inclusive
        assertEquals(ThreatLevel.DANGER, threatLevel(80, bands))
    }

    // --- distToYFraction ---------------------------------------------------

    @Test
    fun distToYFraction_mapsAndClamps() {
        assertEquals(0f, distToYFraction(0f, 50), 1e-4f)
        assertEquals(0.5f, distToYFraction(25f, 50), 1e-4f)
        assertEquals(1f, distToYFraction(50f, 50), 1e-4f)
        assertEquals(1f, distToYFraction(80f, 50), 1e-4f) // clamp far
        assertEquals(0f, distToYFraction(-10f, 50), 1e-4f) // clamp near
    }

    // --- distanceAlphaFactor -----------------------------------------------

    @Test
    fun distanceAlphaFactor_fadesLinearlyToThirty() {
        assertEquals(1f, distanceAlphaFactor(0f, 50), 1e-4f)
        assertEquals(0.65f, distanceAlphaFactor(25f, 50), 1e-4f)
        assertEquals(0.3f, distanceAlphaFactor(50f, 50), 1e-4f)
        assertEquals(0.3f, distanceAlphaFactor(99f, 50), 1e-4f) // clamped
    }

    // --- shouldEdgeDockStationary ------------------------------------------

    /** A target that passes the renderer-side parked-car fallback. */
    private fun edgeDock(
        isAlongsideStationary: Boolean = false,
        isBehind: Boolean = false,
        lateralUnknown: Boolean = false,
        speedMs: Float = 0.5f,
        distanceM: Int = 5,
        lateralPos: Float = 0.5f,
        bikeSpeedMs: Float? = 1.0f,
        speedXMs: Int? = 0,
    ) = shouldEdgeDockStationary(
        isAlongsideStationary = isAlongsideStationary,
        isBehind = isBehind,
        lateralUnknown = lateralUnknown,
        speedMs = speedMs,
        distanceM = distanceM,
        lateralPos = lateralPos,
        bikeSpeedMs = bikeSpeedMs,
        speedXMs = speedXMs,
    )

    @Test
    fun edgeDock_decoderFlag_shortCircuitsTrue() {
        // The decoder flag wins even when every renderer-fallback gate fails.
        assertTrue(
            edgeDock(
                isAlongsideStationary = true,
                isBehind = true,
                lateralUnknown = true,
                speedMs = 9f,
                distanceM = 99,
                lateralPos = 0f,
                bikeSpeedMs = null,
                speedXMs = null,
            ),
        )
    }

    @Test
    fun edgeDock_rendererFallback_allGatesPass() {
        assertTrue(edgeDock())
    }

    @Test
    fun edgeDock_failsWhenBehind() {
        assertFalse(edgeDock(isBehind = true))
    }

    @Test
    fun edgeDock_failsWhenLateralUnknown() {
        assertFalse(edgeDock(lateralUnknown = true))
    }

    @Test
    fun edgeDock_failsWhenMovingLongitudinally() {
        // abs(speedMs) > STATIONARY_SPEED_MS (1f)
        assertFalse(edgeDock(speedMs = 1.5f))
    }

    @Test
    fun edgeDock_failsWhenOutOfRange() {
        assertFalse(edgeDock(distanceM = 9)) // > ALONGSIDE_RANGE_Y_M (8)
        assertFalse(edgeDock(distanceM = -1)) // below the 0 floor
    }

    @Test
    fun edgeDock_failsWhenTooNearCentre() {
        // abs(lateralPos) must exceed RENDERER_STATIONARY_MIN_LATERAL (0.3)
        assertFalse(edgeDock(lateralPos = 0.2f))
    }

    @Test
    fun edgeDock_failsWhenBikeSpeedUnknownOrTooFast() {
        assertFalse(edgeDock(bikeSpeedMs = null))
        assertFalse(edgeDock(bikeSpeedMs = 3.0f)) // > ALONGSIDE_RIDER_SLOW_MS (2.75)
    }

    @Test
    fun edgeDock_failsWhenLateralVelocityUnknownOrDrifting() {
        assertFalse(edgeDock(speedXMs = null))
        assertFalse(edgeDock(speedXMs = 2)) // > RENDERER_STATIONARY_MAX_LATERAL_MS (1)
        // Inward drift: the gate compares abs(speedXMs), so a target weaving
        // toward the rider at -2 m/s must NOT be demoted (pins the abs()).
        assertFalse(edgeDock(speedXMs = -2))
    }

    @Test
    fun edgeDock_inclusiveGateBoundariesStillDock() {
        // Each <=/range gate is inclusive: a value sitting exactly on the
        // threshold still edge-docks.
        assertTrue(edgeDock(speedMs = 1.0f)) // == STATIONARY_SPEED_MS
        assertTrue(edgeDock(speedMs = -1.0f)) // abs == STATIONARY_SPEED_MS
        assertTrue(edgeDock(distanceM = 0)) // range low edge
        assertTrue(edgeDock(distanceM = 8)) // == ALONGSIDE_RANGE_Y_M, range high edge
        assertTrue(edgeDock(bikeSpeedMs = 2.75f)) // == ALONGSIDE_RIDER_SLOW_MS
        assertTrue(edgeDock(speedXMs = 1)) // abs == RENDERER_STATIONARY_MAX_LATERAL_MS
        assertTrue(edgeDock(speedXMs = -1)) // abs == MAX, negative side
    }

    @Test
    fun edgeDock_lateralGateIsStrict() {
        // The off-centre gate is a strict >, so a target exactly on the
        // threshold is NOT demoted (stays a filled box).
        assertFalse(edgeDock(lateralPos = RENDERER_STATIONARY_MIN_LATERAL))
    }
}
