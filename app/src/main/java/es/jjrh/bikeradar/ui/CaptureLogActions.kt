// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Context
import es.jjrh.bikeradar.CaptureLogFiles
import es.jjrh.bikeradar.CaptureLogManager
import java.io.File

/**
 * The capture-log actions the Debug screen offers, as plain functions.
 *
 * They live here rather than in `DebugScreen.kt` so they stay under the
 * diff-coverage gate: that file is a render/wiring leaf and is excluded from
 * it, on the same grounds as the Canvas view and the debug services. Anything
 * in this file is a decision about a rider's data - which logs are offered,
 * which may be deleted, what happens before one leaves the phone - so it is
 * gated, unit-tested and mutation-checked. Keep it that way: logic added to
 * the screen file instead is silently ungated.
 */
/**
 * What happens when the rider taps Share on a capture log.
 *
 * A function rather than a lambda body because both halves are easy to get
 * wrong silently. [flush] must run FIRST and unconditionally: the log may still
 * be open, the writer is buffered on purpose (autoFlush would cost a syscall
 * per BLE notify), so without it a shared in-progress log ends mid-window and
 * the last thing that happened is the part missing. It is a no-op for a closed
 * file and null when the service is not running, so there is nothing to decide
 * and no name comparison to get wrong.
 *
 * [warningSeen] reads the V2 key deliberately. The dialog body names the radar
 * hardware identifiers a setup transcript records, so a rider who dismissed the
 * older dialog has to meet it once more, and that rider is exactly the one
 * filing a hardware report.
 */
internal fun onCaptureLogShareRequested(
    flush: (() -> Unit)?,
    warningSeen: Boolean,
    share: () -> Unit,
    requestWarning: () -> Unit,
) {
    flush?.invoke()
    if (warningSeen) share() else requestWarning()
}

/**
 * The subset of [logs] that Delete all may actually remove.
 *
 * Everything except the file the writer currently holds. Deleting that one
 * unlinks it under a live PrintWriter, and the failure is entirely silent:
 * the writer keeps feeding the unlinked inode, `open()` will not start a
 * replacement while a writer exists, and `close()` skips the gzip because the
 * source is gone. The rider loses the whole session and is told nothing.
 *
 * This guard used to be free. While the list excluded the open file, Delete
 * all could not reach it; listing that file for sharing removed the accident
 * that was protecting this path, so the guard is now explicit and pinned.
 * [CaptureLogManager.prune] carries the same exclusion for the same reason.
 */
internal fun deletableCaptureLogs(logs: List<File>, activeName: String?): List<File> = logs.filter { it.name != activeName }

/** Delete every log Delete all is allowed to touch, returning how many went.
 *  A function rather than a loop in the confirm lambda so the guard and the
 *  deletion cannot drift apart: the count the dialog shows and the files it
 *  removes come from one place. */
internal fun deleteCaptureLogsExceptActive(logs: List<File>, activeName: String?): Int {
    val doomed = deletableCaptureLogs(logs, activeName)
    doomed.forEach { it.delete() }
    return doomed.size
}

/**
 * Enumerate the shareable/deletable capture logs on disk, newest first.
 *
 * INCLUDING the one currently being written, which used to be filtered out.
 * That exclusion is reasonable for an ordinary ride log and wrong for the
 * setup transcript, whose whole purpose is diagnosing a radar that never
 * connects: it deliberately stays open across the reconnect loop, so hiding
 * open files hid precisely the file a reporter needs, and getting at it meant
 * a toggle-off dance that only works while the radar is still switched on.
 * The row marks it as recording, flushes before sharing, and refuses delete.
 *
 * Re-run after a delete so the list reflects the true on-disk state rather
 * than a stale in-memory copy.
 */
internal fun enumerateCaptureLogs(ctx: Context): List<File> = ctx.getExternalFilesDir(null)
    ?.let { File(it, CaptureLogManager.CAPTURE_DIR) }
    ?.listFiles { f -> CaptureLogFiles.isCaptureLog(f) && f.length() > 0L }
    ?.sortedByDescending { it.lastModified() }
    ?: emptyList()
