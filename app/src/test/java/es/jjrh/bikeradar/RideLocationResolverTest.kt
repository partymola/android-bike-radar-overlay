// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.RideLocationResolver.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the location resolver: the precedence chain (manual -> gps -> London)
 * and the full input-safety matrix (sanitize / parse / validate). Pure, no
 * Android, no clock. Non-ASCII and control inputs are written with \\uXXXX
 * escapes so this source stays clean UTF-8 text - no raw control bytes (which
 * would make git treat the file as binary and hide its diff) and no literal
 * bidi trojan-source characters.
 */
class RideLocationResolverTest {

    // --- sanitizeCoordinateInput: the onValueChange / paste filter ---

    @Test fun sanitizeKeepsPlainDecimal() {
        assertEquals("51.5074", RideLocationResolver.sanitizeCoordinateInput("51.5074"))
    }

    @Test fun sanitizeKeepsLeadingMinus() {
        assertEquals("-0.1278", RideLocationResolver.sanitizeCoordinateInput("-0.1278"))
    }

    @Test fun sanitizeDropsLetters() {
        assertEquals("51.5074", RideLocationResolver.sanitizeCoordinateInput("51.5074N"))
    }

    @Test fun sanitizeStripsPastedJunk() {
        // Paste of a maps-style string: only the number survives.
        assertEquals("51.5074", RideLocationResolver.sanitizeCoordinateInput("lat 51.5074 deg"))
    }

    @Test fun sanitizeNormalisesLocaleComma() {
        // es keyboards emit a comma as the decimal separator.
        assertEquals("51.5074", RideLocationResolver.sanitizeCoordinateInput("51,5074"))
    }

    @Test fun sanitizeNormalisesUnicodeMinus() {
        // \u2212 = Unicode MINUS SIGN (what many maps/apps emit).
        assertEquals("-0.1278", RideLocationResolver.sanitizeCoordinateInput("\u22120.1278"))
    }

    @Test fun sanitizeNormalisesUnicodeMinusAndComma() {
        assertEquals("-0.1278", RideLocationResolver.sanitizeCoordinateInput("\u22120,1278"))
    }

    @Test fun sanitizeAllowsOnlyOneDot() {
        assertEquals("1.23", RideLocationResolver.sanitizeCoordinateInput("1.2.3"))
    }

    @Test fun sanitizeAllowsMinusOnlyAtStart() {
        assertEquals("12", RideLocationResolver.sanitizeCoordinateInput("1-2"))
    }

    @Test fun sanitizeDropsInteriorMinus() {
        assertEquals("-12", RideLocationResolver.sanitizeCoordinateInput("-1-2"))
    }

    @Test fun sanitizeStripsWhitespace() {
        assertEquals("51.5", RideLocationResolver.sanitizeCoordinateInput(" 51 . 5 "))
    }

    @Test fun sanitizeCapsLength() {
        val out = RideLocationResolver.sanitizeCoordinateInput("1234567890123456789")
        assertEquals(RideLocationResolver.MAX_COORD_INPUT_LEN, out.length)
    }

    @Test fun sanitizeEmptyStaysEmpty() {
        assertEquals("", RideLocationResolver.sanitizeCoordinateInput(""))
        assertEquals("", RideLocationResolver.sanitizeCoordinateInput("abc\u00B0N"))
    }

    // --- parseCoordinate ---

    @Test fun parsePlainNumber() {
        assertEquals(51.5074, RideLocationResolver.parseCoordinate("51.5074")!!, 1e-9)
    }

    @Test fun parseNegative() {
        assertEquals(-0.1278, RideLocationResolver.parseCoordinate("-0.1278")!!, 1e-9)
    }

    @Test fun parseLocaleComma() {
        assertEquals(51.5, RideLocationResolver.parseCoordinate("51,5")!!, 1e-9)
    }

    @Test fun parseBlankIsNull() {
        assertNull(RideLocationResolver.parseCoordinate(""))
        assertNull(RideLocationResolver.parseCoordinate("   "))
    }

