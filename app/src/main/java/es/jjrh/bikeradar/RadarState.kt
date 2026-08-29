// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

enum class VehicleSize { CAR, TRUCK }

/**
 * Which stream a [RadarState] came from, and what that stream can measure.
 *
 * [V1] is the legacy stream and carries RANGE ONLY. The capability flags below
 * exist because the alternative - reading a zero and hoping every consumer
 * treats it as absent - does not survive contact: a zero closing speed is
 * indistinguishable from a car pacing the rider exactly, and a zero lateral
 * offset means "dead centre behind me". Ask the SOURCE what it can measure;
 * do not infer it from a value.
 *
 * This is deliberately separate from [Vehicle.lateralUnknown], which is a
 * PER-FRAME condition on a source that does have lateral ("this frame's read
 * was unreliable, skip it and take the next"). Conflating the two makes every
 * frame of a legacy ride look like a bad frame, which silently empties the
 * ride record. Source capability is permanent; frame transience is not.
 */
enum class DataSource {
    NONE,
    V1,
    V2,
    ;

    /** Per-target closing speed. False on [V1]: the legacy threat record's
     *  third byte is a state flag, not a velocity. */
    val hasClosingSpeed: Boolean get() = this == V2

    /** Per-target lateral offset. False on [V1]: no lateral channel exists,
     *  so every target can only be drawn on the centreline. */
    val hasLateral: Boolean get() = this == V2

    /** The rider's own bike speed, from the device-status frame. False on
     *  [V1], which has no such frame. */
    val hasRiderSpeed: Boolean get() = this == V2
}

