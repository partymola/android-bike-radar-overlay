// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The class-template size bytes reach [Vehicle], unscaled by anything but the
 * wire's 0.25 m quantum, so the overlay can place a locked track's near edge.
 */
class RadarV2DecoderTemplateSizeTest {

    private val decoder = RadarV2Decoder(nowMs = { 1_000L })

    private fun decode(lengthByte: Int, widthByte: Int): Vehicle {
        val packed = (150 shl 11) or 0
        val struct = byteArrayOf(
            7,
            RadarV2Decoder.CLASS_MODERATE.toByte(),
            (packed and 0xFF).toByte(),
            ((packed shr 8) and 0xFF).toByte(),
            ((packed shr 16) and 0xFF).toByte(),
            lengthByte.toByte(),
            widthByte.toByte(),
            (-4).toByte(),
            0x80.toByte(),
        )
        return decoder.feed(byteArrayOf(0x02, 0x00) + struct)!!.vehicles.single()
    }

    @Test
    fun aTrackPicksUpItsTemplateOnTheFrameItLocks() {
        val unlocked = decode(lengthByte = 0, widthByte = 0)
        assertEquals(0f, unlocked.templateLengthM, 0f)
        val locked = decode(lengthByte = 16, widthByte = 7)
        assertEquals(unlocked.id, locked.id)
        assertEquals(4.0f, locked.templateLengthM, 0f)
        assertEquals(1.75f, locked.templateWidthM, 0f)
    }

    @Test
    fun theCarTemplateDecodesToFourByOneSeventyFive() {
        val v = decode(lengthByte = 16, widthByte = 7)
        assertEquals(4.0f, v.templateLengthM, 0f)
        assertEquals(1.75f, v.templateWidthM, 0f)
    }

    @Test
    fun anUnlockedTrackCarriesZeroForBoth() {
        val v = decode(lengthByte = 0, widthByte = 0)
        assertEquals(0f, v.templateLengthM, 0f)
        assertEquals(0f, v.templateWidthM, 0f)
    }

    @Test
    fun theLongTemplateDecodesToFifteenByTwoTwentyFive() {
        val v = decode(lengthByte = 60, widthByte = 9)
        assertEquals(15.0f, v.templateLengthM, 0f)
        assertEquals(2.25f, v.templateWidthM, 0f)
    }

    @Test
    fun theBytesAreReadUnsigned() {
        val v = decode(lengthByte = 0xFF, widthByte = 0x81)
        assertEquals(63.75f, v.templateLengthM, 0f)
        assertEquals(32.25f, v.templateWidthM, 0f)
    }
}
