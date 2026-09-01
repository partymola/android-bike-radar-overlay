// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import android.os.BadParcelableException
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The wire layout. Expected values are literals, so a changed constant fails
 * here rather than agreeing with itself.
 */
@RunWith(RobolectricTestRunner::class)
class RadarStateParcelTest {

    private fun target(
        id: Int = 1,
        distanceM: Int = 30,
        closingKmh: Int = 18,
        size: Int = 0,
        lateralPos: Float = 0.25f,
        rangeXm: Float = 1.5f,
        isAhead: Boolean = false,
        lateralKnown: Boolean = true,
    ) = RadarVehicleParcel(id, distanceM, closingKmh, size, lateralPos, rangeXm, isAhead, lateralKnown)

    private fun roundTrip(state: RadarStateParcel): RadarStateParcel {
        val p = Parcel.obtain()
        try {
            state.writeToParcel(p, 0)
            p.setDataPosition(0)
            return RadarStateParcel.CREATOR.createFromParcel(p)
        } finally {
            p.recycle()
        }
    }

    private fun state(
        vehicles: List<RadarVehicleParcel> = listOf(target()),
        timestamp: Long = 1_700_000_000_000L,
        bikeSpeedMs: Float = 5.5f,
        isClear: Boolean = false,
        riderSpeedKnown: Boolean = true,
        streamLive: Boolean = true,
        capabilities: Int = 15,
    ) = RadarStateParcel(
        timestamp,
        vehicles,
        bikeSpeedMs,
        riderSpeedKnown,
        streamLive,
        isClear,
        capabilities,
    )

    @Test
    fun theVersionIsTheFirstValueOnTheWire() {
        val p = Parcel.obtain()
        try {
            state().writeToParcel(p, 0)
            p.setDataPosition(0)
            assertEquals(
                "a reader must be able to branch on the version before reading anything else",
                1,
                p.readInt(),
            )
        } finally {
            p.recycle()
        }
    }

    @Test
    fun everyStateFieldSurvivesTheRoundTrip() {
        val out = roundTrip(state(timestamp = 42L, bikeSpeedMs = -3.25f, isClear = true, capabilities = 5))
        assertEquals(42L, out.timestamp)
        assertEquals(-3.25f, out.bikeSpeedMs, 0f)
        assertTrue(out.riderSpeedKnown)
        assertTrue(out.streamLive)
        assertTrue(out.isClear)
        assertEquals(5, out.capabilities)
        assertEquals(1, out.version)
    }

    @Test
    fun everyTargetFieldSurvivesTheRoundTrip() {
        val v = target(id = 9, distanceM = 77, closingKmh = -12, size = 1, lateralPos = -0.75f, rangeXm = -3.5f)
        val out = roundTrip(state(vehicles = listOf(v))).vehicles.single()
        assertEquals(v, out)
    }

    @Test
    fun bothBooleansSurviveInBothStates() {
        assertTrue(roundTrip(state(isClear = true)).isClear)
        assertFalse(roundTrip(state(isClear = false)).isClear)
        assertTrue(roundTrip(state(riderSpeedKnown = true)).riderSpeedKnown)
        assertFalse(roundTrip(state(riderSpeedKnown = false)).riderSpeedKnown)
        assertTrue(roundTrip(state(streamLive = true)).streamLive)
        assertFalse(roundTrip(state(streamLive = false)).streamLive)
        assertTrue(roundTrip(state(vehicles = listOf(target(isAhead = true)))).vehicles.single().isAhead)
        assertFalse(roundTrip(state(vehicles = listOf(target(isAhead = false)))).vehicles.single().isAhead)
        assertTrue(roundTrip(state(vehicles = listOf(target(lateralKnown = true)))).vehicles.single().lateralKnown)
        assertFalse(roundTrip(state(vehicles = listOf(target(lateralKnown = false)))).vehicles.single().lateralKnown)
    }

    @Test
    fun aClearLaneRoundTripsWithNoTargets() {
        val out = roundTrip(state(vehicles = emptyList(), isClear = true))
        assertEquals(emptyList<RadarVehicleParcel>(), out.vehicles)
    }

    @Test
    fun targetsInOneFrameKeepTheirOwnFlags() {
        // Distinct in every field, not just id: an id-only assertion passes even
        // if the writer reads the first target's booleans for all of them, and
        // a frame really does mix these (a far lateral-unknown track beside a
        // near one, or a target mid-overtake).
        val vs = listOf(
            target(id = 3, distanceM = 40, isAhead = false, lateralKnown = true),
            target(id = 1, distanceM = 12, isAhead = true, lateralKnown = false),
            target(id = 2, distanceM = 25, isAhead = false, lateralKnown = false),
        )
        assertEquals(vs, roundTrip(state(vehicles = vs)).vehicles)
    }

