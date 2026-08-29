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
    val HANDSHAKE_TX: UUID = UUID.fromString("6a4e2821-667b-11e3-949a-0800200c9a66") // WRITE-NO-RESP
    val HANDSHAKE_RX: UUID = UUID.fromString("6a4e2811-667b-11e3-949a-0800200c9a66") // NOTIFY

    // Radar service (rear radar only)
    val SVC_RADAR: UUID = UUID.fromString("6a4e3200-667b-11e3-949a-0800200c9a66")

    // V1 cleartext stream. Subscribed ONLY on a radar whose service table has
    // no 6a4e3204 at all - never as a fallback on a radar that has one.
    //
    // Writing the CCCD before the unlock (fw 6.70) makes the radar unlock into
    // V1: the handshake still reports success, V1 heartbeats arrive here, and
    // 3204 never emits. It outlives the connection - later connections that
    // never touch the CCCD also got no V2, until the radar was power-cycled.
    // Silent failure: link up, handshake OK, zero targets.
    //
    // So the guard is on the SERVICE TABLE, not on how the handshake went: a
    // radar that advertises 3204 keeps V2 as its only stream however badly the
    // attempt ends, because a subscribe here costs it V2 until someone
    // power-cycles it, and V1 carries nothing V2 does not. A radar that has no
    // 3204 has no V2 to lose. Pinned by RadarLinkControllerHarnessTest >
    // aV2CapableRadarNeverFallsBackHoweverTheHandshakeEnds; do not relax it to
    // a handshake-outcome test.
    val RADAR_V1: UUID = UUID.fromString("6a4e3203-667b-11e3-949a-0800200c9a66") // NOTIFY (only when 3204 absent)
    val RADAR_V2: UUID = UUID.fromString("6a4e3204-667b-11e3-949a-0800200c9a66") // NOTIFY (subscribe post-handshake)

    // Control / settings service (rear radar only)
    val SVC_CONTROL: UUID = UUID.fromString("6a4e2f00-667b-11e3-949a-0800200c9a66")
    val SETTINGS_ACK: UUID = UUID.fromString("6a4e2f11-667b-11e3-949a-0800200c9a66") // INDICATE
    val SETTINGS_12: UUID = UUID.fromString("6a4e2f12-667b-11e3-949a-0800200c9a66") // NOTIFY
    val SETTINGS_14: UUID = UUID.fromString("6a4e2f14-667b-11e3-949a-0800200c9a66") // NOTIFY

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

    // Bosch smart-system proprietary "status" service the Flow app uses for
    // live telemetry (speed/SoC/cadence/power/odometer). This is NOT the
    // official LDI eb20/eb21 accessory protocol - LDI requires the bike to be
    // GAP central to the phone, which can't happen while Flow holds the single
    // phone<->bike link. This channel streams over that existing phone-central
    // link instead; we subscribe to its notify char and parse, read-only.
    val SVC_EBIKE_STATUS: UUID = UUID.fromString("00000010-eaa2-11e9-81b4-2a2ae2dbcce4")
    val CHAR_EBIKE_STATUS: UUID = UUID.fromString("00000011-eaa2-11e9-81b4-2a2ae2dbcce4")
}
