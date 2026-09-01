// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import android.os.Parcel
import android.os.Parcelable

/** Wire size codes shared with the contract (0=car, 1=truck, 2=bike). */
const val RADAR_SIZE_CAR = 0
const val RADAR_SIZE_TRUCK = 1
const val RADAR_SIZE_BIKE = 2

/**
 * Parcelable mirror of one rear-radar target. Field order and types define the
 * IPC wire layout (the cross-app contract); the consumer app ships its own
 * implementation of the same layout.
 *
 * Note on [distanceM]: by default it is the distance BEHIND the rider. When
 * [isAhead] is true the target has passed the rider and [distanceM] instead
 * means the distance AHEAD. An ahead reading is inherently less reliable — the
 * rear radar is mostly "looking back" — so consumers should present it with
 * care.
 */
class RadarVehicleParcel(
    val id: Int,
    val distanceM: Int,
    val closingKmh: Int,
    val size: Int,
    val lateralPos: Float,
    val rangeXm: Float,
    val isAhead: Boolean,
) : Parcelable {

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeInt(distanceM)
        parcel.writeInt(closingKmh)
        parcel.writeInt(size)
        parcel.writeFloat(lateralPos)
        parcel.writeFloat(rangeXm)
        parcel.writeByte(if (isAhead) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<RadarVehicleParcel> {
            override fun createFromParcel(parcel: Parcel): RadarVehicleParcel = RadarVehicleParcel(
                id = parcel.readInt(),
                distanceM = parcel.readInt(),
                closingKmh = parcel.readInt(),
                size = parcel.readInt(),
                lateralPos = parcel.readFloat(),
                rangeXm = parcel.readFloat(),
                isAhead = parcel.readByte() != 0.toByte(),
            )

            override fun newArray(size: Int): Array<RadarVehicleParcel?> = arrayOfNulls(size)
        }
    }
}
