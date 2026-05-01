// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [Prefs.redactPresence] behaviour. The diagnostic bundle is meant
 * for public issue trackers, so any future edit that lets a real value
 * through (e.g. early-return on a non-null but blank input) must fail
 * a test rather than ship a leak.
 */
class PrefsRedactionTest {

    @Test fun redactedMarkerMatchesContract() {
        // The marker must be the standard `<redacted>` token (review #1 NIT 7).
        // Future edits that rename it must update copy expectations + tests.
        assertEquals("<redacted>", Prefs.redactPresence("DE:AD:BE:EF"))
        assertEquals("<unset>", Prefs.redactPresence(null))
    }

    @Test fun nullIsUnset() {
        assertEquals("<unset>", Prefs.redactPresence(null))
    }

    @Test fun emptyIsUnset() {
        assertEquals("<unset>", Prefs.redactPresence(""))
    }

    @Test fun whitespaceOnlyIsUnset() {
        assertEquals("<unset>", Prefs.redactPresence("   "))
        assertEquals("<unset>", Prefs.redactPresence("\t"))
        assertEquals("<unset>", Prefs.redactPresence("\n"))
    }

    @Test fun realMacIsSet() {
        assertEquals("<redacted>", Prefs.redactPresence("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun displayNameIsSet() {
        assertEquals("<redacted>", Prefs.redactPresence("Bike Dashcam 12"))
    }

    @Test fun valueWithSurroundingWhitespaceStillSet() {
        // Stripping whitespace before the redaction check would let a
        // real MAC slip through if the field has padding. Verify the
        // helper checks isNullOrBlank, not isEmpty after trim.
        assertEquals("<redacted>", Prefs.redactPresence("  AA:BB:CC:DD:EE:FF  "))
    }

    @Test fun shortValueIsStillSet() {
        // Single-char values are presence-positive; the redaction must
        // not mistake "short" for "empty".
        assertEquals("<redacted>", Prefs.redactPresence("a"))
    }

    @Test fun outputIsExactlyTheRedactedMarker() {
        // The strongest property: output is the literal marker, never the
        // input. Asserting the marker by exact match catches both leaks
        // and accidental marker drift in one assertion.
        val secret = "AA:BB:CC:DD:EE:FF"
        assertEquals("<redacted>", Prefs.redactPresence(secret))
    }

    @Test fun outputContainsNoInputCharacters() {
        // Belt-and-braces against future edits that build the marker
        // from the input (e.g. a partial-mask attempt). Input chosen
        // to share no characters with the marker `<redacted>`.
        val secret = "1234567890"
        val out = Prefs.redactPresence(secret)
        for (ch in secret) {
            assertEquals(
                "redacted output must not contain input character '$ch' (found in $out)",
                false,
                out.contains(ch),
            )
        }
    }
}
