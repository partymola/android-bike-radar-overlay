// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The release branch is the one that matters and the one a test run cannot
 * reach on its own: unit tests run against the debug variant, so
 * `BuildConfig.DEBUG` is always true here. [LogRedaction.mac] therefore takes
 * the flag as a parameter, and these pass it explicitly - otherwise the
 * release path would be asserted by nothing and the guard could be deleted
 * with the suite green.
 */
class LogRedactionTest {

    private val address = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `a release build does not put the address in the line`() {
        val out = LogRedaction.mac(address, debug = false)
        assertFalse("the address must not survive into a release log, got $out", out.contains("AA:BB"))
        assertFalse("no fragment of it either, got $out", out.contains("EE:FF"))
        assertEquals("(mac)", out)
    }

    @Test
    fun `a debug build keeps the address so two devices can be told apart`() {
        assertEquals(address, LogRedaction.mac(address, debug = true))
    }

    @Test
    fun `a missing address reads as absent, not withheld`() {
        // Distinct from the release placeholder on purpose: "no device is
        // selected" and "there is one but the build will not name it" are
        // different things to read off a log line.
        assertEquals("-", LogRedaction.mac(null, debug = true))
        assertEquals("-", LogRedaction.mac(null, debug = false))
    }
}
