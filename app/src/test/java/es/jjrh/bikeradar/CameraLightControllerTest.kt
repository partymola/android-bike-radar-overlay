// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// ── Wire-format snapshot: encodeWrite ────────────────────────────────────────
/**
 * Snapshot-pins the 3-byte write payload for each [CameraLightMode].
 * If anyone edits the ordinal order or the header bytes, these fail immediately.
 */
class CameraLightModeWireFormatTest {

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test fun encodeHigh()       = assertEquals("070001", CameraLightController.encodeWrite(CameraLightMode.HIGH).toHex())
    @Test fun encodeMedium()     = assertEquals("070002", CameraLightController.encodeWrite(CameraLightMode.MEDIUM).toHex())
    @Test fun encodeLow()        = assertEquals("070003", CameraLightController.encodeWrite(CameraLightMode.LOW).toHex())
    @Test fun encodeNightFlash() = assertEquals("070004", CameraLightController.encodeWrite(CameraLightMode.NIGHT_FLASH).toHex())
    @Test fun encodeDayFlash()   = assertEquals("070005", CameraLightController.encodeWrite(CameraLightMode.DAY_FLASH).toHex())
    @Test fun encodeOff()        = assertEquals("070006", CameraLightController.encodeWrite(CameraLightMode.OFF).toHex())

    @Test fun allModesHaveUniquePayloads() {
        val payloads = CameraLightMode.entries.map { CameraLightController.encodeWrite(it).toHex() }
        assertEquals("each mode must produce a distinct payload", payloads.size, payloads.toSet().size)
    }
}

// ── Mode-state notify parser ───────────────────────────────────────────────────
class CameraLightModeNotifyParserTest {

    @Test fun parseHigh()       = assertEquals(CameraLightMode.HIGH,       CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x00, 0x10.toByte())))
    @Test fun parseMedium()     = assertEquals(CameraLightMode.MEDIUM,     CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x01, 0x11.toByte())))
    @Test fun parseLow()        = assertEquals(CameraLightMode.LOW,        CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x02, 0x12.toByte())))
    @Test fun parseNightFlash() = assertEquals(CameraLightMode.NIGHT_FLASH,CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x03, 0x13.toByte())))
    @Test fun parseDayFlash()   = assertEquals(CameraLightMode.DAY_FLASH,  CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x04, 0x14.toByte())))
    @Test fun parseOff()        = assertEquals(CameraLightMode.OFF,        CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x05, 0x1f.toByte())))

    @Test fun emptyReturnsNull()        = assertNull(CameraLightController.parseModeStateNotify(ByteArray(0)))
    @Test fun wrongLeadingByteNull()    = assertNull(CameraLightController.parseModeStateNotify(byteArrayOf(0x00, 0x00, 0x10.toByte())))
    @Test fun wrongLengthTwoNull()      = assertNull(CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x00)))
    @Test fun wrongLengthFourNull()     = assertNull(CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x00, 0x10.toByte(), 0x00)))
    @Test fun unknownOrdinalReturnsNull() = assertNull(CameraLightController.parseModeStateNotify(byteArrayOf(0x01, 0x06, 0x00)))
}

// ── 0x18 sub-mode toggle frame byte pins ─────────────────────────────────────
/**
 * Verifies the frame constants in [RadarUnlock] match the wire spec:
 * `00 SS 00 00 00 00 00 00 41 4d 56 18 PP` (13 bytes).
 */
class SubmodeToggleEncoderTest {

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) {
            ((Character.digit(this[it * 2], 16) shl 4) or Character.digit(this[it * 2 + 1], 16)).toByte()
        }
    }

    private fun checkFrame(hex: String, ss: Byte, pp: Byte) {
        val b = hex.hexToBytes()
        assertEquals("frame must be 13 bytes", 13, b.size)
        assertEquals("byte[0] must be 0x00", 0x00.toByte(), b[0])
        assertEquals("byte[1] must be SS=0x${"%02x".format(ss)}", ss, b[1])
        // bytes 2-7 are zero padding
        for (i in 2..7) assertEquals("byte[$i] must be 0x00", 0x00.toByte(), b[i])
        assertEquals("byte[8] must be 0x41 (A)", 0x41.toByte(), b[8])
        assertEquals("byte[9] must be 0x4d (M)", 0x4d.toByte(), b[9])
        assertEquals("byte[10] must be 0x56 (V)", 0x56.toByte(), b[10])
        assertEquals("byte[11] must be 0x18 (opcode)", 0x18.toByte(), b[11])
        assertEquals("byte[12] must be PP=0x${"%02x".format(pp)}", pp, b[12])
    }

    @Test fun frame1MatchesSpec() = checkFrame(RadarUnlock.SUBMODE_FRAME_1, ss = 0x00, pp = 0x02)
    @Test fun frame2MatchesSpec() = checkFrame(RadarUnlock.SUBMODE_FRAME_2, ss = 0x02, pp = 0x82.toByte())
    @Test fun frame3MatchesSpec() = checkFrame(RadarUnlock.SUBMODE_FRAME_3, ss = 0x00, pp = 0x02)

    // Frame 3 sends the same bytes as frame 1 by design: the device advances
    // internal toggle state based on sequence position, not payload content.
    @Test fun frame1And3AreIdentical() =
        assertArrayEquals(RadarUnlock.SUBMODE_FRAME_1.hexToBytes(), RadarUnlock.SUBMODE_FRAME_3.hexToBytes())
}
