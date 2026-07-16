// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Birth annotations on decoded vehicles ([Vehicle.bornAtMs] /
 * [Vehicle.bornDistanceM] / [Vehicle.bornInformative] / raw lateral),
 * the inputs of the ghost-beep filter. The decoder is the
 * birth authority: its own track creation and prune events define
 * "born", so the gate's premise ("a real threat first seen close must
 * be closing") is evaluated against real coverage, not a downstream
 * approximation.
 */
class RadarV2DecoderBirthTest {

    private var now = 1_000L
    private val decoder = RadarV2Decoder(nowMs = { now })

    private fun packet(vararg targets: ByteArray): ByteArray = byteArrayOf(0x02, 0x00) + targets.fold(byteArrayOf()) { a, b -> a + b }

    private fun target(
        tid: Int,
        rangeY: Int,
        rangeX: Int = 0,
        speedYhalf: Int = 0,
    ): ByteArray {
        val rxBits = rangeX and 0x7FF
        val ryBits = rangeY and 0x1FFF
        val packed = (ryBits shl 11) or rxBits
        return byteArrayOf(
            tid.toByte(),
            RadarV2Decoder.CLASS_NORMAL.toByte(),
            (packed and 0xFF).toByte(),
            ((packed shr 8) and 0xFF).toByte(),
            ((packed shr 16) and 0xFF).toByte(),
            16,
            7,
            speedYhalf.toByte(),
            0x80.toByte(),
        )
    }

    /** Move past the post-connect warm-up window with an empty frame. */
    private fun warmUp() {
        decoder.feed(packet())
        now += RadarV2Decoder.BIRTH_WARMUP_MS + 1
    }

    @Test
    fun `birth facts are fixed on the first frame and survive updates`() {
        warmUp()
        decoder.feed(packet(target(tid = 1, rangeY = 400))) // born at 40 m
        val bornAt = now
        now += 90
        val v = decoder.feed(packet(target(tid = 1, rangeY = 80)))!!.vehicles.single()
        assertEquals(40, v.bornDistanceM)
        assertEquals(bornAt, v.bornAtMs)
        assertTrue(v.bornInformative)
    }

    @Test
    fun `births during the warm-up window are uninformative`() {
        val v = decoder.feed(packet(target(tid = 1, rangeY = 60)))!!.vehicles.single()
        assertFalse(v.bornInformative)
    }

    @Test
    fun `births after the warm-up window are informative`() {
        warmUp()
        val v = decoder.feed(packet(target(tid = 1, rangeY = 60)))!!.vehicles.single()
        assertTrue(v.bornInformative)
    }

    @Test
    fun `reset re-arms the warm-up window`() {
        warmUp()
        decoder.reset()
        val v = decoder.feed(packet(target(tid = 1, rangeY = 60)))!!.vehicles.single()
        assertFalse(v.bornInformative)
    }

    @Test
    fun `rebirth at similar range after a coverage gap is uninformative`() {
        warmUp()
        // Moving follower at 8 m, then a notification gap past the moving
        // prune (800 ms) - the decoder loses and re-acquires it.
        decoder.feed(packet(target(tid = 1, rangeY = 80, speedYhalf = -4)))
        now += RadarV2Decoder.STALE_MOVING_MS + 100
        val v = decoder.feed(packet(target(tid = 2, rangeY = 90, speedYhalf = -4)))!!
            .vehicles.single()
        assertFalse(v.bornInformative)
    }

    @Test
    fun `birth far from any recent death is informative`() {
        warmUp()
        decoder.feed(packet(target(tid = 1, rangeY = 80, speedYhalf = -4)))
        now += RadarV2Decoder.STALE_MOVING_MS + 100
        // New track 30 m away from the dead one's range: a different object.
        val v = decoder.feed(packet(target(tid = 2, rangeY = 380)))!!.vehicles.single()
        assertTrue(v.bornInformative)
    }

    @Test
    fun `birth long after a death is informative again`() {
        warmUp()
        decoder.feed(packet(target(tid = 1, rangeY = 80, speedYhalf = -4)))
        now += RadarV2Decoder.STALE_MOVING_MS + 100
        decoder.feed(packet()) // prune records the death now
        now += RadarV2Decoder.DEATH_MEMORY_MS + 100
        val v = decoder.feed(packet(target(tid = 2, rangeY = 90)))!!.vehicles.single()
        assertTrue(v.bornInformative)
    }

    @Test
    fun `rangeXmRaw is the pre-correction lateral`() {
        val offset = RadarV2Decoder(nowMs = { now }, lateralOffsetCm = 141)
        val v = offset.feed(packet(target(tid = 1, rangeY = 60, rangeX = 3)))!!
            .vehicles.single()
        assertEquals(0.3f, v.rangeXmRaw, 1e-4f)
        assertEquals(1.71f, v.rangeXm, 1e-4f)
    }
}
