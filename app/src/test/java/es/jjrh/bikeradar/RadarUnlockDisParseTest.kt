// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [RadarUnlock.parseDisUtf8] - the Device Information
 *  Service string parsing used to read the radar's firmware revision. */
class RadarUnlockDisParseTest {

    @Test fun `plain ascii revision parses`() {
        assertEquals("6.70", RadarUnlock.parseDisUtf8("6.70".toByteArray()))
    }

    @Test fun `nul padding and whitespace are stripped`() {
        assertEquals(
            "6.70",
            RadarUnlock.parseDisUtf8(byteArrayOf(0x36, 0x2E, 0x37, 0x30, 0x00, 0x00, 0x20)),
        )
    }

    @Test fun `empty or all-padding reads parse to null`() {
        assertNull(RadarUnlock.parseDisUtf8(ByteArray(0)))
        assertNull(RadarUnlock.parseDisUtf8(byteArrayOf(0x00, 0x00, 0x20)))
    }

    @Test fun `interior control bytes reject the read`() {
        assertNull(RadarUnlock.parseDisUtf8(byteArrayOf(0x36, 0x01, 0x37)))
    }

    @Test fun `implausibly long strings reject the read`() {
        assertNull(RadarUnlock.parseDisUtf8(ByteArray(40) { 0x41 }))
    }
}
