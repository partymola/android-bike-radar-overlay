// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.HaFailure
import es.jjrh.bikeradar.HaFamily
import es.jjrh.bikeradar.HaHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle is the one artefact the app is designed to leave the phone and
 * become permanently visible to strangers, and it has no consent dialog in
 * front of it.
 *
 * These assert on the OUTPUT. The previous test read this code's own source
 * looking for a redaction call, which passes whenever the call is present and
 * says nothing about whether an address survives it - a check on the measure
 * rather than the thing.
 */
class DiagnosticBundleTest {

    private fun build(
        journal: List<String> = emptyList(),
        crashText: String? = null,
        haFamilies: Map<HaFamily, HaHealth> = emptyMap(),
        prefsDump: String = "prefs=x",
        captureLogs: List<DiagnosticBundle.FileLine> = emptyList(),
        crashLogs: List<DiagnosticBundle.FileLine> = emptyList(),
    ) = DiagnosticBundle.build(
        generated = "Mon Jan 01 00:00:00 GMT 2035",
        buildStamp = "app version=1.2.3 code=42 build=debug",
        prefsDump = prefsDump,
        haFamilies = haFamilies,
        captureLogs = captureLogs,
        journal = journal,
        crashLogs = crashLogs,
        newestCrashText = crashText,
    )

    @Test
    fun `an address in a journal line does not survive`() {
        // Journal lines interpolate BLE device names and addresses, and are
        // appended after the prefs dump - so they used to bypass the only
        // redaction in the bundle.
        val out = build(journal = listOf("radar link start MyRadar AA:BB:CC:DD:EE:FF"))

        assertFalse("a raw address reached the bundle:\n$out", out.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(out.contains("<redacted>"))
        assertTrue("the rest of the line must survive", out.contains("radar link start MyRadar"))
    }

    @Test
    fun `an address in a crash report does not survive`() {
        // An exception message carries whatever string threw, which is not a
        // fixed vocabulary and cannot be reasoned about in advance.
        val out = build(crashText = "java.lang.IllegalStateException: gatt 11:22:33:44:55:66 closed")

        assertFalse("a raw address reached the bundle:\n$out", out.contains("11:22:33:44:55:66"))
        assertTrue(out.contains("IllegalStateException"))
    }

    @Test
    fun `the journal is capped and the header says how many it took`() {
        val out = build(journal = (1..100).map { "line $it AA:BB:CC:DD:EE:FF" })

        // Literals, not the constants: asserting a constant against itself
        // pins that A cap exists, never that it is 40.
        assertTrue(out.contains("--- Link journal (newest 40) ---"))
        assertEquals(
            "only the cap may be carried",
            40,
            out.lines().count { it.startsWith("line ") },
        )
        assertFalse(out.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `the bundle names the build it came from`() {
        // A hand-built APK sent to one reporter carries no tag and shares its
        // versionCode with every build between releases.
        assertTrue(build().contains("app version=1.2.3 code=42 build=debug"))
    }

    @Test
    fun `a quiet Home Assistant stream is told apart from a failing one`() {
        // The distinction the aggregate on the row erases, and the reason
        // per-family reaches the bundle at all.
        val out = build(
            haFamilies = mapOf(
                HaFamily.BATTERY to HaHealth.Ok,
                HaFamily.CLOSE_PASS to HaHealth.Error("failed", cause = HaFailure.AUTH),
            ),
        )

        assertTrue(out.contains("battery: ok"))
        assertTrue("the cause must reach the reader", out.contains("close_pass: FAILED (AUTH)"))
        assertTrue(
            "a stream with no entry is quiet, not broken",
            out.contains("ride_edge: not published this session"),
        )
    }

    @Test
    fun `no publishes at all says so rather than listing four unknowns`() {
        assertTrue(build().contains("nothing published this session"))
    }

    @Test
    fun `file listings are capped but the counts are the totals`() {
        // A reader needs to know there are forty logs even though three are
        // named; a truncated list with no total reads as "there are three".
        val logs = (1..40).map { DiagnosticBundle.FileLine("cap-$it.log", it.toLong()) }
        val out = build(captureLogs = logs, crashLogs = logs)

        assertTrue(out.contains("--- Capture logs (40 on disk) ---"))
        assertTrue(out.contains("--- Crash reports (40 on disk) ---"))
        assertEquals(
            3,
            out.lines().count { it.startsWith("cap-") && it.contains("KB") },
        )
    }

    @Test
    fun `no crash report means no crash section at all`() {
        // Rather than an empty heading a reader would take for a missing file.
        assertFalse(build().contains("--- Newest crash report ---"))
        assertTrue(build(crashText = "boom").contains("--- Newest crash report ---"))
    }
}