    @Test fun parseIncompleteTokensAreNull() {
        assertNull(RideLocationResolver.parseCoordinate("-"))
        assertNull(RideLocationResolver.parseCoordinate("."))
        assertNull(RideLocationResolver.parseCoordinate("-."))
    }

    @Test fun parseGarbageIsNull() {
        assertNull(RideLocationResolver.parseCoordinate("NaN"))
        assertNull(RideLocationResolver.parseCoordinate("Infinity"))
        assertNull(RideLocationResolver.parseCoordinate("1.2.3"))
        assertNull(RideLocationResolver.parseCoordinate("1e"))
    }

    // --- validManualLocation: the commit gate ---

    @Test fun validAcceptsInRange() {
        assertEquals(51.5 to -0.12, RideLocationResolver.validManualLocation(51.5, -0.12))
    }

    @Test fun validAcceptsBoundaries() {
        assertEquals(90.0 to 180.0, RideLocationResolver.validManualLocation(90.0, 180.0))
        assertEquals(-90.0 to -180.0, RideLocationResolver.validManualLocation(-90.0, -180.0))
    }

    @Test fun validRejectsOutOfRange() {
        assertNull(RideLocationResolver.validManualLocation(90.1, 0.0))
        assertNull(RideLocationResolver.validManualLocation(-90.1, 0.0))
        assertNull(RideLocationResolver.validManualLocation(0.0, 180.1))
        assertNull(RideLocationResolver.validManualLocation(0.0, -180.1))
    }

    @Test fun validRejectsBothOrNeither() {
        assertNull(RideLocationResolver.validManualLocation(51.5, null))
        assertNull(RideLocationResolver.validManualLocation(null, -0.12))
        assertNull(RideLocationResolver.validManualLocation(null, null))
    }

    @Test fun validRejectsNonFinite() {
        assertNull(RideLocationResolver.validManualLocation(Double.NaN, 0.0))
        assertNull(RideLocationResolver.validManualLocation(0.0, Double.POSITIVE_INFINITY))
    }

    @Test fun isValidLatLonHelpers() {
        assertTrue(RideLocationResolver.isValidLat(90.0))
        assertFalse(RideLocationResolver.isValidLat(90.1))
        assertFalse(RideLocationResolver.isValidLat(null))
        assertTrue(RideLocationResolver.isValidLon(-180.0))
        assertFalse(RideLocationResolver.isValidLon(181.0))
    }

    // --- sign toggle + display formatting ---

    @Test fun toggleSignAddsAndRemovesMinus() {
        assertEquals("-51.5", RideLocationResolver.toggleSign("51.5"))
        assertEquals("51.5", RideLocationResolver.toggleSign("-51.5"))
        assertEquals("-", RideLocationResolver.toggleSign(""))
        assertEquals("", RideLocationResolver.toggleSign("-"))
    }

    @Test fun formatCoordinateIsFixedPoint() {
        assertEquals("-0.1278", RideLocationResolver.formatCoordinate(-0.1278))
        assertEquals("51.5074", RideLocationResolver.formatCoordinate(51.5074))
        // Near-zero must NOT become scientific notation (would be mangled on re-edit).
        assertEquals("0.0005", RideLocationResolver.formatCoordinate(0.0005))
        assertEquals("0", RideLocationResolver.formatCoordinate(0.0))
        assertEquals("90", RideLocationResolver.formatCoordinate(90.0))
    }

    // --- resolve: precedence + defensive re-validation ---

    @Test fun resolveManualWinsOverGps() {
        val r = RideLocationResolver.resolve(48.85, 2.35, gpsFix = 40.0 to -3.0)
        assertEquals(Source.MANUAL, r.source)
        assertEquals(48.85, r.lat, 1e-9)
        assertEquals(2.35, r.lon, 1e-9)
    }

    @Test fun resolveGpsWhenNoManual() {
        val r = RideLocationResolver.resolve(null, null, gpsFix = 40.0 to -3.0)
        assertEquals(Source.GPS, r.source)
        assertEquals(40.0, r.lat, 1e-9)
    }

