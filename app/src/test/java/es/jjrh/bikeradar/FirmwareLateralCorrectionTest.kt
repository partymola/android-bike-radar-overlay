// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirmwareLateralCorrectionTest {

    // ── correction table ────────────────────────────────────────────────

    @Test fun `firmware 6_70 gets the measured correction`() {
        assertEquals(
            FirmwareLateralCorrection.CORRECTION_670_CM,
            FirmwareLateralCorrection.correctionCm("6.70"),
        )
    }

    @Test fun `sub-revisions inherit the base entry by prefix`() {
        assertEquals(
            FirmwareLateralCorrection.CORRECTION_670_CM,
            FirmwareLateralCorrection.correctionCm("6.70.0.12"),
        )
    }

    @Test fun `whitespace around the revision string is tolerated`() {
        assertEquals(
            FirmwareLateralCorrection.CORRECTION_670_CM,
            FirmwareLateralCorrection.correctionCm(" 6.70 "),
        )
    }

    @Test fun `unknown firmware corrects nothing`() {
        assertEquals(0, FirmwareLateralCorrection.correctionCm("5.80"))
        assertEquals(0, FirmwareLateralCorrection.correctionCm("7.00"))
        // Prefix must anchor at the start: a build string merely
        // CONTAINING the version must not match.
        assertEquals(0, FirmwareLateralCorrection.correctionCm("v6.70"))
        // Nor may a longer version that merely BEGINS with the digits.
        assertEquals(0, FirmwareLateralCorrection.correctionCm("6.700"))
    }

    @Test fun `null firmware corrects nothing`() {
        assertEquals(0, FirmwareLateralCorrection.correctionCm(null))
    }

    // ── composition with the mount offset ───────────────────────────────

    @Test fun `correction composes with the mount offset`() {
        assertEquals(
            -10 + FirmwareLateralCorrection.CORRECTION_670_CM,
            FirmwareLateralCorrection.effectiveLateralOffsetCm(-10, "6.70", correctionEnabled = true),
        )
    }

    @Test fun `disabling the correction keeps the mount offset`() {
        assertEquals(
            -10,
            FirmwareLateralCorrection.effectiveLateralOffsetCm(-10, "6.70", correctionEnabled = false),
        )
    }

    @Test fun `enabled correction on unaffected firmware is a no-op`() {
        assertEquals(
            15,
            FirmwareLateralCorrection.effectiveLateralOffsetCm(15, "5.80", correctionEnabled = true),
        )
    }

    // ── DIS string parsing (RadarUnlock.parseDisUtf8) ───────────────────

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
