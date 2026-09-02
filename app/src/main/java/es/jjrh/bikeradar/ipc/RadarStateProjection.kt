// SPDX-License-Identifier: GPL-3.0-or-later
// Additional permission for cross-app consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.Vehicle
import es.jjrh.bikeradar.VehicleSize

/**
 * How this app's internal model reaches the wire [RadarContract] describes.
 *
 * Split out because it reads [RadarState] and [Vehicle], which a consumer does
 * not have. That is what lets the contract stay copyable.
 */
object RadarStateProjection {

    /**
     * What the stream behind this state can actually measure, as a bitfield.
     *
     * Read off [DataSource]'s own capability properties rather than matching on
     * the enum here: the source of truth for "what can V1 do" is that enum, and
     * a second copy of the answer is a second thing to keep in step.
     */
    fun capabilitiesOf(source: DataSource): Int {
        var bits = 0
        if (source.hasClosingSpeed) bits = bits or RadarContract.HAS_CLOSING_SPEED
        if (source.hasLateral) bits = bits or RadarContract.HAS_LATERAL
        if (source.hasRiderSpeed) bits = bits or RadarContract.HAS_RIDER_SPEED
        if (source.hasVehicleSize) bits = bits or RadarContract.HAS_VEHICLE_SIZE
        return bits
    }

    /**
     * Project a whole snapshot onto the wire.
     *
     * This exists so the two collapses are decided once, here, rather than by
     * each caller.
     *
     * [RadarState.bikeSpeedMs] is null until the radar's first device-status
     * frame of the session, and a zero reaching a consumer unflagged reads as a
     * stationary rider.
     *
     * A state with no source at all carries no targets, which is the same shape
     * as a radar reporting an empty road. Without
     * [RadarStateParcel.streamLive] a consumer would read an all-clear off an
     * app that has never seen a radar.
     */
    fun toParcel(state: RadarState): RadarStateParcel = RadarStateParcel(
        timestamp = state.timestamp,
        vehicles = state.vehicles.map { toParcel(it, state.source) },
        bikeSpeedMs = state.bikeSpeedMs ?: 0f,
        // Both conditions, so the flag cannot contradict HAS_RIDER_SPEED. A
        // value without the capability behind it would leave a consumer with
        // two answers and no rule for which wins. The cost is that a rider
        // speed sourced from somewhere other than the radar reports as not
        // known, which is the conservative direction.
        riderSpeedKnown = state.source.hasRiderSpeed && state.bikeSpeedMs != null,
        streamLive = state.source != DataSource.NONE,
        isClear = state.isClear,
        capabilities = capabilitiesOf(state.source),
    )

    /**
     * The internal [Vehicle.isBehind] means "has overtaken the rider and is now
     * ahead", so it maps onto the contract's [RadarVehicleParcel.isAhead]
     * unchanged. The names read as opposites; the meanings are the same.
     */
    fun toParcel(vehicle: Vehicle, source: DataSource): RadarVehicleParcel = RadarVehicleParcel(
        id = vehicle.id,
        distanceM = vehicle.distanceM,
        closingKmh = vehicle.closingKmh,
        size = when (vehicle.size) {
            VehicleSize.CAR -> RadarContract.RADAR_SIZE_CAR
            VehicleSize.TRUCK -> RadarContract.RADAR_SIZE_TRUCK
        },
        lateralPos = vehicle.lateralPos,
        rangeXm = vehicle.rangeXm,
        isAhead = vehicle.isBehind,
        // ANDed here rather than left to the consumer, so one rule covers every
        // flag on this wire: trust a field when its own flag says so. A stream
        // with no lateral channel can never report a usable lateral frame.
        lateralKnown = source.hasLateral && !vehicle.lateralUnknown,
    )
}
