// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
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
    fun `a serial inside a device name does not survive`() {
        // Some radars advertise a name that ends in the unit's serial.
        val out = build(journal = listOf("radar link start XYZ12345"))

        assertFalse("a serial reached the bundle:\n$out", out.contains("12345"))
        assertTrue(out.contains("radar link start XYZ#####"))
    }

    @Test
    fun `a serial after a hyphen in a device name does not survive`() {
        val out = build(journal = listOf("camera link start CAM-54321"))

        assertFalse(out.contains("54321"))
        assertTrue(out.contains("camera link start CAM-#####"))
    }

    @Test
    fun `a serial after an underscore or in a lowercase name does not survive`() {
        val out = build(journal = listOf("radar link start XYZ_123456", "radar link start xyz12345"))

        assertTrue(out.contains("radar link start XYZ_######"))
        assertTrue(out.contains("radar link start xyz#####"))
    }

    @Test
    fun `four joined digits are masked and a three-digit model number is not`() {
        val out = build(journal = listOf("radar link start XYZ1234", "radar link start ABC315"))

        assertTrue(out.contains("radar link start XYZ####"))
        assertTrue(out.contains("radar link start ABC315"))
    }

    @Test
    fun `a serial after a space does not survive`() {
        val out = build(journal = listOf("device paired; treated as a sighting Cam 58213"))

        assertFalse(out.contains("58213"))
        assertTrue(out.contains("treated as a sighting Cam #####"))
    }

    @Test
    fun `a serial after a hash, two separators or an equals sign does not survive`() {
        val out = build(
            journal = listOf(
                "radar link start XYZ#73521",
                "radar link start XYZ--73522",
                "radar link start XYZ-_73523",
                "radar link start XYZ=73524",
                "radar link start Cam=73525",
                "radar link start a1b=73526",
            ),
        )

        assertTrue(out.contains("radar link start XYZ######"))
        assertTrue(out.contains("radar link start XYZ--#####"))
        assertTrue(out.contains("radar link start XYZ-_#####"))
        assertTrue(out.contains("radar link start XYZ=#####"))
        assertTrue(out.contains("radar link start Cam=#####"))
        assertTrue(out.contains("radar link start a1b=#####"))
    }

    @Test
    fun `a serial with letters mixed in does not survive`() {
        val out = build(journal = listOf("camera link start CAM-1A2B3C4D", "radar link start RTL A1B2C3D4"))

        assertTrue(out.contains("camera link start CAM-########"))
        assertTrue(out.contains("radar link start RTL A#######"))
    }

    @Test
    fun `an identifier with under four digits stays visible`() {
        // The limit of a rule that also has to leave model numbers alone.
        val lines = listOf("radar link start CAM-12-34-56", "radar link start A1B2C3", "radar link start ABCDEF")
        val out = build(journal = lines)

        lines.forEach { assertTrue(out.contains(it)) }
    }

    @Test
    fun `numbers in the shapes the journal writes survive`() {
        val lines = listOf(
            "1999-01-01 12:34:56 radar legacy stream ended frames=12345 hb=4000 threat=1234 unparsed=0",
            "1999-01-01 12:34:56 radar reconnect in 8000ms (backoff=8000ms)",
            "1999-01-01 12:34:56 radar V2 stream silent 12000ms, last received 13000ms ago; tearing down",
            "1999-01-01 12:34:56 radar link lost status=147 3990ms after its last frame",
            "1999-01-01 12:34:56 radar conn state status=133 newState=0",
            "1999-01-01 12:34:56 no-radar alert sounded (cue 1, 4000 s into the ride, grace 3600 s)",
            "1999-01-01 12:34:56 radar handshake aborted at amv-04 (quick reconnect)",
        )
        val out = build(journal = lines)

        lines.forEach { assertTrue("a number was masked in: $it\n$out", out.contains(it)) }
    }

    @Test
    fun `a model number survives in every name shape`() {
        val lines = listOf(
            "radar link start RTL515",
            "radar link start Varia RTL515",
            "radar link start RearVue8",
            "camera link start Vue 870",
        )
        val out = build(journal = lines)

        lines.forEach { assertTrue(out.contains(it)) }
    }

    @Test
    fun `the settings, build and file listings are left as they are`() {
        // Firmware revisions, link probe tags and file stamps are what a report
        // is read for, and none of them comes from a device name.
        val probe = "radar_link_probe=since=1700000000000 svc=3200[3203,3204] 2800[2811] out=amv-04"
        val out = build(
            prefsDump = "radar_firmware_rev=2026.10\n$probe",
            captureLogs = listOf(DiagnosticBundle.FileLine("bike-radar-capture-19990101-120000.log.gz", 12)),
            crashLogs = listOf(DiagnosticBundle.FileLine("bike-radar-crash-19990101-120000.log", 1)),
        )

        assertTrue(out.contains("radar_firmware_rev=2026.10"))
        assertTrue(out.contains(probe))
        assertTrue(out.contains("bike-radar-capture-19990101-120000.log.gz  12KB"))
        assertTrue(out.contains("bike-radar-crash-19990101-120000.log"))
        assertTrue(out.contains("Generated: Mon Jan 01 00:00:00 GMT 2035"))
    }

    @Test
    fun `a serial in a crash report does not survive`() {
        val out = build(crashText = "java.lang.IllegalStateException: gatt XYZ12345 closed")

        assertFalse(out.contains("12345"))
        assertTrue(out.contains("gatt XYZ##### closed"))
    }

    @Test
    fun `a serial after a space in a crash report's cause does not survive`() {
        val out = build(crashText = "Caused by: java.io.IOException: gatt Cam 58213 closed")

        assertTrue(out.contains("Caused by: java.io.IOException: gatt Cam ##### closed"))
    }

    @Test
    fun `a crash report keeps its code locations, version, time and status codes`() {
        val report = listOf(
            "time_ms: 1700000000000",
            "version: 1.10.0 (4200)",
            "exception: java.lang.IllegalStateException: gatt status 0x80000000",
            "\tat es.jjrh.bikeradar.Foo\$abc1234.run(Foo.kt:1234)",
            "\t... 12345 more",
        )
        val out = build(crashText = report.joinToString("\n"))

        report.forEach { assertTrue("masked: $it\n$out", out.contains(it)) }
    }

    @Test
    fun `a message shaped like a report's own lines is still masked`() {
        // An exception message can hold a newline. Only a line in the exact shape
        // the report writes is left alone, so these near misses are masked; a
        // message forging the exact shape would still get through.
        val out = build(
            crashText = listOf(
                "version: gatt XYZ73521",
                "time_ms: XYZ73522",
                "\tat XYZ73523 closed",
                "\t... XYZ73524 more",
                "gatt XYZ73525 version: 1.0 (4)",
            ).joinToString("\n"),
        )

        (21..25).forEach { assertFalse("XYZ735$it survived:\n$out", out.contains("735$it")) }
        assertTrue(out.contains("version: gatt XYZ#####"))
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
