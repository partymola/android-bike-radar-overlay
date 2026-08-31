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
 * What remains here is the pair of checks that must read SOURCE, because no
 * output can show them: that the redactor itself still removes an address,
 * and that no Composable renders the build stamp. The redaction of the
 * bundle's own content is now asserted on the built string by
 * [es.jjrh.bikeradar.ui.DiagnosticBundleTest], which is a stronger check -
 * a source read passes whenever the call is present and says nothing about
 * whether an address survives it.
 */
class DiagnosticBundleRedactionTest {

    @Test
    fun theRedactorActuallyRemovesAnAddress() {
        // Pinned here because no output test can show it: DiagnosticBundleTest
        // asserts an address does not survive the bundle, which a redactor
        // that deleted everything would also satisfy. This says what it does.
        val line = "radar link start MyRadar AA:BB:CC:DD:EE:FF"
        assertEquals("radar link start MyRadar <redacted>", Prefs.redactAddresses(line))
    }

    @Test
    fun theScreenHandsTheBundleTheRealBuildStampAndHealth() {
        // Source-read because these are WIRING, and the bundle's own tests
        // cannot see them: DiagnosticBundleTest hands `build()` a stamp and
        // asserts it comes back, which is equally true if the screen passes an
        // empty string. DebugScreen is also in diffCoverageExcludes, so
        // nothing else looks at these two lines at all.
        val src = RepoFiles.mainSource("ui/DebugScreen.kt").readText()
        assertTrue(
            "the bundle must be handed the real build stamp",
            src.contains("buildStamp = BuildConfigStamp.line().removePrefix(\"# \")"),
        )
        assertTrue(
            "the bundle must be handed the real per-family health",
            src.contains("haFamilies = HaHealthBus.families.value"),
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
