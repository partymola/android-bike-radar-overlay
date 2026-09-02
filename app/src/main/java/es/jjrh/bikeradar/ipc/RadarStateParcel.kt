// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 JJ del Rio
// Permissive so another app can copy this contract into its own build.
// Licence text: LICENSES/Apache-2.0.txt. Consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable

/**
 * A snapshot of the rear radar's tracked targets, as carried over the
 * cross-app contract.
 *
 * [version] is written FIRST and read FIRST. That ordering is the whole
 * compatibility story: the two apps ship their own copies of this layout and
 * update on different schedules, so a reader has to know which layout it is
 * looking at before it reads anything else.
 *
 * The only change that does not bump [RadarContract.VERSION] is appending a
 * field at the very END of the parcel. Everything else bumps: inserting
 * anywhere earlier, changing a field's type, removing one, reordering, or
 * touching the repeated target group.
 *
 * Keyed on POSITION rather than on state-versus-target, because those coincide
 * only while the target array is last. A same-width type change is the case
 * that makes the distinction matter: an older reader would decode the new bits
 * as the old type with every length check still passing.
 *
 * A reader of an appended field must guard it with [Parcel.dataAvail] and
 * document what its absence means, since a short parcel yields zeros and a
 * zero is indistinguishable from a written value.
 *
 * A version this reader does not know produces a not-live snapshot rather than
 * an exception. Throwing would run inside the CONSUMER's unmarshalling path and
 * take its process down on every frame, so a future bump would break every
 * shipped consumer at once. A not-live snapshot is a shape they already handle,
 * because it is the one an app with no radar produces.
 *
 * [capabilities] says which of the per-target numbers are measurements rather
 * than defaults. See [RadarContract].
 */
class RadarStateParcel(
    /**
     * When this snapshot was taken, as [System.currentTimeMillis]. Wall clock,
     * so it is comparable with a consumer's own `currentTimeMillis` and NOT with
     * `elapsedRealtime`, and it can step under an NTP correction.
     *
     * Zero appears when a link is torn down. It is NOT how an app with no radar
     * yet reads, which carries a real clock, so do not use it to detect an
     * absent radar. [streamLive] is that answer.
     */
    val timestamp: Long,
    val vehicles: List<RadarVehicleParcel>,
    val bikeSpeedMs: Float,
    /**
     * False until the radar has reported the rider's own speed this session.
     * [bikeSpeedMs] is then a default, not a reading, and a zero must not be
     * taken for a stationary rider.
     *
     * Separate from the [capabilities] bit because that says what the STREAM can
     * measure and stays true all session, while this says whether it has done so
     * YET. A V2 radar sets the bit the moment it connects and needs a frame or
     * two before the value behind it means anything.
     */
    val riderSpeedKnown: Boolean,
    /**
     * True when a radar link is delivering. False before one has ever connected
     * and after one drops.
     *
     * [isClear] is meaningless without this. An app with no radar attached
     * reports no targets, which is the same shape as a radar reporting an empty
     * road, and telling those apart is not something the other fields can do:
     * a range-only radar seeing nothing and no radar at all both carry
     * `capabilities` of zero.
     */
    val streamLive: Boolean,
    /**
     * True when this snapshot holds no targets. Only meaningful while
     * [streamLive]; see there.
     */
    val isClear: Boolean,
    val capabilities: Int,
    val version: Int = RadarContract.VERSION,
) : Parcelable {

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(version)
        parcel.writeLong(timestamp)
        parcel.writeFloat(bikeSpeedMs)
        parcel.writeByte(if (riderSpeedKnown) 1 else 0)
        parcel.writeByte(if (streamLive) 1 else 0)
        parcel.writeByte(if (isClear) 1 else 0)
        parcel.writeInt(capabilities)
        parcel.writeInt(vehicles.size)
        for (v in vehicles) {
            parcel.writeInt(v.id)
            parcel.writeInt(v.distanceM)
            parcel.writeInt(v.closingKmh)
            parcel.writeInt(v.size)
            parcel.writeFloat(v.lateralPos)
            parcel.writeFloat(v.rangeXm)
            parcel.writeByte(if (v.isAhead) 1 else 0)
            parcel.writeByte(if (v.lateralKnown) 1 else 0)
        }
    }

    override fun describeContents(): Int = 0

    companion object {
        /**
         * Bytes one target occupies on the wire: four ints, two floats, and two
         * booleans that [Parcel.writeByte] widens to ints. Measured, and pinned
         * by `aTargetCostsTheBytesTheBoundsCheckAssumes`.
         *
         * The bounds check needs a LOWER bound, and this stays one as the layout
         * grows: a future version appends fields, so a target only ever costs
         * more. Understating it is what fails dangerously. Android's [Parcel]
         * returns zeros past the end rather than throwing, so a count this check
         * lets through is read as real targets at distance zero.
         */
        internal const val BYTES_PER_VEHICLE = 32

        /**
         * What a reader returns when it cannot interpret the parcel: no radar
         * is delivering, so nothing else in it means anything.
         */
        private fun notLive(version: Int) = RadarStateParcel(
            timestamp = 0L,
            vehicles = emptyList(),
            bikeSpeedMs = 0f,
            riderSpeedKnown = false,
            streamLive = false,
            isClear = true,
            capabilities = 0,
            version = version,
        )

        @JvmField
        val CREATOR = object : Parcelable.Creator<RadarStateParcel> {
            override fun createFromParcel(parcel: Parcel): RadarStateParcel {
                val version = parcel.readInt()
                if (version !in 1..RadarContract.VERSION) {
                    // Skip anything a newer writer appended that this build has no meaning
                    // for. This jumps to the end of the WHOLE parcel, so it is only correct
                    // while this is the last thing in it - true today because it is the
                    // sole argument of a one-way call. A second argument would need the
                    // reader to stop at its own end instead.
                    parcel.setDataPosition(parcel.dataSize())
                    return notLive(version)
                }
                val timestamp = parcel.readLong()
                val bikeSpeedMs = parcel.readFloat()
                val riderSpeedKnown = parcel.readByte() != 0.toByte()
                val streamLive = parcel.readByte() != 0.toByte()
                val isClear = parcel.readByte() != 0.toByte()
                val capabilities = parcel.readInt()
                val count = parcel.readInt()
                if (count < 0 || count.toLong() * BYTES_PER_VEHICLE > parcel.dataAvail()) {
                    throw BadParcelableException("implausible radar target count: $count")
                }
                val vehicles = ArrayList<RadarVehicleParcel>(count)
                repeat(count) {
                    vehicles.add(
                        RadarVehicleParcel(
                            id = parcel.readInt(),
                            distanceM = parcel.readInt(),
                            closingKmh = parcel.readInt(),
                            size = parcel.readInt(),
                            lateralPos = parcel.readFloat(),
                            rangeXm = parcel.readFloat(),
                            isAhead = parcel.readByte() != 0.toByte(),
                            lateralKnown = parcel.readByte() != 0.toByte(),
                        ),
                    )
                }
                return RadarStateParcel(
                    timestamp = timestamp,
                    vehicles = vehicles,
                    bikeSpeedMs = bikeSpeedMs,
                    riderSpeedKnown = riderSpeedKnown,
                    streamLive = streamLive,
                    isClear = isClear,
                    capabilities = capabilities,
                    version = version,
                )
            }

            override fun newArray(size: Int): Array<RadarStateParcel?> = arrayOfNulls(size)
        }
    }
}
