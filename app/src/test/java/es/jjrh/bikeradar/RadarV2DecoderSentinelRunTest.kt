// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lateral-unknown sentinel run must not end because the target came close.
 *
 * The radar signals "no lateral reading" by sending `rangeX` bits of zero, and
 * the decoder only treats that as the sentinel beyond
 * [RadarV2Decoder.LATERAL_UNKNOWN_MIN_RANGE_Y_M], because inside it a literal
 * zero is a plausible dead-behind measurement. That range test is right for the
 * START of a run and wrong for its continuation: a track already carrying a
 * held-over offset does not suddenly become centred when it crosses 10 m.
 *
 * Field evidence from one captured track: the radar sent zero lateral bits
 * continuously from rangeY 76 m down to 5 m. The frames beyond 10 m were read
 * as the sentinel and carried 71.2 m forward; the last two were read as
 * measurements of dead centre. The predicted-pass fit
 * then had nine samples at ~70 m and two at ~0, fitted a line converging on the
 * rider, scored it a predicted hit - which is never vetoed - and fired the
 * imminent-impact cue at 47 m/s on a stationary rider in a closed room.
 */
class RadarV2DecoderSentinelRunTest {

    /** One V2 notification carrying a single target. [rangeXm] null sends the
     *  zero lateral bits the radar uses when it has no reading. */
    private fun frame(tid: Int, rangeYm: Float, rangeXm: Float?, closingMs: Float): ByteArray {
        val ry = (rangeYm * 10).toInt() and 0x1FFF
        val rx = if (rangeXm == null) 0 else (rangeXm * 10).toInt() and 0x07FF
        val packed = (ry shl 11) or rx
        val speedY = (-closingMs / 0.5f).toInt()
        return byteArrayOf(
            0x02, 0x00,
            tid.toByte(),
            RadarV2Decoder.CLASS_NORMAL.toByte(),
            (packed and 0xFF).toByte(),
            ((packed shr 8) and 0xFF).toByte(),
            ((packed shr 16) and 0xFF).toByte(),
            0, 0,
            speedY.toByte(),
            RadarV2Decoder.LATERAL_VELOCITY_SENTINEL.toByte(),
        )
    }

    private fun vehicleAfter(frames: List<ByteArray>, tid: Int): Vehicle? {
        var now = 1_000L
        val decoder = RadarV2Decoder(nowMs = { now })
        var last: RadarState? = null
        for (f in frames) {
            last = decoder.feed(f) ?: last
            now += 100L
        }
        return last?.vehicles?.firstOrNull { it.id == tid }
    }

    @Test fun `a sentinel run survives the target coming inside the range gate`() {
        val v = vehicleAfter(
            listOf(
                frame(1, 40f, 3f, 20f),
                frame(1, 34f, 3f, 20f),
                frame(1, 28f, 3f, 20f),
                frame(1, 20f, null, 20f), // sentinel starts, beyond the gate
                frame(1, 14f, null, 20f),
                frame(1, 8f, null, 20f), // still zero bits, now inside the gate
            ),
            tid = 1,
        )!!
        assertTrue("the run must still read as unknown inside 10 m", v.lateralUnknown)
        assertEquals("the held offset must survive, not collapse to centre", 3f, v.rangeXm, 0.001f)
    }

    @Test fun `a real lateral reading ends the run and the value comes back`() {
        // The run must END. Widening the entry test to "zero bits OR already in
        // a run" reads identically on every frame above, and would hold a stale
        // offset for the track's whole life: close-pass detection skips it, the
        // overlay draws it off to the side, and the off-axis veto stands down
        // for good. Asserting the flag alone does not catch that - assert the
        // measured value returns.
        val v = vehicleAfter(
            listOf(
                frame(4, 40f, 3f, 20f),
                frame(4, 30f, null, 20f),
                frame(4, 20f, null, 20f),
                frame(4, 15f, 0.5f, 20f), // lateral is back
            ),
            tid = 4,
        )!!
        assertFalse("a real reading must end the run", v.lateralUnknown)
        assertEquals("the new measurement must replace the held offset", 0.5f, v.rangeXm, 0.001f)
    }

    @Test fun `a track genuinely near centre still reads a close zero as a measurement`() {
        // The other side of the same rule: this track was never off-axis, so
        // its previous lateral is inside the threshold and a zero at close
        // range is exactly what a dead-behind car looks like.
        val v = vehicleAfter(
            listOf(
                frame(2, 30f, 0.3f, 20f),
                frame(2, 20f, 0.3f, 20f),
                frame(2, 8f, null, 20f),
            ),
            tid = 2,
        )!!
        assertFalse("a centred track's close zero is a real reading", v.lateralUnknown)
    }

    @Test fun `a run cannot start inside the range gate`() {
        // The range test still guards where a run BEGINS: with no sentinel run
        // already in progress, a zero inside 10 m is a measurement.
        val v = vehicleAfter(
            listOf(
                frame(3, 30f, 4f, 20f),
                frame(3, 9f, null, 20f),
            ),
            tid = 3,
        )!!
        assertFalse("a first zero inside the gate is not the sentinel", v.lateralUnknown)
    }
}
