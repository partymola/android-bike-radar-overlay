// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import es.jjrh.bikeradar.testutil.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the "release builds keep BLE/movement payloads out of logcat" claim,
 * which ships in README.md and AGENTS.md.
 *
 * No runtime test can reach it: `BuildConfig.DEBUG` is true under the unit-test
 * variant, so the release branch never executes and deleting a guard leaves the
 * whole suite green while a release build prints the radar's device-ID frame on
 * every connection. That is the sink the setup transcript's disclosure surfaces
 * exist to make opt-in, so the promise needs a check that can actually fail.
 *
 * Pattern mirrors [EBikeStatusReaderReadOnlyTest] and [HaClientDataDisclosureTest]:
 * read the `.kt` as text and assert on the call sites rather than on behaviour.
 *
 * Scope is payload sinks only. Device names and connection state deliberately
 * still reach release logcat: they are already in the always-on link journal,
 * which the Privacy screen discloses, so guarding them would be a change with
 * no disclosure to reconcile it against.
 */
class SettingsPrivacyLogcatGuardTest {

    /**
     * The payload sinks, named so a DELETED guard fails rather than silently
     * shrinking the set the sweep below can see. The sweep alone cannot do it:
     * removing a guard and the payload together would leave it green.
     */
    private val pinnedSites = listOf(
        "RadarUnlock.kt" to "rx \${charUuid",
        "RadarLinkController.kt" to "first V2 frame",
        "CameraLightLinkController.kt" to "msg -> if (BuildConfig.DEBUG) Log.d(TAG, msg)",
        "CameraLightLinkController.kt" to "mode-state notify: \$mode",
    )

    /**
     * `Log.` preceded by no letter, so `captureLog.clog(...)` does not match.
     * That call writes the opt-in capture file, not logcat, and reading it as
     * a logcat sink is the difference between this check meaning something and
     * it flagging the feature it is meant to protect.
     */
    private val logcatCall = Regex("""(?<![A-Za-z])Log\.[a-z]+\(""")

    /** The package directory, via a file [RepoFiles] can already resolve. */
    private fun mainSourceDir(): File {
        val anchor = RepoFiles.mainSource("RadarUnlock.kt")
        return requireNotNull(anchor.parentFile) { "RadarUnlock.kt must sit in a directory" }
    }

    @Test
    fun everyPinnedPayloadLogSiteIsGuardedByBuildConfigDebug() {
        pinnedSites.forEach { (fileName, needle) ->
            val lines = RepoFiles.mainSource(fileName).readLines()
            val idx = lines.indexOfFirst { it.contains(needle) }
            assertTrue(
                "expected a logcat site containing '$needle' in $fileName; if it moved, " +
                    "move this pin with it rather than deleting it",
                idx >= 0,
            )
            // The guard sits on the line or opens an `if` just above it, so a
            // small window covers both shapes without matching a distant one.
            val window = lines.subList(maxOf(0, idx - 3), idx + 1).joinToString("\n")
            assertTrue(
                "$fileName logs a raw payload with no BuildConfig.DEBUG guard, which breaks " +
                    "the logcat claim in README.md and AGENTS.md. Line: ${lines[idx].trim()}",
                window.contains("BuildConfig.DEBUG"),
            )
        }
    }

    /**
     * Nothing outside the pinned set may render bytes to logcat. Catches a NEW
     * payload sink, which the per-site pins structurally cannot - they only
     * prove the sites they name.
     */
    @Test
    fun noSourceFileLogsRawBytesToLogcatWithoutAGuard() {
        val mainDir: File = mainSourceDir()
        val logCall = logcatCall
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val lines = file.readLines()
                lines.withIndex()
                    .filter { (_, l) -> logCall.containsMatchIn(l) && l.contains("toHex()") }
                    .filter { (i, _) ->
                        !lines.subList(maxOf(0, i - 3), i + 1)
                            .joinToString("\n")
                            .contains("BuildConfig.DEBUG")
                    }
                    .map { (_, l) -> "${file.name}: ${l.trim()}" }
            }
            .toList()
        assertEquals(
            "every logcat line rendering raw bytes must be behind BuildConfig.DEBUG. Found: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Anti-vacuity: the sweep above passes trivially if it stops finding files
     * or stops recognising a log call. Both have to be shown live.
     */
    @Test
    fun theSweepActuallyReadsTheSourcesAndCanSeeALogCall() {
        val mainDir: File = mainSourceDir()
        val kt = mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the sweep must reach the main sources, found ${kt.size}", kt.size > 20)
        val logCall = logcatCall
        val withHex = kt.flatMap { f -> f.readLines() }
            .count { logCall.containsMatchIn(it) && it.contains("toHex()") }
        assertTrue("the sweep must still recognise the guarded payload sites, saw $withHex", withHex >= 2)
    }
}
