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
     * True when the target has just overtaken the rider and is now behind
     * the bike. `distanceM` in this case is the distance *behind* (0-25 m),
     * not the distance in front. Set when the V2 decoder sees a zone-7
     * frame; these tracks are excluded from alert and overlay rendering.
     */
    val isBehind: Boolean = false,
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
) {
    val isClear: Boolean get() = vehicles.isEmpty()
}
