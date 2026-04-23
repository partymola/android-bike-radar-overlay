// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import java.util.UUID

object Uuids {
    // Garmin 16-bit company UUID. Both Vue and RearVue advertise this;
    // neither advertises 6a4e2xxx in advert data. Scan filter MUST use
    // 0000fe1f, not the radar service UUID, or the Vue will be missed.
    const val COMPANY_UUID_HEX = "0000fe1f"

    // Standard Battery Service (both Vue and RearVue820)
    val SVC_BATTERY: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val CHAR_BATTERY: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // Config / handshake service (rear radar only)
    val SVC_CONFIG: UUID = UUID.fromString("6a4e2800-667b-11e3-949a-0800200c9a66")
    val HANDSHAKE_TX: UUID = UUID.fromString("6a4e2821-667b-11e3-949a-0800200c9a66")  // WRITE-NO-RESP
    val HANDSHAKE_RX: UUID = UUID.fromString("6a4e2811-667b-11e3-949a-0800200c9a66")  // NOTIFY

    // Radar service (rear radar only)
    val SVC_RADAR: UUID = UUID.fromString("6a4e3200-667b-11e3-949a-0800200c9a66")
    // V1 cleartext stream. We receive V1 frames WITHOUT writing its CCCD -
    // the radar broadcasts 3203 regardless. Subscribing the CCCD signals us
    // as a legacy V1 client and locks the radar to 3203-only, suppressing 3204.
    val RADAR_V1: UUID = UUID.fromString("6a4e3203-667b-11e3-949a-0800200c9a66")  // NOTIFY (no CCCD write)
    val RADAR_V2: UUID = UUID.fromString("6a4e3204-667b-11e3-949a-0800200c9a66")  // NOTIFY (subscribe post-handshake)

    // Control / settings service (rear radar only)
    val SVC_CONTROL: UUID = UUID.fromString("6a4e2f00-667b-11e3-949a-0800200c9a66")
    val SETTINGS_ACK: UUID = UUID.fromString("6a4e2f11-667b-11e3-949a-0800200c9a66")  // INDICATE
    val SETTINGS_12: UUID = UUID.fromString("6a4e2f12-667b-11e3-949a-0800200c9a66")   // NOTIFY
    val SETTINGS_14: UUID = UUID.fromString("6a4e2f14-667b-11e3-949a-0800200c9a66")   // INDICATE

    // Other config-service chars referenced in the handshake sequence
    val CHAR_2803: UUID = UUID.fromString("6a4e2803-667b-11e3-949a-0800200c9a66")
    val CHAR_2810: UUID = UUID.fromString("6a4e2810-667b-11e3-949a-0800200c9a66")
    val CHAR_2812: UUID = UUID.fromString("6a4e2812-667b-11e3-949a-0800200c9a66")
    val CHAR_2820: UUID = UUID.fromString("6a4e2820-667b-11e3-949a-0800200c9a66")
    val CHAR_2822: UUID = UUID.fromString("6a4e2822-667b-11e3-949a-0800200c9a66")

    // Standard BLE Device Information Service (0x180A)
    val SVC_DIS: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DIS_MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DIS_SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val DIS_FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val DIS_SOFTWARE_REV: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")

    // Standard CCCD descriptor UUID
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
