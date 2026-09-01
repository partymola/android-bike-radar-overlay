// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.RadarState
import es.jjrh.bikeradar.Vehicle
import es.jjrh.bikeradar.VehicleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection from the internal model onto the cross-app contract.
 *
 * Expected values are literals rather than the production constants: asserting
 * a constant against itself stays green when the constant is wrong.
 */
class RadarContractTest {

    private fun vehicle(
        id: Int = 1,
        distanceM: Int = 30,
        speedMs: Float = -5f,
        size: VehicleSize = VehicleSize.CAR,
        lateralPos: Float = 0.25f,
        rangeXm: Float = 1.5f,
        isBehind: Boolean = false,
        lateralUnknown: Boolean = false,
    ) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        size = size,
        lateralPos = lateralPos,
        rangeXm = rangeXm,
        isBehind = isBehind,
        lateralUnknown = lateralUnknown,
    )

    @Test
    fun everySourceAdvertisesExactlyWhatItCanMeasure() {
        // Exhaustive on purpose: a new DataSource makes this fail until someone
        // writes its literal, which is the moment the mapping starts to matter.
        assertEquals(
            mapOf(DataSource.NONE to 0, DataSource.V1 to 0, DataSource.V2 to 15),
            DataSource.entries.associateWith { RadarContract.capabilitiesOf(it) },
        )
    }

    @Test
    fun aV2StreamAdvertisesEveryCapability() {
        assertEquals(15, RadarContract.capabilitiesOf(DataSource.V2))
    }

    @Test
    fun aRangeOnlyStreamAdvertisesNothingItCannotMeasure() {
        assertEquals(0, RadarContract.capabilitiesOf(DataSource.V1))
    }

    @Test
    fun noSourceAdvertisesNothing() {
        assertEquals(0, RadarContract.capabilitiesOf(DataSource.NONE))
    }

    @Test
    fun theCapabilityBitsAreDistinctAndDoNotOverlap() {
        val bits = listOf(
            RadarContract.HAS_CLOSING_SPEED,
            RadarContract.HAS_LATERAL,
            RadarContract.HAS_RIDER_SPEED,
            RadarContract.HAS_VEHICLE_SIZE,
        )
        assertEquals(listOf(1, 2, 4, 8), bits)
        assertEquals("no two capabilities may share a bit", 15, bits.reduce { a, b -> a or b })
    }

    @Test
    fun vehicleSizeIsAMeasurementOnlyOnTheV2Stream() {
        assertTrue(DataSource.V2.hasVehicleSize)
        assertFalse("a range-only stream carries no class byte", DataSource.V1.hasVehicleSize)
        assertFalse(DataSource.NONE.hasVehicleSize)
    }

    @Test
    fun aTargetThatHasOvertakenTheRiderIsReportedAsAhead() {
        assertTrue(RadarContract.toParcel(vehicle(isBehind = true), DataSource.V2).isAhead)
        assertFalse(RadarContract.toParcel(vehicle(isBehind = false), DataSource.V2).isAhead)
    }

    @Test
    fun anUnusableLateralFrameIsReportedAsNotKnown() {
        assertFalse(RadarContract.toParcel(vehicle(lateralUnknown = true), DataSource.V2).lateralKnown)
        assertTrue(RadarContract.toParcel(vehicle(lateralUnknown = false), DataSource.V2).lateralKnown)
    }

    @Test
    fun everyVehicleClassMapsToItsOwnWireCode() {
        assertEquals(0, RadarContract.toParcel(vehicle(size = VehicleSize.CAR), DataSource.V2).size)
        assertEquals(1, RadarContract.toParcel(vehicle(size = VehicleSize.TRUCK), DataSource.V2).size)
    }

    @Test
    fun theBikeCodeIsReservedAndNeverProduced() {
        val emitted = VehicleSize.entries.map { RadarContract.toParcel(vehicle(size = it), DataSource.V2).size }
        assertFalse("the radar reports no bikes, so the wire must not claim one", 2 in emitted)
        VehicleSize.entries.forEach { assertNotEquals(2, RadarContract.toParcel(vehicle(size = it), DataSource.V2).size) }
    }

    @Test
    fun closingSpeedIsCarriedInKmhAndKeepsItsSign() {
        assertEquals(36, RadarContract.toParcel(vehicle(speedMs = -10f), DataSource.V2).closingKmh)
        assertEquals(-18, RadarContract.toParcel(vehicle(speedMs = 5f), DataSource.V2).closingKmh)
    }

    @Test
    fun aRangeOnlyTrackProjectsAsDefaultsRatherThanMeasurements() {
        // Built the way RadarV1Decoder builds one: range is the only reading,
        // and every other value is a default. Nothing in the target itself says
        // so, which is why the state carries the capability bits.
        val v1 = Vehicle(id = 4, distanceM = 25, speedMs = 0f, lateralUnknown = true)
        val p = RadarContract.toParcel(v1, DataSource.V1)
        assertEquals("a zero closing speed here is a default, not a stopped car", 0, p.closingKmh)
        assertEquals("CAR is the default, not a classification", 0, p.size)
        assertEquals(0f, p.lateralPos, 0f)
        assertEquals(0f, p.rangeXm, 0f)
        assertFalse(p.lateralKnown)
    }

    @Test
    fun aRangeOnlyStateSaysItMeasuredNoneOfIt() {
        val state = RadarState(
            vehicles = listOf(Vehicle(id = 1, distanceM = 20, speedMs = 0f, lateralUnknown = true)),
            source = DataSource.V1,
        )
        val p = RadarContract.toParcel(state)
        assertEquals("nothing on this stream is measured beyond range", 0, p.capabilities)
        assertFalse("no device-status frame exists on V1, so rider speed is never known", p.riderSpeedKnown)
        assertEquals(0f, p.bikeSpeedMs, 0f)
    }

    @Test
    fun aFullyCapableStateSaysSo() {
        val state = RadarState(
            vehicles = listOf(vehicle()),
            source = DataSource.V2,
            bikeSpeedMs = 6.5f,
        )
        val p = RadarContract.toParcel(state)
        assertEquals(15, p.capabilities)
        assertTrue(p.riderSpeedKnown)
        assertEquals(6.5f, p.bikeSpeedMs, 0f)
    }

    @Test
    fun aRiderSpeedNotYetReportedIsNotAStoppedRider() {
        // The transient the capability bit cannot express: a V2 radar has
        // connected, so HAS_RIDER_SPEED is set, but no device-status frame has
        // arrived and bikeSpeedMs is still null.
        val state = RadarState(vehicles = emptyList(), source = DataSource.V2, bikeSpeedMs = null)
        val p = RadarContract.toParcel(state)
        assertEquals("the stream can measure it", 15, p.capabilities)
        assertFalse("but it has not yet, and 0f must not read as stationary", p.riderSpeedKnown)
    }

    @Test
    fun aLiveV2StreamIsReportedAsDelivering() {
        // Without this only NONE and V1 are checked, and `source == V1` passes
        // both. That inversion would make the main hardware report a dead link.
        assertTrue(RadarContract.toParcel(RadarState(source = DataSource.V2)).streamLive)
    }

    @Test
    fun targetsInOneFrameKeepTheirOwnFlags() {
        // Every other multi-target fixture differs only in id, so a writer that
        // read the first target's booleans for all of them, or a projection that
        // hoisted lateral to the frame, would pass unnoticed.
        val state = RadarState(
            vehicles = listOf(
                vehicle(id = 1, lateralUnknown = false, isBehind = false),
                vehicle(id = 2, lateralUnknown = true, isBehind = false),
                vehicle(id = 3, lateralUnknown = false, isBehind = true),
            ),
            source = DataSource.V2,
        )
        val out = RadarContract.toParcel(state).vehicles
        assertEquals(listOf(true, false, true), out.map { it.lateralKnown })
        assertEquals(listOf(false, false, true), out.map { it.isAhead })
    }

    @Test
    fun aStreamWithNoLateralChannelReportsNoUsableLateralFrame() {
        val v = vehicle(lateralUnknown = false)
        assertFalse(
            "a range-only stream cannot have a usable lateral read",
            RadarContract.toParcel(v, DataSource.V1).lateralKnown,
        )
        assertTrue(RadarContract.toParcel(v, DataSource.V2).lateralKnown)
    }

    @Test
    fun aStoppedRiderIsAMeasurementNotAnAbsentOne() {
        // The discriminating case for riderSpeedKnown: a reported zero means the
        // rider is stopped, an absent one means nothing has been reported. Every
        // other fixture has the flag and a non-zero value moving together.
        val state = RadarState(vehicles = emptyList(), source = DataSource.V2, bikeSpeedMs = 0f)
        val p = RadarContract.toParcel(state)
        assertTrue("a reported zero is a measurement", p.riderSpeedKnown)
        assertEquals(0f, p.bikeSpeedMs, 0f)
    }

    @Test
    fun aRiderSpeedWithoutTheCapabilityBehindItIsNotReportedAsKnown() {
        // The two must not be able to contradict each other on the wire.
        val state = RadarState(vehicles = emptyList(), source = DataSource.V1, bikeSpeedMs = 4f)
        assertFalse(RadarContract.toParcel(state).riderSpeedKnown)
    }

    @Test
    fun everyTargetInAStateIsProjectedInOrder() {
        val state = RadarState(
            vehicles = listOf(vehicle(id = 3), vehicle(id = 1), vehicle(id = 2)),
            source = DataSource.V2,
        )
        val p = RadarContract.toParcel(state)
        assertEquals(listOf(3, 1, 2), p.vehicles.map { it.id })
        assertFalse("targets are present, so the lane is not clear", p.isClear)

        val empty = RadarContract.toParcel(RadarState(vehicles = emptyList(), source = DataSource.V2))
        assertTrue("no targets on a live stream is a clear lane", empty.isClear)
    }

    @Test
    fun aStateCarriesItsOwnTimestamp() {
        val p = RadarContract.toParcel(RadarState(timestamp = 1_234L, source = DataSource.V2))
        assertEquals(1_234L, p.timestamp)
    }

    @Test
    fun anAppWithNoRadarDoesNotReportAClearRoad() {
        // The value sitting in the bus before anything connects. Its targets,
        // capabilities and isClear are identical to a range-only radar seeing an
        // empty road, so streamLive is the only thing telling them apart.
        val p = RadarContract.toParcel(RadarState())
        assertFalse("no radar has ever been attached", p.streamLive)
        assertTrue(p.isClear)
        assertEquals(emptyList<RadarVehicleParcel>(), p.vehicles)

        val liveButClear = RadarContract.toParcel(
            RadarState(vehicles = emptyList(), source = DataSource.V1),
        )
        assertTrue("a radar is delivering, the road is just empty", liveButClear.streamLive)
        assertEquals(
            "every other field is identical, which is why streamLive exists",
            p.capabilities,
            liveButClear.capabilities,
        )
    }

    @Test
    fun theRemainingFieldsAreCarriedThrough() {
        val p = RadarContract.toParcel(vehicle(id = 7, distanceM = 42, lateralPos = -0.5f, rangeXm = -2.25f), DataSource.V2)
        assertEquals(7, p.id)
        assertEquals(42, p.distanceM)
        assertEquals(-0.5f, p.lateralPos, 0f)
        assertEquals(-2.25f, p.rangeXm, 0f)
    }
}
