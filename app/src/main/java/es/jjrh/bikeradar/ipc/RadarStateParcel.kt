// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable snapshot of the rear-radar's tracked targets, pushed at ~5 Hz.
 * Field order and types define the IPC wire layout (the cross-app contract).
 */
class RadarStateParcel(
    val timestamp: Long,
    val vehicles: Array<RadarVehicleParcel>,
    val bikeSpeedMs: Float,
    val isClear: Boolean,
) : Parcelable {

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(timestamp)
        parcel.writeInt(vehicles.size)
        for (v in vehicles) parcel.writeParcelable(v, flags)
        parcel.writeFloat(bikeSpeedMs)
        parcel.writeByte(if (isClear) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<RadarStateParcel> {
            override fun createFromParcel(parcel: Parcel): RadarStateParcel {
                val timestamp = parcel.readLong()
                val count = parcel.readInt()

                @Suppress("UNCHECKED_CAST")
                val vehicles = arrayOfNulls<RadarVehicleParcel>(count)
                for (i in 0 until count) {
                    vehicles[i] = parcel.readParcelable(RadarVehicleParcel::class.java.classLoader)
                }
                return RadarStateParcel(
                    timestamp = timestamp,
                    vehicles = vehicles as Array<RadarVehicleParcel>,
                    bikeSpeedMs = parcel.readFloat(),
                    isClear = parcel.readByte() != 0.toByte(),
                )
            }

            override fun newArray(size: Int): Array<RadarStateParcel?> = arrayOfNulls(size)
        }
    }
}