    @Test
    fun aParcelFromAnUnknownVersionReadsAsNotLive() {
        // A newer writer may have added a field INSIDE the repeated target
        // group, which this reader would consume as the next target's id. It
        // must not throw: createFromParcel runs in the CONSUMER's unmarshalling
        // path, so an exception takes that process down on every frame and a
        // future version bump would break every shipped consumer at once. A
        // not-live snapshot is a shape they already handle.
        for (bad in listOf(RadarContract.VERSION + 1, 0, -1)) {
            val p = Parcel.obtain()
            try {
                p.writeInt(bad)
                p.writeLong(99L)
                p.writeFloat(4.5f)
                p.writeByte(1)
                p.writeByte(1)
                p.writeByte(1)
                p.writeInt(15)
                p.writeInt(1)
                repeat(8) { p.writeInt(7) }
                p.setDataPosition(0)

                val out = RadarStateParcel.CREATOR.createFromParcel(p)
                assertFalse("version $bad is uninterpretable, so nothing is delivering", out.streamLive)
                assertEquals(emptyList<RadarVehicleParcel>(), out.vehicles)
                assertEquals(0, out.capabilities)
                assertFalse(out.riderSpeedKnown)
                assertEquals(bad, out.version)
                assertEquals("the whole parcel must be consumed", 0, p.dataAvail())
            } finally {
                p.recycle()
            }
        }
    }

    @Test
    fun aParcelWithExtraTrailingStateFieldsIsReadWithoutThem() {
        val p = Parcel.obtain()
        try {
            // Hand-built as a future version would write it: same prefix, then
            // fields this reader knows nothing about.
            p.writeInt(1)
            p.writeLong(99L)
            p.writeFloat(4.5f)
            p.writeByte(1)
            p.writeByte(1)
            p.writeByte(1)
            p.writeInt(15)
            p.writeInt(1)
            p.writeInt(5)
            p.writeInt(20)
            p.writeInt(30)
            p.writeInt(1)
            p.writeFloat(0.5f)
            p.writeFloat(2f)
            p.writeByte(1)
            p.writeByte(0)
            p.writeInt(1234)
            p.writeString("a field added later")
            p.setDataPosition(0)

            val out = RadarStateParcel.CREATOR.createFromParcel(p)
            assertEquals(1, out.version)
            assertEquals(99L, out.timestamp)
            assertEquals(4.5f, out.bikeSpeedMs, 0f)
            assertTrue(out.isClear)
            assertEquals(15, out.capabilities)
            assertEquals(target(5, 20, 30, 1, 0.5f, 2f, isAhead = true, lateralKnown = false), out.vehicles.single())
        } finally {
            p.recycle()
        }
    }

    @Test
    fun aTargetCostsTheBytesTheBoundsCheckAssumes() {
        // Measured rather than asserted against itself: the bounds check is only
        // sound while the constant is at or below what a target really costs.
        val one = Parcel.obtain()
        val two = Parcel.obtain()
        try {
            state(vehicles = listOf(target())).writeToParcel(one, 0)
            state(vehicles = listOf(target(), target())).writeToParcel(two, 0)
            val measured = two.dataSize() - one.dataSize()
            assertEquals("a target's wire cost moved", 32, measured)
            assertEquals(
                "the guard's constant no longer matches what a target costs",
                measured,
                RadarStateParcel.BYTES_PER_VEHICLE,
            )
        } finally {
            one.recycle()
            two.recycle()
        }
    }

    @Test
    fun aCountLargerThanTheTrailingBytesCanHoldIsRefused() {
        // The case a header-only fixture cannot reach: a plausible count with
        // real target data behind it, but not enough of it. Reading past the end
        // of a Parcel yields zeros, so without this guard the caller receives
        // phantom targets at distance zero instead of an error.
        val p = Parcel.obtain()
        try {
            p.writeInt(1)
            p.writeLong(0L)
            p.writeFloat(0f)
            p.writeByte(0)
            p.writeByte(0)
            p.writeByte(1)
            p.writeInt(0)
            p.writeInt(8)
            repeat(8) { p.writeInt(0) } // 32 bytes: one target's worth, not eight
            p.setDataPosition(0)
            assertThrows(BadParcelableException::class.java) {
                RadarStateParcel.CREATOR.createFromParcel(p)
            }
        } finally {
            p.recycle()
        }
    }

    @Test
    fun anImpossibleTargetCountIsRefusedRatherThanAllocated() {
        for (bogus in listOf(-1, Int.MAX_VALUE, 100_000)) {
            val p = Parcel.obtain()
            try {
                p.writeInt(1)
                p.writeLong(0L)
                p.writeFloat(0f)
                p.writeByte(0)
                p.writeByte(0)
                p.writeByte(1)
                p.writeInt(0)
                p.writeInt(bogus)
                p.setDataPosition(0)
                assertThrows(BadParcelableException::class.java) {
                    RadarStateParcel.CREATOR.createFromParcel(p)
                }
            } finally {
                p.recycle()
            }
        }
    }
}
