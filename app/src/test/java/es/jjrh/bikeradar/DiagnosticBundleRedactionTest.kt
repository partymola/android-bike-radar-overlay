// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything the diagnostic bundle appends must be redacted, not just the
 * prefs dump.
 *
 * The bundle is what a hardware reporter is asked to paste into a public issue
 * thread, so it is the one artefact in the app that is expected to leave the
 * phone and become permanently visible to strangers. Unlike a capture-log
 * share it has no consent dialog in front of it.
 *
 * `Prefs.dumpAll()` redacts. The envelope around it did not: the link-journal
 * lines interpolate BLE device names, and the crash report is appended
 * verbatim, both AFTER the redacted section. That made the bundle's weakest
 * part the one nobody was looking at.
 *
 * Source-reading because `shareDiagnosticBundle` is a private function inside
 * a Composable file with no test entry point, and its only side effect is
 * writing the clipboard. The alternative measured here was no check at all.
 */
class DiagnosticBundleRedactionTest {

    private val source = RepoFiles.mainSource("ui/DebugScreen.kt").readText()

    @Test
    fun theRedactorActuallyRemovesAnAddress() {
        // Pin the behaviour the two call sites below depend on, so this file
        // cannot pass while the redactor itself has become a no-op.
        val line = "radar link start MyRadar AA:BB:CC:DD:EE:FF"
        assertEquals("radar link start MyRadar <redacted>", Prefs.redactAddresses(line))
    }

    @Test
    fun journalLinesAreRedactedIntoTheBundle() {
        assertTrue(
            "link-journal lines carry BLE device names and are pasted into public " +
                "issues; they must go through the same redaction as the prefs dump",
            source.contains("journal.take(40).forEach { sb.appendLine(Prefs.redactAddresses(it)) }"),
        )
    }

    @Test
    fun theCrashReportIsRedactedIntoTheBundle() {
        // An exception message carries whatever string threw, which is not a
        // fixed vocabulary and cannot be reasoned about in advance.
        val bundleFn = source.substringAfter("private fun shareDiagnosticBundle")
        val crashSection = bundleFn.substringAfter("--- Newest crash report ---")
        assertTrue(
            "the appended crash report must be redacted: $crashSection",
            crashSection.contains("Prefs.redactAddresses("),
        )
    }

    @Test
    fun theBundleNamesTheBuildItCameFrom() {
        // A hand-built APK sent to one reporter carries no tag and shares its
        // versionCode with every build between releases, so without this the
        // pasted bundle cannot be tied to a tree. It is deliberately here and
        // not on a rendered screen: a screen would put the commit into every
        // Roborazzi golden and force a re-record on each commit.
        assertTrue(
            "the bundle must state which build produced it",
            source.contains("BuildConfigStamp.line()"),
        )
    }

    @Test
    fun noRenderedScreenShowsTheCommit() {
        // The constraint that keeps the goldens stable. If a Composable ever
        // renders the stamp, every golden carries the SHA and every commit
        // invalidates all of them.
        val uiDir = RepoFiles.mainSource("ui/DebugScreen.kt").parentFile
        val offenders = uiDir?.listFiles { f: java.io.File -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { f ->
                val text = f.readText()
                text.contains("BuildConfigStamp.line()") && text.contains("Text(")
            }
            .map { it.name }
            .filter { it != "DebugScreen.kt" }
        assertEquals("no Composable may render the build stamp: $offenders", emptyList<String>(), offenders)
    }
}
