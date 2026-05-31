// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt

/**
 * Rear-radar tail-light modes, each carrying its stable wire "type" byte
 * ([typeByte]). Unlike the front camera's modes - which are addressed by an
 * ordinal that maps to a user-configurable cycle slot - the radar light is set
 * by TYPE (`06 09 01 TT`), so selecting one is an output override that never
 * disturbs the rider's button-cycle ("slot list") configuration in the vendor
 * app. Type bytes verified on a bench sweep against the live radar
 * (RearVue8): each value below produced its named mode, including Peloton which
 * was NOT in the rider's slot list - confirming type-set is slot-independent.
 */
enum class RadarLightMode(val typeByte: Int) {
    NIGHT_FLASH(0x14),
    DAY_FLASH(0x13),
    SOLID(0x11),
    PELOTON(0x12),
    OFF(0x1f),
}

/**
 * Issues mode-set writes to the rear radar's tail light over the existing radar
 * GATT connection (the radar light shares the radar's link - there is no
 * separate device, unlike the front camera).
 *
 * Wire format: `06 09 01 TT` to the control char [Uuids.SETTINGS_ACK] (6a4e2f11)
 * using WRITE_TYPE_DEFAULT, where TT = [RadarLightMode.typeByte]. This sets the
 * light output by type and does NOT move the radar's selected cycle slot, so the
 * rider's vendor-app slot configuration is preserved.
 *
 * Note on read-back: the radar reports mode-state on 6a4e2f14 as the SELECTED
 * SLOT's mode, not the type-override - so the 2f14 notify will NOT echo a
 * type-set. [parseModeState] is therefore used only to detect a rider's
 * manual button press (which DOES move the slot and change 2f14), not to
 * confirm our own writes.
 */
@SuppressLint("MissingPermission")
class RadarLightController(
    private val gatt: BluetoothGatt,
    private val queue: BleOpQueue,
) {
    /**
     * Writes `06 09 01 TT` to the control char. Returns false if the control
     * service / characteristic is not present on this connection.
     */
    suspend fun setMode(mode: RadarLightMode): Boolean {
        val ch = gatt.getService(Uuids.SVC_CONTROL)
            ?.getCharacteristic(Uuids.SETTINGS_ACK) ?: return false
        return queue.write(gatt, ch, encodeWrite(mode), noResponse = false)
    }

    /** The selected-slot mode-state the radar reports on 6a4e2f14. [slot] is the
     *  cycle-slot ordinal, [type] the mode type byte. Used for override
     *  detection (a slot change = a rider button press), NOT to confirm our own
     *  type-overrides (which never update 2f14). */
    data class ModeState(val slot: Int, val type: Int)

    companion object {
        /** The 4-byte mode-set payload `06 09 01 TT` for [mode]. */
        fun encodeWrite(mode: RadarLightMode): ByteArray = byteArrayOf(0x06, 0x09, 0x01, mode.typeByte.toByte())

        /**
         * Parses a 6a4e2f14 notify into the selected-slot [ModeState], or null
         * if the frame is not a mode-state record. Mode-state is the 4-byte
         * `01 [slot] ff [type]`; the channel also carries an 11-byte config blob
         * and a 2-byte counter, which return null.
         */
        fun parseModeState(value: ByteArray): ModeState? {
            if (value.size != 4 || value[0] != 0x01.toByte() || value[2] != 0xff.toByte()) return null
            return ModeState(slot = value[1].toInt() and 0xFF, type = value[3].toInt() and 0xFF)
        }

        /** The [RadarLightMode] for a wire type byte, or null if unrecognised. */
        fun fromType(typeByte: Int): RadarLightMode? = RadarLightMode.entries.firstOrNull { it.typeByte == typeByte }
    }
}
