// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Owns the per-ride capture log: open/close (one file per radar connection),
 * the buffered append + periodic flush, packet/line tagging, and retention
 * (prune + opportunistic gzip). Extracted from [BikeRadarService] so the file
 * lifecycle is unit-testable and the service is smaller; behaviour is unchanged.
 *
 * Thread-safety: [clog]/[clogPacket] are called from BLE GATT callback threads,
 * IO coroutines and the Main overlay loop; all writes serialise through [lock].
 *
 * Opt-in: when [captureLoggingEnabled] returns false, [open] is a no-op and no
 * file is created, so every [clog] then no-ops on the null writer.
 *
 * Dependencies are injected so the class is JVM/Robolectric-constructible:
 * [externalFilesDir] supplies the app-private external root, [mirror] is the
 * debug-only logcat echo (kept out of release builds by the caller), and
 * [onActiveName] mirrors the active file name to wherever the UI reads it.
 */
internal class CaptureLogManager(
    private val externalFilesDir: () -> File?,
    private val captureLoggingEnabled: () -> Boolean,
    private val mirror: (String) -> Unit = {},
    private val onActiveName: (String?) -> Unit = {},
) {
    private val lock = Any()

    @Volatile private var writer: PrintWriter? = null

    // Wall-clock of the last flush; drives the periodic flush in writeLine.
    // Guarded by [lock].
    private var lastFlushMs: Long = 0L

    private var activeName: String? = null

    /** Open a fresh capture file for this connection (no-op when logging is off). */
    fun open() {
        if (!captureLoggingEnabled()) return
        val root = externalFilesDir() ?: return
        val dir = File(root, CAPTURE_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "bike-radar-capture-$stamp.log")
        try {
            // No autoFlush: it write()s on every println, defeating the
            // BufferedWriter and adding a syscall per BLE notify (~11 Hz).
            // close() flushes on the normal path; writeLine flushes at most every
            // FLUSH_INTERVAL_MS, so an abnormal kill loses at most one window.
            val pw = PrintWriter(BufferedWriter(FileWriter(file)))
            synchronized(lock) {
                writer = pw
                // Force the first line (the header) of a fresh log to flush.
                lastFlushMs = 0L
            }
            activeName = file.name
            onActiveName(file.name)
            clog("# bike-radar capture started ${java.time.Instant.now()}")
            clog("# format: unix_ms char_tail_4hex hex_bytes_no_spaces")
            Log.i(TAG, "capture log: ${file.absolutePath}")
            // Prune after the new file exists so steady-state count is
            // MAX_CAPTURE_LOGS, not MAX_CAPTURE_LOGS+1 (the active file is
            // skipped by name inside prune).
            prune()
        } catch (t: Throwable) {
            Log.w(TAG, "failed to open capture log: $t")
        }
    }

    /** Flush + close the active file, then gzip it (off the live write path). */
    fun close() {
        synchronized(lock) {
            writer?.flush()
            writer?.close()
            writer = null
        }
        val closedName = activeName
        activeName = null
        onActiveName(null)
        // Gzip the just-finalised file. Runs after the PrintWriter close so the
        // live write path is never on a gzip stream (which would lose its
        // un-finalised tail on a crash). Failure preserves the .log so the next
        // prune pass can retry.
        if (closedName != null) {
            val dir = externalFilesDir()?.let { File(it, CAPTURE_DIR) }
            if (dir != null) {
                val src = File(dir, closedName)
                if (src.exists()) CaptureLogFiles.gzipAndDelete(src, TAG)
            }
        }
    }

    /** Append one line; mirrors to the injected [mirror] sink (debug logcat). */
    fun clog(msg: String) {
        writeLine(msg)
        mirror(msg)
    }

    /** Append a tagged packet line: `unix_ms char_tail_4hex hex`. */
    fun clogPacket(uuid: UUID, bytes: ByteArray) {
        // Chars 4-7 of the first UUID segment are the tag (e.g. "3203", "3204",
        // "2a19"). All Garmin chars share the ...9a66 suffix, so those nibbles
        // are the meaningful discriminator.
        val tag = uuid.toString().substring(4, 8)
        writeLine("${System.currentTimeMillis()} $tag ${bytes.toHex()}")
    }

    private fun writeLine(line: String) {
        synchronized(lock) {
            val w = writer ?: return
            w.println(line)
            val now = System.currentTimeMillis()
            if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                w.flush()
                lastFlushMs = now
            }
        }
    }

    /** Retention pass: drop header-only files, gzip stragglers, cap the count. */
    fun prune() {
        val dir = externalFilesDir()?.let { File(it, CAPTURE_DIR) } ?: return
        val logs = dir.listFiles { f -> CaptureLogFiles.isCaptureLog(f) } ?: return
        val active = activeName
        // A real session logs thousands of packet lines; anything under a few
        // hundred bytes is just the header + maybe a connect-state line from a
        // session where the radar never connected. Only applies to plain `.log`
        // files - a `.log.gz` is always small (a real session compresses small,
        // but even a multi-KB plain session can gzip below the threshold).
        val tiny = logs.filter {
            it.name != active &&
                !CaptureLogFiles.isGzipped(it) &&
                it.length() < MIN_USEFUL_LOG_BYTES
        }
        tiny.forEach { it.delete() }
        // Gzipped archives always pass the size gate (sized for plain text);
        // only the plain-text gate has to discriminate.
        var remaining = logs.filter {
            it.name != active &&
                (CaptureLogFiles.isGzipped(it) || it.length() >= MIN_USEFUL_LOG_BYTES)
        }
        // Opportunistic backfill: gzip any plain .log that isn't the active
        // target. close() gzips on the normal path; this covers logs left plain
        // by a previous install (pre-gzip code) or by a crash before close ran.
        // .gz outputs replace their .log sources so the cap is computed on the
        // final set.
        var backfilled = 0
        remaining = remaining.map { src ->
            if (!CaptureLogFiles.isGzipped(src)) {
                val gz = CaptureLogFiles.gzipAndDelete(src, TAG)
                if (gz != null) backfilled++
                gz ?: src
            } else {
                src
            }
        }
        val keepFromOld = if (active != null) MAX_CAPTURE_LOGS - 1 else MAX_CAPTURE_LOGS
        if (remaining.size <= keepFromOld) {
            if (tiny.isNotEmpty() || backfilled > 0) {
                Log.d(TAG, "deleted ${tiny.size} header-only + gzipped $backfilled legacy capture logs")
            }
            return
        }
        val pruned = remaining.sortedByDescending { it.lastModified() }.drop(keepFromOld)
        pruned.forEach { it.delete() }
        Log.d(
            TAG,
            "deleted ${tiny.size} header-only + ${pruned.size} old + gzipped $backfilled legacy capture logs",
        )
    }

    companion object {
        const val CAPTURE_DIR = "captures"
        const val MAX_CAPTURE_LOGS = 50
        const val MIN_USEFUL_LOG_BYTES = 500L
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val TAG = "BikeRadar.Capture"
    }
}
