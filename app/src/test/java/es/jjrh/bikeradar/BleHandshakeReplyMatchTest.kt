// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the AMV `0x18` reply matcher's byte positions so that a regression
 * narrowing the check back to byte 10 + 11 only (the pre-tightening shape)
 * fails this suite. The wire invariant is `41 4d 56` at bytes 8-10 plus
 * `0x18` at byte 11; trailing status bytes vary and must not be checked.
 */
class BleHandshakeReplyMatchTest {

    private fun frameOf(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test fun acceptsCanonicalReplyOnFirmware580() {
        // `00 01 00 00 00 00 00 00 41 4d 56 18 01` — observed on firmware 5.80
        val frame = frameOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x41, 0x4d, 0x56, 0x18, 0x01)
        assertTrue(RadarUnlock.matchSubmodeReply(frame))
    }

    @Test fun acceptsRepliesWithDifferingStatusByte() {
        // Trailing status byte varies (`82 02` from the second toggle frame
        // also appears). The matcher must only pin bytes 0-11; trailing
        // bytes are session/frame state and out of scope.
        val frame = frameOf(0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x41, 0x4d, 0x56, 0x18, 0x82, 0x02)
        assertTrue(RadarUnlock.matchSubmodeReply(frame))
    }

    @Test fun rejectsTooShort() {
        // Anything shorter than 12 bytes can't carry the AMV signature
        // and opcode. Reject without indexing.
        val frame = frameOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x41, 0x4d, 0x56)
        assertFalse(RadarUnlock.matchSubmodeReply(frame))
    }

    /**
     * The regression-catching test. A frame with byte 10 = `0x56` (`V`)
     * and byte 11 = `0x18` (the opcode), but bytes 8-9 NOT matching
     * `41 4d`, would have falsely passed the pre-tightening matcher
     * (which only checked byte 10 + 11). The full-signature matcher
     * MUST reject this frame so a stray notify with coincidental
     * bytes at offsets 10/11 cannot sneak past.
     */
    @Test fun rejectsCoincidentalByte10And11WithoutFullSignature() {
        val frame = frameOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x56, 0x18, 0x00)
        assertFalse(RadarUnlock.matchSubmodeReply(frame))
    }

    @Test fun rejectsAmvSignatureWithWrongOpcode() {
        // 41 4d 56 16 — AMV signature for cmd 16 (a different opcode).
        // The 0x18 toggle handshake must not match cmd 16 replies.
        val frame = frameOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x41, 0x4d, 0x56, 0x16, 0x00, 0x01)
        assertFalse(RadarUnlock.matchSubmodeReply(frame))
    }

    @Test fun rejectsPartialSignature() {
        // Missing `M` at byte 9. Real radar traffic on this characteristic
        // wouldn't produce this, but the matcher's defence-in-depth
        // ensures a stray notify of length 12+ with byte 8 = `0x41`,
        // byte 10 = `0x56`, byte 11 = `0x18` doesn't slip through.
        val frame = frameOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x41, 0x00, 0x56, 0x18, 0x01)
        assertFalse(RadarUnlock.matchSubmodeReply(frame))
    }
}