data class Vehicle(
    val id: Int,
    val distanceM: Int,
    /**
     * Longitudinal closing speed in metres per second, as reported by the
     * radar in target byte[7] (signed int8 x 0.5 m/s - native protocol
     * quantum). Sign convention: negative = approaching, positive = receding,
     * zero = stationary relative to the rider. Float is carried end-to-end
     * so downstream threshold checks land on the exact raw-byte boundaries
     * (e.g. raw -12 -> -6.0f hits a -6f gate; raw -11 -> -5.5f does not).
     */
    val speedMs: Float,
    val size: VehicleSize = VehicleSize.CAR,
    /** -1.0 = full left, 0.0 = same lane / centre, +1.0 = full right */
    val lateralPos: Float = 0f,
    /**
     * Lateral offset in metres, signed like [lateralPos] (negative = left,
     * positive = right), mount-offset-corrected, UNCLAMPED. [lateralPos]
     * saturates at +/-1.0 (= +/-[RadarV2Decoder.LATERAL_FULL_M]), which is
     * right for overlay rendering but destroys the difference between a car
     * one lane over and one on a parallel street 30 m away - a distinction
     * the urgent-cue lateral gates need. 0f when no lateral data exists for
     * the source (synthetic scenarios, defaults); consumers must fail open
     * on 0f, matching the [lateralPos] convention.
     */
    val rangeXm: Float = 0f,
    /**
     * True when the target has overtaken the rider and is now ahead of the
     * bike (rangeY < 0 in the V2 packed range field). `distanceM` in this
     * case is the absolute distance ahead, not behind. These tracks are
     * excluded from alert and overlay rendering because the rear radar
     * cannot reliably follow a target once it is in front of the rider.
     */
    val isBehind: Boolean = false,
    /**
     * Lateral closing speed in metres per second, as reported by the radar
     * in target byte[8] (signed int8 x 0.5 m/s). Sign matches [lateralPos]:
     * positive = the target is moving rightward relative to the bike.
     * Null when the radar emits its 0x80 sentinel ("no lateral velocity
     * available") for that frame.
     */
    val speedXMs: Int? = null,
    /**
     * True when this target is a near-stationary vehicle alongside the
     * rider (parked car / queued traffic in the next lane while the rider
     * crawls past). The decoder sets this when range, lateral offset,
     * closing speed, rider speed, and dwell-time gates all hold; see
     * [RadarV2Decoder] companion constants. The overlay renders these as
     * edge-docked hollow outlines instead of filled centre-lane boxes.
     * Recomputed every snapshot - flips back to false the moment any
     * gate breaks (e.g. the target starts closing), and the resulting
     * pop back to a normal filled box is the rider's attention cue.
     */
    val isAlongsideStationary: Boolean = false,
    /**
     * True when the radar's lateral channel reported `rangeXBits = 0`
     * for a far track (rangeY >= 10 m) whose previous frame had a
     * non-centred lateral position. This is the radar's
     * "lateral-unknown" sentinel: instead of a real lateral reading
     * the firmware emits a hard zero. The decoder carries forward the
     * previous frame's [lateralPos] so visual consumers see continuity,
     * and downstream gates (close-pass detection) should skip frames
     * with this flag because the lateral data is unreliable.
     */
    val lateralUnknown: Boolean = false,
    /**
     * Lateral offset in metres as the sensor reported it, before the
     * rider's mount-offset translation ([rangeXm] applies it).
     * Physical-plausibility gates use this so a configuration error can
     * never move a target on or off the road.
     * 0f when no lateral data exists for the source; consumers fail
     * open on 0f, matching the [rangeXm] convention.
     */
    val rangeXmRaw: Float = 0f,
    /**
     * When this track was first seen by the decoder (monotonic ms),
     * i.e. the decoder's own track birth - survives frame-to-frame
     * updates, resets when the track is pruned and re-acquired.
     * 0L for synthetic sources.
     */
    val bornAtMs: Long = 0L,
    /**
     * [distanceM] on the track's first frame. A real vehicle is
     * normally acquired far out (30-80 m); a track BORN at close range
     * is either radar clutter (turn-sweep ghosts, roadside objects) or
     * a reacquisition - see [bornInformative]. Int.MAX_VALUE for
     * synthetic sources, so born-close logic never engages on them.
     */
    val bornDistanceM: Int = Int.MAX_VALUE,
    /**
     * False when this track's birth says nothing about the physical
     * world: within the decoder's warm-up window after connect/reset,
     * or right after another track died at similar range (the same
     * physical vehicle reacquired under a new tid after a coverage
     * gap - the decoder prunes unseen moving tracks after only
     * ~800 ms, so this is common). Born-close gating must skip
     * uninformative births or it silences real reacquired followers
     * and kills the re-anchor beep. Default false = fail open.
     */
    val bornInformative: Boolean = false,
) {
    /**
     * Closing speed in km/h: positive when the target is approaching, which is
     * the opposite sign to [speedMs] and the convention every threshold in the
     * app is expressed in.
     *
     * This deliberately replaces a `speedKmh` that carried the wire sign
     * through unchanged. The overlay's threat bands are positive closing
     * speeds, so passing the wire value straight in painted every approaching
     * vehicle green and reserved the red danger border for traffic pulling
     * away. Both call sites read correctly and neither was wrong on its own
     * terms - the sign only became visible where the two conventions met.
     * Removing the signed accessor removes the accessor-shaped version of the
     * mistake; it cannot stop a caller hand-rolling `speedMs * 3.6f`, which
     * ClosePassDetector and RideStatsAccumulator already do. The conversion is
     * pinned by RadarThreatRenderTest; the two overlay call sites are pinned
     * only by the Roborazzi goldens.
     */
    val closingKmh: Int get() = (-speedMs * 3.6f).toInt()
}

data class RadarState(
    val vehicles: List<Vehicle> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: DataSource = DataSource.NONE,
    /** Milliseconds since the start of a scripted scenario (replay or
     *  synthetic). Null when the state is sourced from a live radar link -
     *  the overlay uses null to decide whether to render the t+... label. */
    val scenarioTimeMs: Long? = null,
    /** Rider's own bike speed in m/s, sourced from the radar's
     *  device-status frame (byte[len-1] x 0.25 m/s per LSB - native
     *  protocol resolution). Carried as Float so threshold comparisons
     *  in AlertDecider / ClosePassDetector / decoder land on the exact
     *  raw-byte boundaries the prior km/h thresholds did. Null until
     *  the first device-status frame has been received in the current
     *  session. */
    val bikeSpeedMs: Float? = null,
) {
    val isClear: Boolean get() = vehicles.isEmpty()
}