    @Test fun resolveLondonWhenNothing() {
        val r = RideLocationResolver.resolve(null, null, gpsFix = null)
        assertEquals(Source.LONDON, r.source)
        assertEquals(SunsetCalculator.LONDON_LAT_DEG, r.lat, 1e-9)
        assertEquals(SunsetCalculator.LONDON_LON_DEG, r.lon, 1e-9)
    }

    @Test fun resolveIgnoresHalfManual() {
        // One coordinate only -> not a point -> fall through to gps.
        val r = RideLocationResolver.resolve(48.85, null, gpsFix = 40.0 to -3.0)
        assertEquals(Source.GPS, r.source)
    }

    @Test fun resolveReValidatesStoredGarbage() {
        // A corrupt pref (out of range) must not reach the solar math.
        val r = RideLocationResolver.resolve(999.0, 999.0, gpsFix = null)
        assertEquals(Source.LONDON, r.source)
    }

    @Test fun resolveReValidatesGarbageGps() {
        val r = RideLocationResolver.resolve(null, null, gpsFix = Double.NaN to 0.0)
        assertEquals(Source.LONDON, r.source)
    }

    // --- trash-injection battery: prove nothing hostile survives ---

    private val hostileInputs = listOf(
        "\n", "\r\n", "\t", " ", "\u0000", "\u001B", "1\n2", "5\r5",
        "'; DROP TABLE coords;--",
        "<script>alert(1)</script>",
        "%s%n%x", "\${7*7}",
        "\u202E123", // RTL override (bidi trojan-source char)
        "51\uD83D\uDE00.5", // emoji surrogate pair
        "\uFF15\uFF11", "\u0665\u0661", // full-width + Arabic-Indic digits (not ASCII 0-9)
        "1e309", "0x1F", "NaN", "Infinity",
        "   ", "----", "....", "-.-.",
        "51.5074".repeat(50), // very long
    )

    /** The only shape the field is ever allowed to hold. */
    private val coordCharset = Regex("^-?[0-9]*\\.?[0-9]*$")

    @Test fun sanitizeNeverEmitsHostileChars() {
        for (input in hostileInputs) {
            val out = RideLocationResolver.sanitizeCoordinateInput(input)
            assertTrue("charset violated for <$input> -> <$out>", coordCharset.matches(out))
            assertTrue("length cap violated for <$input>", out.length <= RideLocationResolver.MAX_COORD_INPUT_LEN)
            assertFalse("control char survived <$input> -> <$out>", out.any { it < ' ' })
        }
    }

    @Test fun parseNeverReturnsNonFinite() {
        for (input in hostileInputs) {
            val v = RideLocationResolver.parseCoordinate(input)
            if (v != null) assertTrue("non-finite from <$input>", v.isFinite())
        }
    }

    @Test fun resolveAlwaysReturnsInRange() {
        val trash = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            1e309, -1e309, 91.0, -91.0, 200.0, -200.0, 0.0, 89.9, -179.9,
        )
        for (lat in trash) {
            for (lon in trash) {
                val r = RideLocationResolver.resolve(lat, lon, gpsFix = null)
                assertTrue("lat out of range: ${r.lat}", r.lat in RideLocationResolver.LAT_MIN..RideLocationResolver.LAT_MAX)
                assertTrue("lon out of range: ${r.lon}", r.lon in RideLocationResolver.LON_MIN..RideLocationResolver.LON_MAX)
            }
        }
    }

    @Test fun sanitizeThenParseRoundTripStaysFiniteOrNull() {
        // The real UI path: sanitize on every keystroke/paste, then parse at commit.
        for (input in hostileInputs) {
            val cleaned = RideLocationResolver.sanitizeCoordinateInput(input)
            val v = RideLocationResolver.parseCoordinate(cleaned)
            if (v != null) assertTrue("non-finite after round-trip of <$input>", v.isFinite())
        }
    }
}
