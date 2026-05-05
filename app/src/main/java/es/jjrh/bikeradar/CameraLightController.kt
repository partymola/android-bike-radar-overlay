// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt

/**
 * Named light modes for the front camera/light, in device-display order.
 * ordinal 0..5 corresponds to mode-state notify byte [1]; write byte is ordinal+1.
 */
enum class CameraLightMode { HIGH, MEDIUM, LOW, NIGHT_FLASH, DAY_FLASH, OFF }

/**
 * Issues mode-set writes to the front camera/light over an established GATT connection.
 *
 * Wire format: write `07 00 NN` to `SETTINGS_ACK` (6a4e2f11) using WRITE_TYPE_DEFAULT,
 * where NN = mode.ordinal + 1. The device echoes an indicate on the same characteristic
 * and emits a 3-byte state notify on `SETTINGS_14` (6a4e2f14) once the mode is applied.
 *
 * [setMode] guards the payload against out-of-range values before writing. The known-enum
 * guard prevents firmware from receiving an unexpected byte even if the caller somehow
 * passes a value outside the declared enum range.
 */
@SuppressLint("MissingPermission")
class CameraLightController(
    private val gatt: BluetoothGatt,
    private val queue: BleOpQueue,
) {
    /**
     * Writes `07 00 NN` to SETTINGS_ACK, where NN = mode.ordinal + 1.
     * Returns false if the characteristic is not found or the encoded byte
     * exceeds 0x06 (the known-safe ceiling).
     */
    suspend fun setMode(mode: CameraLightMode): Boolean {
        val payload = (mode.ordinal + 1).toByte()
        // Known-enum guard: reject any value above the documented ceiling even
        // if the enum were extended without updating this table.
        if (payload > 0x06) return false

        val ch = gatt.getService(Uuids.SVC_CONTROL)
            ?.getCharacteristic(Uuids.SETTINGS_ACK) ?: return false
        return queue.write(
            gatt, ch,
            byteArrayOf(0x07, 0x00, payload),
            noResponse = false,
        )
    }

    companion object {
        /** Returns the 3-byte payload for a SETTINGS_ACK write: `07 00 NN` where NN = ordinal+1. */
        fun encodeWrite(mode: CameraLightMode): ByteArray = byteArrayOf(0x07, 0x00, (mode.ordinal + 1).toByte())

        /**
         * Parses a SETTINGS_14 notify value into a [CameraLightMode].
         * Returns null for any frame that is not a 3-byte mode-state packet
         * (the channel carries multiple event types; only `len == 3 && bytes[0] == 0x01`
         * is a mode-state notification).
         */
        fun parseModeStateNotify(value: ByteArray): CameraLightMode? {
            if (value.size != 3 || value[0] != 0x01.toByte()) return null
            return CameraLightMode.entries.getOrNull(value[1].toInt() and 0xFF)
        }
    }
}
