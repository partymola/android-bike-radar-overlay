// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

enum class VehicleSize { BIKE, CAR, TRUCK }

enum class DataSource { NONE, V2 }

data class Vehicle(
    val id: Int,
    val distanceM: Int,
    val speedMs: Int,
    val size: VehicleSize = VehicleSize.CAR,
    /** -1.0 = full left, 0.0 = same lane / centre, +1.0 = full right */
    val lateralPos: Float = 0f,
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
) {
    val speedKmh: Int get() = (speedMs * 3.6).toInt()
}

data class RadarState(
    val vehicles: List<Vehicle> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: DataSource = DataSource.NONE,
    /** Milliseconds since the start of a scripted scenario (replay or
     *  synthetic). Null when the state is sourced from a live radar link -
     *  the overlay uses null to decide whether to render the t+... label. */
    val scenarioTimeMs: Long? = null,
    /** Rider's own bike speed in km/h, sourced from the radar's
     *  device-status frame (byte[len-1] x 0.25 km/h). Null until the first
     *  device-status frame has been received in the current session. */
    val bikeSpeedKmh: Int? = null,
) {
    val isClear: Boolean get() = vehicles.isEmpty()
}
