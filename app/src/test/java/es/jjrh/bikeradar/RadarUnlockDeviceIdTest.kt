// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [RadarUnlock.buildDeviceIdSuffix] - the phone-side
 *  device-ID payload (client-name + vendor + model + trailer) sent during
 *  the capability exchange. Vendor/model come from android.os.Build at
 *  runtime; these pass explicit params so no Build shadow is needed. */
class RadarUnlockDeviceIdTest {

    @Test fun `golden payload matches the original hardcoded constant`() {
        assertEquals(
            "1162696b657261646172206f7665726c617906476f6f676c650f" +
                "506978656c2031302050726f20584c01148400",
            RadarUnlock.buildDeviceIdSuffix("bikeradar overlay", "Google", "Pixel 10 Pro XL"),
        )
    }

    @Test fun `empty vendor and model emit zero-length fields`() {
        val suffix = RadarUnlock.buildDeviceIdSuffix("bikeradar overlay", "", "")
        assertEquals(
            "1162696b657261646172206f7665726c6179" + "00" + "00" + "01148400",
            suffix,
        )
        assertTrue(suffix.endsWith("000001148400"))
        assertTrue(suffix.contains("1162696b657261646172206f7665726c6179"))
    }

    @Test fun `over-cap model field is truncated to 32 bytes`() {
        val suffix = RadarUnlock.buildDeviceIdSuffix("bikeradar overlay", "x", "a".repeat(40))
        // vendor "x" = 01 + 78; model field length prefix is 0x20 (32) then 32 * "61".
        val modelField = "20" + "61".repeat(32)
        assertTrue(suffix.contains(modelField))
        // No 33rd model byte: the 32-byte field followed by the trailer, not another "61".
        assertTrue(suffix.contains(modelField + DEVICE_ID_TRAILER_HEX))
    }

    @Test fun `client name runs through the same sanitize and cap path`() {
        // clientName is not fixed at the type level - it shares lenPrefixedAscii
        // with vendor/model, so pin that it is stripped to ASCII and capped too.
        val suffix = RadarUnlock.buildDeviceIdSuffix("Ü" + "z".repeat(40), "x", "y")
        // "Ü" (U+00DC) stripped, 40 'z' capped to 32 -> prefix 0x20 then 32 * "7a".
        assertTrue(suffix.startsWith("20" + "7a".repeat(32)))
    }

    @Test fun `non-ascii bytes are stripped before length-prefixing`() {
        // model "Pixel™ Pro" -> ™ stripped -> "Pixel Pro" (9 chars).
        // vendor "Göogle" -> ö stripped -> "Gogle" (5 chars).
        val suffix = RadarUnlock.buildDeviceIdSuffix("bikeradar overlay", "Göogle", "Pixel™ Pro")
        val vendorField = "05" + "Gogle".toByteArray(Charsets.US_ASCII).toHex()
        val modelField = "09" + "Pixel Pro".toByteArray(Charsets.US_ASCII).toHex()
        assertTrue(suffix.contains(vendorField))
        assertTrue(suffix.contains(modelField))
    }

    private companion object {
        const val DEVICE_ID_TRAILER_HEX = "01148400"
    }
}
