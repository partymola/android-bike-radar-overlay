// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the connection-probe line a rider pastes into a bug report: the exact
 * shape of the service list, and that the outcome survives every truncation
 * path. The outcome is the field that says what went wrong, so a device with a
 * large GATT table must not be able to push it off the end.
 *
 * Expected strings are written out in full rather than rebuilt from the
 * formatter's own constants, so a change to the format fails here instead of
 * agreeing with itself.
 */
class LinkProbeTest {

    @Test fun formatsServicesAndCharacteristicsInDiscoveryOrder() {
        // Both lists are deliberately unsorted: discovery order is what a
        // maintainer compares against a known-good device, and a formatter that
        // quietly sorted either level would read as a different device.
        val out = LinkProbe.format(
            listOf(
                "2800" to listOf("2803", "2821", "2811"),
                "3200" to listOf("3204", "3203"),
                "180f" to listOf("2a19"),
            ),
            outcome = "handshake-ok",
        )
        assertEquals("svc=2800[2803,2821,2811] 3200[3204,3203] 180f[2a19] out=handshake-ok", out)
    }

    @Test fun emptyServiceListStillReportsTheOutcome() {
        assertEquals(
            "svc=none out=tx-char-missing",
            LinkProbe.format(emptyList(), outcome = "tx-char-missing"),
        )
    }

    @Test fun serviceWithNoCharacteristicsRendersEmptyBrackets() {
        assertEquals(
            "svc=1801[] out=ok",
            LinkProbe.format(listOf("1801" to emptyList()), outcome = "ok"),
        )
    }

    @Test fun aServiceListExactlyAtTheBudgetIsKeptWhole() {
        // 139 characteristics renders as 4 + 1 + (139*4 + 138) + 1 = 700 chars,
        // exactly MAX_SERVICE_CHARS. The budget is a ceiling, not a limit below.
        val chars = List(139) { "2a19" }
        val out = LinkProbe.format(listOf("180f" to chars), outcome = "ok")
        assertTrue("expected no truncation marker, got: $out", !out.contains("more"))
        assertEquals(700, out.removePrefix("svc=").removeSuffix(" out=ok").length)
    }

    @Test fun oversizedServiceListIsTruncatedAndCounted() {
        // Each token is "32NN[3203,3204]" = 15 chars, joined by one space, so
        // n tokens cost 16n - 1. 43 fit in 700 (687); 44 would need 703.
        val services = List(50) { i -> "32%02d".format(i) to listOf("3203", "3204") }
        val out = LinkProbe.format(services, outcome = "v2-cccd")
        assertTrue("expected the first service kept, got: $out", out.startsWith("svc=3200[3203,3204] "))
        assertTrue("expected 3242 to be the last kept service, got: $out", out.contains("3242[3203,3204] +7 more"))
        assertTrue("expected the outcome preserved, got: $out", out.endsWith(" out=v2-cccd"))
    }

    /**
     * Truncation stops at the first service that will not fit; it does not skip
     * that one and carry on with smaller ones. A kept list that is a prefix of
     * discovery order is readable against another device's; a subset picked by
     * size is not, and the dropped count would no longer say where the cut is.
     */
    @Test fun truncationKeepsAPrefixRatherThanPackingSmallerServicesIn() {
        val big = List(200) { "2a19" }
        val out = LinkProbe.format(
            listOf(
                "180f" to big,
                "3200" to listOf("3203", "3204"),
                "180a" to listOf("2a26"),
            ),
            outcome = "handshake-ok",
        )
        assertEquals("svc=+3 more out=handshake-ok", out)
    }

    @Test fun outcomeSurvivesWhenEvenTheFirstServiceIsTooLarge() {
        val chars = List(200) { "2a19" }
        assertEquals(
            "svc=+1 more out=ok",
            LinkProbe.format(listOf("180f" to chars), outcome = "ok"),
        )
    }

    @Test fun rendersTheStoredLineWithItsStamp() {
        assertEquals(
            "since=1786034957178 svc=180f[2a19] out=v2-cccd",
            LinkProbe.render(1786034957178L, "svc=180f[2a19] out=v2-cccd"),
        )
    }

    @Test fun parsesBackTheStampAndBodyItRendered() {
        val parsed = LinkProbe.parse("since=1786034957178 svc=180f[2a19] out=v2-cccd")
        assertEquals(1786034957178L, parsed?.sinceMs)
        assertEquals("svc=180f[2a19] out=v2-cccd", parsed?.body)
    }

    /** The body keeps its own spaces: a multi-service table splits on the FIRST
     *  space only, or a restart would seed the debounce with a truncated answer
     *  and rewrite the stamp it was meant to preserve. */
    @Test fun parseSplitsOnTheFirstSpaceOnly() {
        val parsed = LinkProbe.parse("since=17 svc=2800[2811] 3200[3204] out=handshake-ok")
        assertEquals(17L, parsed?.sinceMs)
        assertEquals("svc=2800[2811] 3200[3204] out=handshake-ok", parsed?.body)
    }

    @Test fun parseRejectsWhatItDidNotRender() {
        assertNull(LinkProbe.parse(null))
        assertNull(LinkProbe.parse(""))
        // No prefix at all - a value written by some older build.
        assertNull(LinkProbe.parse("svc=180f[2a19] out=ok"))
        // Prefix but no body.
        assertNull(LinkProbe.parse("since=1786034957178"))
        assertNull(LinkProbe.parse("since=1786034957178 "))
        // Prefix but the stamp is not a number.
        assertNull(LinkProbe.parse("since=yesterday svc=180f[2a19] out=ok"))
    }
}
