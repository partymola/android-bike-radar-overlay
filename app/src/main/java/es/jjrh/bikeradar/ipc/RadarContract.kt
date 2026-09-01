// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.Vehicle
import es.jjrh.bikeradar.VehicleSize

/**
 * The cross-app radar contract's constants and the projection from the app's
 * internal model onto it.
 *
 * The wire deliberately carries provenance alongside the numbers. A range-only
 * radar reports no closing speed, no lateral offset, no rider speed and no
 * vehicle class, and the decoder fills those with defaults; without
 * [capabilitiesOf] a consumer cannot tell a default from a reading and will
 * draw a fabricated lane position as though it were measured.
 */
object RadarContract {

    /** Bumped only when the wire layout changes. Written first on every parcel. */
    const val VERSION = 1

    const val HAS_CLOSING_SPEED = 1
    const val HAS_LATERAL = 2
    const val HAS_RIDER_SPEED = 4
    const val HAS_VEHICLE_SIZE = 8

    const val RADAR_SIZE_CAR = 0
    const val RADAR_SIZE_TRUCK = 1

    /**
     * Reserved and never emitted. The radar reports cars and trucks only, so a
     * consumer that colours by class must not present a bike legend as
     * something this stream can produce.
     */
    const val RADAR_SIZE_BIKE = 2

    /**
     * What the stream behind this state can actually measure, as a bitfield.
     *
     * Read off [DataSource]'s own capability properties rather than matching on
     * the enum here: the source of truth for "what can V1 do" is that enum, and
     * a second copy of the answer is a second thing to keep in step.
     */
    fun capabilitiesOf(source: DataSource): Int {
        var bits = 0
        if (source.hasClosingSpeed) bits = bits or HAS_CLOSING_SPEED
        if (source.hasLateral) bits = bits or HAS_LATERAL
        if (source.hasRiderSpeed) bits = bits or HAS_RIDER_SPEED
        if (source.hasVehicleSize) bits = bits or HAS_VEHICLE_SIZE
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
            VehicleSize.CAR -> RADAR_SIZE_CAR
            VehicleSize.TRUCK -> RADAR_SIZE_TRUCK
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
