// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.util.Log
import es.jjrh.bikeradar.data.Prefs
import java.io.File

/**
 * Redacts device identifiers from text that can leave the app.
 *
 * Logcat is not private to the app. A release build writes to it like any
 * other, `adb bugreport` collects it wholesale, and a bug report is a file a
 * rider hands to someone else. A Bluetooth MAC is a durable identifier for a
 * specific piece of hardware, so it is the one field withheld there.
 *
 * The capture log is a different surface and already handles this its own way:
 * it is opt-in, it stays on the phone unless the rider shares it, and its
 * logcat mirror is debug-only. [mac] governs the logcat path, which has no such
 * gate.
 *
 * Why a helper rather than editing the call sites: there were eleven of them
 * across five files, and a convention holds only until the twelfth. A site
 * that logs an address and does not come through [mac] is the thing to catch
 * in review - grep for `.address` as well as `$mac`, because the first sweep
 * matched only the variable name and missed the site that inlined the
 * expression.
 *
 * Scope of [mac], stated because the obvious reading is wider: it withholds
 * Bluetooth addresses and nothing else. Device names still reach release logcat
 * by design, and so does a Home Assistant hostname when a connection fails,
 * because the exception message carries it. [shareable] is for the text a rider
 * reads, copies and shares from inside the app.
 */
internal object LogRedaction {

    /** Placeholder written in place of an address a release build must not
     *  emit. Deliberately not an empty string: the field stays in the line so
     *  a reader sees a value was withheld rather than a log that never had
     *  one. */
    const val WITHHELD = "(mac)"

    /**
     * A Bluetooth address as it should appear in a logcat line.
     *
     * Debug builds get the real address, because a developer reading their own
     * device's log needs to tell two radios apart. Release builds get
     * [WITHHELD]. Device NAMES are left alone and still appear beside this in
     * most call sites, which is what keeps a release log diagnosable at all.
     */
    fun mac(mac: String?, debug: Boolean = BuildConfig.DEBUG): String = when {
        mac == null -> "-"
        debug -> mac
        else -> WITHHELD
    }

    /**
     * [text] without what could name one rider's hardware: addresses go, and a
     * word holding four or more digits is masked from its first digit, which is
     * where some devices put a serial ("XYZ73521", "Cam 58213", "A1B2C3D4").
     *
     * Default-deny: a long number survives only as `key=value` with a lowercase
     * key and no digits in it, a duration in `ms` or ` s`, a date, or a `0x`
     * status, so a new journal line with a bare number is masked until it takes
     * one of those shapes. `DiagnosticBundleTest` pins the shapes, and that an
     * identifier with under four digits to a word, or none, stays visible.
     */
    fun shareable(text: String): String = if (text.isBlank()) {
        text
    } else {
        KEPT_NUMBER_OR_WORD.replace(Prefs.redactAddresses(text)) { match ->
            val word = match.groups[1]?.value
            if (word == null || word.count { it in '0'..'9' } < MIN_IDENTIFIER_DIGITS) {
                match.value
            } else {
                val firstDigit = word.indexOfFirst { it in '0'..'9' }
                word.take(firstDigit) + "#".repeat(word.length - firstDigit)
            }
        }
    }

    /**
     * Masks what an upgraded install already holds. Everything written since is
     * masked on the way in, so this is needed once: [Prefs.storedFilesMasked] is
     * set only after every file passed, and any failure retries at the next
     * start (`StoredFilesMaskTest`).
     */
    fun maskStoredFilesOnce(prefs: Prefs, root: File?) {
        if (prefs.storedFilesMasked || root == null) return
        val reports = CrashLogger.maskStoredReports(File(root, CrashLogger.CRASH_DIR))
        val journal = LinkEventJournal.maskStoredLines(root)
        if (reports && journal) prefs.storedFilesMasked = true
    }

    /** True when every file in [dir] that [accept] takes is masked, or [dir] is
     *  not there. Clears what a death mid-pass left, first. */
    fun maskFilesIn(dir: File, accept: (File) -> Boolean, mask: (String) -> String): Boolean {
        dir.listFiles { f -> f.name.startsWith(SIBLING_PREFIX) }?.forEach { it.delete() }
        // Sorted for a fixed order, so a test can put the failing file first.
        val files = dir.listFiles { f -> accept(f) }?.sorted() ?: return !dir.exists()
        return files.map { maskFile(it, mask = mask) }.all { it }
    }

    /**
     * Rewrites [file] through a sibling and a rename, so a death mid-write leaves
     * it whole, and leaves it alone when [mask] changes nothing. The sibling's
     * name keeps it out of the Debug list and the prune. A date that cannot be
     * set does not stop this: the file then sorts as the newest. Never throws,
     * so one bad file leaves the others to be masked.
     */
    fun maskFile(
        file: File,
        setDate: (File, Long) -> Boolean = File::setLastModified,
        rename: (File, File) -> Boolean = File::renameTo,
        mask: (String) -> String,
    ): Boolean = try {
        val raw = file.readText()
        val masked = mask(raw)
        if (masked == raw) {
            true
        } else {
            val sibling = File(file.parentFile, SIBLING_PREFIX + file.name)
            sibling.writeText(masked)
            if (!setDate(sibling, file.lastModified())) Log.w(TAG, "could not keep a masked file's date")
            rename(sibling, file)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not mask ${file.name}: $t")
        false
    }

    private const val TAG = "BikeRadar.Redaction"

    private const val SIBLING_PREFIX = ".masking-"

    private const val MIN_IDENTIFIER_DIGITS = 4

    private val KEPT_NUMBER_OR_WORD =
        Regex("""[a-z]+=[0-9]+|[0-9]+ms\b|[0-9]+ s\b|[0-9]{4}-[0-9]{2}-[0-9]{2}\b|0x[0-9A-Fa-f]+\b|([A-Za-z0-9]+)""")
}
