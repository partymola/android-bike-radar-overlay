// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.math.abs

/**
 * Pure, framework-free render math for [RadarOverlayView].
 *
 * The overlay is a Canvas [android.view.View] that JaCoCo cannot line-cover
 * (it is exercised only through Roborazzi golden renders), so the safety-
 * adjacent decisions it makes - which threat colour a target gets, where it
 * sits on the strip, how far it fades, and whether a near-stationary target is
 * demoted to a hollow "noted, not a threat" outline - used to live inline in
 * `onDraw` at 0% measured coverage. They are extracted here as density-free
 * functions over primitives so each branch is JVM-unit-asserted; the view keeps
 * only the Canvas drawing and the dp/Color packing.
 */

/** Amber / red closing-speed thresholds (km/h) for the threat-colour bands. */
internal data class SpeedBands(val amberKmh: Int, val redKmh: Int)

/** Threat class a vehicle is drawn in, by its closing speed against the bands. */
internal enum class ThreatLevel { SAFE, WARNING, DANGER }

/**
 * Fixed closing-speed bands used when adaptive alerts are off or bikeSpeedKmh
 * is null. Tuned for a typical urban cruising rider (~20-25 km/h).
 */
internal val FIXED_SPEED_BANDS = SpeedBands(amberKmh = 25, redKmh = 50)

/**
 * |lateralPos| > 0.3 ≈ 0.9 m off the rider's own lane (the decoder gate uses
 * 0.5 m). Anything within 0.9 m of centre stays a filled box so a stationary
 * tailgater never gets edge-docked.
 */
internal const val RENDERER_STATIONARY_MIN_LATERAL = 0.3f

/**
 * ≤ 1 m/s lateral drift; the decoder doesn't gate on lateral velocity (it
 * relies on dwell), so this is an extra renderer-only guard against targets
 * weaving toward the rider being suppressed mid-swerve. [Vehicle.speedXMs] is
 * in m/s after the decoder's LSB conversion.
 */
internal const val RENDERER_STATIONARY_MAX_LATERAL_MS = 1

/**
 * Scales the amber / red closing-speed bands by rider speed so that a stopped
 * rider (puncture at roadside, traffic-light stop) sees alarming colours
 * earlier, and a cruising rider doesn't get coloured warnings for every vehicle
 * that happens to be overtaking. At bikeSpeed = 0 the bands collapse toward the
 * classic "anything approaching is worth watching" mode; at 30 km/h they stay
 * near the legacy static thresholds. Slightly superlinear past 30 so fast
 * descenders don't drown in red boxes. Null bike speed (no device-status frame
 * yet) falls back to [FIXED_SPEED_BANDS].
 */
internal fun adaptiveSpeedBands(bikeSpeedKmh: Int?): SpeedBands {
    val s = bikeSpeedKmh ?: return FIXED_SPEED_BANDS
    val amber = (15 + s / 2).coerceAtLeast(10)
    val red = (30 + s).coerceAtLeast(20)
    return SpeedBands(amber, red)
}

/** Threat class for a target closing at [closingKmh] against [bands]. */
internal fun threatLevel(closingKmh: Int, bands: SpeedBands): ThreatLevel = when {
    closingKmh < bands.amberKmh -> ThreatLevel.SAFE
    closingKmh < bands.redKmh -> ThreatLevel.WARNING
    else -> ThreatLevel.DANGER
}

/**
 * Maps a distance to its position fraction down the strip: 0 m -> 0f (at the
 * rider), [visualMaxM] -> 1f (farthest). Clamped so out-of-window targets pin
 * to the ends. The view lerps this between its top and bottom Y.
 */
internal fun distToYFraction(dist: Float, visualMaxM: Int): Float = dist.coerceIn(0f, visualMaxM.toFloat()) / visualMaxM

/**
 * Close targets render near-solid; far targets fade to ~30% so the rider's eye
 * lands on immediate threats first. Linear in distance - cheap, predictable,
 * easy to re-tune after a ride.
 */
internal fun distanceAlphaFactor(dist: Float, visualMaxM: Int): Float = 1f - 0.7f * distToYFraction(dist, visualMaxM)

/**
 * Whether a target should be drawn as the hollow edge-docked "noted, not a
 * threat" outline rather than a filled coloured box.
 *
 * True for any decoder-flagged [isAlongsideStationary] target, plus a strict
 * renderer-side fallback for the parked-car-in-the-next-lane case the decoder
 * dwell gate can miss (e.g. a vehicle that sat on the chevron for 68 s before
 * the dwell window tripped). The fallback is a strict subset of the decoder
 * gate plus extra guards so a tailgater is never edge-docked: a clearly
 * off-centre target ([RENDERER_STATIONARY_MIN_LATERAL]), known longitudinal AND
 * lateral velocity at rest, close range, a confirmed-slow rider, an in-front
 * frame, and non-stale lateral data. Any missing datum (null bike speed or
 * lateral velocity) falls back to the normal filled box.
 */
internal fun shouldEdgeDockStationary(
    isAlongsideStationary: Boolean,
    isBehind: Boolean,
    lateralUnknown: Boolean,
    speedMs: Float,
    distanceM: Int,
    lateralPos: Float,
    bikeSpeedMs: Float?,
    speedXMs: Int?,
): Boolean {
    if (isAlongsideStationary) return true
    return !isBehind &&
        !lateralUnknown &&
        abs(speedMs) <= RadarV2Decoder.STATIONARY_SPEED_MS &&
        distanceM in 0..RadarV2Decoder.ALONGSIDE_RANGE_Y_M &&
        abs(lateralPos) > RENDERER_STATIONARY_MIN_LATERAL &&
        bikeSpeedMs != null &&
        bikeSpeedMs <= RadarV2Decoder.ALONGSIDE_RIDER_SLOW_MS &&
        speedXMs != null &&
        abs(speedXMs) <= RENDERER_STATIONARY_MAX_LATERAL_MS
}
