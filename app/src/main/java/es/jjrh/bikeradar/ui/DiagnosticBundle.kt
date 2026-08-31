// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.HaFamily
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.data.Prefs

/**
 * Assembles the text a reporter pastes into a public issue thread.
 *
 * Pure, and that is the point rather than tidiness. This bundle is the one
 * artefact the app is DESIGNED to leave the phone and become permanently
 * visible to strangers, and it has no consent dialog in front of it. While it
 * was built inline in a Composable the only way to assert anything about it
 * was to read its own source and look for the redaction call - a test that
 * passes if the call is present and says nothing about whether an address
 * actually survives. Now the output is a string a test can search.
 *
 * Every caller-supplied field is treated as untrusted: the link journal
 * interpolates BLE device names, and a crash report carries whatever string
 * threw. Both are redacted HERE rather than by the caller, so a new caller
 * cannot forget.
 */
object DiagnosticBundle {

    /** One line per capture or crash file: what a reader needs, no path. */
    data class FileLine(val name: String, val sizeKb: Long)

    /**
     * @param generated wall-clock stamp, passed in so the output is
     *   deterministic under test.
     * @param buildStamp which build produced this, already formatted.
     * @param prefsDump the settings dump; [Prefs.dumpAll] redacts its own.
     * @param haFamilies per-stream Home Assistant outcomes. Empty means
     *   nothing published this session, which is not the same as failing.
     * @param captureLogs newest first; only the first few are listed.
     * @param journal newest-first link-journal lines, redacted here.
     * @param crashLogs newest first.
     * @param newestCrashText contents of the newest crash report, redacted
     *   here, or null when there is none.
     */
    fun build(
        generated: String,
        buildStamp: String,
        prefsDump: String,
        haFamilies: Map<HaFamily, HaHealth>,
        captureLogs: List<FileLine>,
        journal: List<String>,
        crashLogs: List<FileLine>,
        newestCrashText: String?,
    ): String = buildString {
        appendLine("=== Bike Radar Diagnostic Bundle ===")
        appendLine("Generated: $generated")
        appendLine(buildStamp)
        appendLine()
        appendLine("--- Prefs ---")
        appendLine(prefsDump)
        appendLine("--- Home Assistant publishes ---")
        if (haFamilies.isEmpty()) {
            appendLine("nothing published this session")
        } else {
            // Every family listed, including the ones with no entry: "not
            // published this session" and "failed" are different answers, and
            // omitting the quiet ones would leave a reader to guess which.
            HaFamily.entries.forEach { fam ->
                val line = when (val h = haFamilies[fam]) {
                    null -> "not published this session"
                    is HaHealth.Ok -> "ok"
                    is HaHealth.Error -> "FAILED (${h.cause})"
                    else -> "unknown"
                }
                appendLine("${fam.name.lowercase()}: $line")
            }
        }
        appendLine()
        appendLine("--- LESC bond verification (run on PC) ---")
        appendLine("adb shell dumpsys bluetooth_manager | grep -E 'PairingAlgorithm|le_encrypted'")
        appendLine()
        appendLine("--- Capture logs (${captureLogs.size} on disk) ---")
        captureLogs.take(MAX_FILES_LISTED).forEach { appendLine("${it.name}  ${it.sizeKb}KB") }
        appendLine("--- Link journal (newest ${journal.size.coerceAtMost(MAX_JOURNAL_LINES)}) ---")
        journal.take(MAX_JOURNAL_LINES).forEach { appendLine(Prefs.redactAddresses(it)) }
        appendLine()
        appendLine("--- Crash reports (${crashLogs.size} on disk) ---")
        crashLogs.take(MAX_FILES_LISTED).forEach { appendLine(it.name) }
        if (newestCrashText != null) {
            appendLine()
            appendLine("--- Newest crash report ---")
            append(Prefs.redactAddresses(newestCrashText))
        }
    }

    /** Files named in the listing. The counts above them are the full totals. */
    const val MAX_FILES_LISTED = 3

    /** Journal lines carried. The header states how many were taken. */
    const val MAX_JOURNAL_LINES = 40
}
