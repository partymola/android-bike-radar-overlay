// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * Guard tests for the UUID table. These pin the load-bearing constants that
 * the AMV handshake depends on - in particular the rear-radar / front-camera
 * pair separation (mixing the pairs is a documented silent-handshake-failure
 * mode) and the standard CCCD descriptor UUID.
 *
 * The radar service / characteristic UUIDs are part of the wire contract;
 * if any of these flips, the handshake or scan filter breaks on hardware,
 * so the exact strings are pinned here as a compile-time tripwire.
 */
class UuidsTest {

    private fun u(s: String) = UUID.fromString(s)

    @Test fun rearRadarAndFrontCameraAmvPairsAreDistinct() {
        // Rear radar drives TX=2821 / RX=2811; front camera drives
        // TX=2820 / RX=2810. The two pairs MUST be disjoint: writing the
        // rear pair to the front device (or vice-versa) is accepted but
        // never answered correctly - a silent failure. RadarUnlock selects
        // the pair by DeviceVariant; this guards the table it selects from.
        assertNotEquals(Uuids.HANDSHAKE_TX, Uuids.CHAR_2820)
        assertNotEquals(Uuids.HANDSHAKE_RX, Uuids.CHAR_2810)
        // And TX must never equal RX within a pair.
        assertNotEquals(Uuids.HANDSHAKE_TX, Uuids.HANDSHAKE_RX)
        assertNotEquals(Uuids.CHAR_2820, Uuids.CHAR_2810)
    }

    @Test fun amvPairUuidsMatchTheWireContract() {
        assertEquals(u("6a4e2821-667b-11e3-949a-0800200c9a66"), Uuids.HANDSHAKE_TX)
        assertEquals(u("6a4e2811-667b-11e3-949a-0800200c9a66"), Uuids.HANDSHAKE_RX)
        assertEquals(u("6a4e2820-667b-11e3-949a-0800200c9a66"), Uuids.CHAR_2820)
        assertEquals(u("6a4e2810-667b-11e3-949a-0800200c9a66"), Uuids.CHAR_2810)
    }

    @Test fun cccdIsTheStandardBluetoothDescriptor() {
        // The Client Characteristic Configuration Descriptor is fixed by the
        // Bluetooth spec; subscribing notifies writes this descriptor.
        assertEquals(u("00002902-0000-1000-8000-00805f9b34fb"), Uuids.CCCD)
    }

    @Test fun v1AndV2RadarCharsAreDistinct() {
        // 6a4e3203 is the V1 cleartext stream; 6a4e3204 is V2. They must be
        // different chars - subscribing 3203's CCCD locks the radar to
        // V1-only and suppresses V2, so they are deliberately not the same.
        assertEquals(u("6a4e3203-667b-11e3-949a-0800200c9a66"), Uuids.RADAR_V1)
        assertEquals(u("6a4e3204-667b-11e3-949a-0800200c9a66"), Uuids.RADAR_V2)
        assertNotEquals(Uuids.RADAR_V1, Uuids.RADAR_V2)
    }

    @Test fun companyScanFilterUuidIsLeftAsAdvertised() {
        // The scan filter MUST match the 16-bit company UUID, not the radar
        // service UUID, or the front camera/light is never discovered.
        assertEquals("0000fe1f", Uuids.COMPANY_UUID_HEX)
    }

    @Test fun allReferencedUuidsAreUnique() {
        // No two distinct constants may collide - a duplicated UUID would
        // route GATT operations to the wrong characteristic.
        val all = listOf(
            Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY,
            Uuids.SVC_CONFIG, Uuids.HANDSHAKE_TX, Uuids.HANDSHAKE_RX,
            Uuids.SVC_RADAR, Uuids.RADAR_V1, Uuids.RADAR_V2,
            Uuids.SVC_CONTROL, Uuids.SETTINGS_ACK, Uuids.SETTINGS_12, Uuids.SETTINGS_14,
            Uuids.CHAR_2803, Uuids.CHAR_2810, Uuids.CHAR_2812, Uuids.CHAR_2820, Uuids.CHAR_2822,
            Uuids.SVC_DIS, Uuids.DIS_MODEL_NUMBER, Uuids.DIS_SERIAL_NUMBER,
            Uuids.DIS_FIRMWARE_REV, Uuids.DIS_SOFTWARE_REV,
            Uuids.CCCD,
        )
        assertEquals("UUID table has a duplicate entry", all.size, all.toSet().size)
    }
}
