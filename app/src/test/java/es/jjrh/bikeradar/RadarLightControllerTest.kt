// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the radar tail-light wire encoding. The mode-set is `06 09 01 TT` (set by
 * stable type, slot-independent - verified on-bench) and the 6a4e2f14 mode-state
 * is `01 [slot] ff [type]`. These must match the radar's firmware exactly, so the
 * byte values are asserted literally.
 */
class RadarLightControllerTest {

    @Test fun encodeWriteIs060901PlusTypeByte() {
        assertArrayEquals(byteArrayOf(0x06, 0x09, 0x01, 0x14), RadarLightController.encodeWrite(RadarLightMode.NIGHT_FLASH))
        assertArrayEquals(byteArrayOf(0x06, 0x09, 0x01, 0x13), RadarLightController.encodeWrite(RadarLightMode.DAY_FLASH))
        assertArrayEquals(byteArrayOf(0x06, 0x09, 0x01, 0x11), RadarLightController.encodeWrite(RadarLightMode.SOLID))
        assertArrayEquals(byteArrayOf(0x06, 0x09, 0x01, 0x12), RadarLightController.encodeWrite(RadarLightMode.PELOTON))
        assertArrayEquals(byteArrayOf(0x06, 0x09, 0x01, 0x1f), RadarLightController.encodeWrite(RadarLightMode.OFF))
    }

    @Test fun parseModeStateAcceptsTheFourByteRecord() {
        val ms = RadarLightController.parseModeState(byteArrayOf(0x01, 0x00, 0xff.toByte(), 0x14))
        assertEquals(0, ms!!.slot)
        assertEquals(0x14, ms.type)
        val ms2 = RadarLightController.parseModeState(byteArrayOf(0x01, 0x02, 0xff.toByte(), 0x1f))
        assertEquals(2, ms2!!.slot)
        assertEquals(0x1f, ms2.type)
    }

    @Test fun parseModeStateRejectsNonModeStateFrames() {
        // 11-byte config blob, 2-byte counter, wrong tag, missing 0xff marker.
        assertNull(RadarLightController.parseModeState(byteArrayOf(0, 0x60, 0, 0, 0, 0, 0x12, 0, 0, 0, 0)))
        assertNull(RadarLightController.parseModeState(byteArrayOf(0x02, 0x03)))
        assertNull(RadarLightController.parseModeState(byteArrayOf(0x00, 0x00, 0xff.toByte(), 0x14)))
        assertNull(RadarLightController.parseModeState(byteArrayOf(0x01, 0x00, 0x00, 0x14)))
    }

    @Test fun fromTypeRoundTripsEveryModeAndRejectsUnknown() {
        RadarLightMode.entries.forEach { assertEquals(it, RadarLightController.fromType(it.typeByte)) }
        assertNull(RadarLightController.fromType(0x99))
        assertNull(RadarLightController.fromType(0x10)) // no-op type observed on-bench
    }
}
