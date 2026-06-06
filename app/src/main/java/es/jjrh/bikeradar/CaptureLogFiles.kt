// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Single source of truth for the capture-log file shape: the
 * `bike-radar-capture-YYYYMMDD-HHMMSS.log` (active write target) /
 * `.log.gz` (post-close compressed archive) naming, the listing predicate
 * used by [CaptureLogManager.prune] and the Debug screen, and
 * the gzip helper used at log-close and during opportunistic backfill.
 *
 * Gzipping happens AFTER `closeCaptureLog` has flushed + closed the
 * PrintWriter; the live write path never touches a gzip stream so a crash
 * mid-ride still produces a valid plain `.log` (which the next prune pass
 * will then compress). 5-10x space saving on the steady-state cache; the
 * 500-file cap on phone storage drops from ~150 MB to ~15-30 MB.
 */
internal object CaptureLogFiles {

    private const val PREFIX = "bike-radar-capture-"
    private const val PLAIN_SUFFIX = ".log"
    private const val GZ_SUFFIX = ".log.gz"

    /** True for every form of capture-log file - both the active write
     *  target (`.log`) and the post-close archive (`.log.gz`). */
    fun isCaptureLog(file: File): Boolean = isCaptureLogName(file.name)

    fun isCaptureLogName(name: String): Boolean = name.startsWith(PREFIX) && (name.endsWith(PLAIN_SUFFIX) || name.endsWith(GZ_SUFFIX))

    /** Already-compressed archive. */
    fun isGzipped(file: File): Boolean = file.name.endsWith(GZ_SUFFIX)

    /**
     * Gzip [src] into a sibling `.gz` file, then delete the source. No-op
     * if [src] is already gzipped. Returns the resulting `.log.gz` [File]
     * on success, null on failure (the source is preserved in the failure
     * case so the next prune pass can retry).
     */
    fun gzipAndDelete(src: File, tag: String = "BikeRadar"): File? {
        if (isGzipped(src)) return src
        val dst = File(src.parentFile, "${src.name}.gz")
        return try {
            FileInputStream(src).use { input ->
                GZIPOutputStream(FileOutputStream(dst)).use { gz ->
                    input.copyTo(gz)
                }
            }
            // Sanity guard: never delete the source if the destination is
            // unexpectedly empty (the copy succeeded but the FS reports 0
            // bytes - unlikely but cheap to check).
            if (dst.length() > 0L) {
                src.delete()
                dst
            } else {
                dst.delete()
                Log.w(tag, "gzip of ${src.name} produced an empty archive; source preserved")
                null
            }
        } catch (t: Throwable) {
            Log.w(tag, "gzip of ${src.name} failed: $t")
            // Leaving a partial .gz behind would confuse the next prune pass
            // (it'd see a half-baked archive next to the still-present source);
            // clean it up so the retry starts from a known state.
            try {
                dst.delete()
            } catch (_: Throwable) {}
            null
        }
    }
}
