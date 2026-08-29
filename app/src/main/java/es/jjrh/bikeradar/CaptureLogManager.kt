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
 * [externalFilesDir] supplies the app-private external root,
 * [captureLoggingEnabled] is the opt-in read, [mirror] is the debug-only
 * logcat echo (kept out of release builds by the caller), [onActiveName]
 * mirrors the active file name to wherever the UI reads it, and [clockAnchor]
 * samples the two time bases for the header.
 *
 * [buildStamp] is written into every file's header rather than once per
 * process: a mid-ride radar drop starts a new file, so a per-process stamp
 * would leave the second half of a ride unattributable to the code that
 * produced it. See [BuildStamp] for why it names a commit.
 */
internal class CaptureLogManager(
    private val externalFilesDir: () -> File?,
    private val captureLoggingEnabled: () -> Boolean,
    private val mirror: (String) -> Unit = {},
    private val onActiveName: (String?) -> Unit = {},
    private val buildStamp: () -> String = { BuildConfigStamp.line() },
    /** Samples both clocks ADJACENTLY and renders the header's anchor line.
     *  The two reads have to stay in one place: the whole value of the line
     *  is that the offset between the bases is accurate, and a caller that
     *  passed them in separately could interleave work between them. */
    private val clockAnchor: () -> String = {
        anchorLine(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
    },
) {
    private val lock = Any()

    @Volatile private var writer: PrintWriter? = null

    // Wall-clock of the last flush; drives the periodic flush in writeLine.
    // Guarded by [lock].
    private var lastFlushMs: Long = 0L

    private var activeName: String? = null

    /** Open a fresh capture file for this connection. No-op when a file is
     *  already open: setup-transcript mode opens at connection start and spans
     *  the reconnect loop, so the post-handshake open() on the same connection
     *  must keep the file rather than rotate it.
     *
     *  When logging is off this CLOSES an open file rather than just returning.
     *  A setup transcript spans the reconnect loop, so a rider switching the
     *  master toggle off mid-loop would otherwise keep feeding a file that
     *  toggle is meant to govern. The Debug screen promises the switch takes
     *  effect on the next radar connection, and transcript mode calls this at
     *  the start of every attempt, so that is where the promise is kept. */
    fun open() {
        if (!captureLoggingEnabled()) {
            if (writer != null) close()
            return
        }
        if (writer != null) return
        val root = externalFilesDir() ?: return
        val dir = File(root, CAPTURE_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "bike-radar-capture-$stamp.log")
        // Resolved before the writer exists: a throwing injected stamp would
        // otherwise land in the catch below, which would log "failed to open"
        // while the writer was live and skip the retention pass. runCatching
        // because open() is called from the BLE connect path, which has no
        // catch of its own - a throw there would abort the connection before
        // the overlay/alert pipeline attaches.
        val stampLine = runCatching { buildStamp() }.getOrElse { "# app stamp unavailable" }
        val anchor = runCatching { clockAnchor() }.getOrElse { "# clock unavailable" }
        try {
            // No autoFlush: it write()s on every println, defeating the
            // BufferedWriter and adding a syscall per BLE notify (~11 Hz).
            // close() flushes on the normal path; writeLine flushes at most every
            // FLUSH_INTERVAL_MS, so an abnormal kill loses at most one window.
            val pw = PrintWriter(BufferedWriter(FileWriter(file)))
            // Check and install in one step. What the recheck guards is a
            // second concurrent open(), NOT a close: a close landing in the gap
            // leaves writer null, so the install is then correct. Only
            // RadarLinkController's connection coroutine opens, so nothing can
            // produce that second caller today - the recheck is here so a
            // future second opener cannot silently orphan a live writer.
            val installed = synchronized(lock) {
                if (writer != null) {
                    false
                } else {
                    writer = pw
                    // Force the first line (the header) of a fresh log to flush.
                    lastFlushMs = 0L
                    true
                }
            }
            if (!installed) {
                // Closed, never deleted. Names carry second resolution, so a
                // loser inside the same second computes the SAME path as the
                // winner and would delete the file the winner is writing into -
                // whose fd survives unlinked, so the capture would be lost with
                // no error. An empty orphan is cheaper: the Debug list skips
                // zero-length files and prune sweeps it.
                pw.close()
                return
            }
            activeName = file.name
            onActiveName(file.name)
            clog("# bike-radar capture started ${java.time.Instant.now()}")
            clog("# format: unix_ms char_tail_4hex hex_bytes_no_spaces")
            clog(stampLine)
            // After the stamp, so the stamp's position in the header is
            // unchanged for anything already reading it.
            clog(anchor)
            // Flush the whole header now. lastFlushMs above only forces the
            // FIRST line out; without this the format line, the stamp and the
            // clock anchor sit in the buffer for up to FLUSH_INTERVAL_MS, so a
            // hard kill early in a ride would leave a log that cannot be
            // attributed to a build or placed on the wall clock.
            flushNow()
            Log.i(TAG, "capture log: ${file.absolutePath}")
            // Prune after the new file exists so steady-state count is
            // MAX_CAPTURE_LOGS, not MAX_CAPTURE_LOGS+1 (the active file is
            // skipped by name inside prune).
            prune()
        } catch (t: Throwable) {
            Log.w(TAG, "failed to open capture log: $t")
        }
    }

    /** Flush + close the active file, then gzip it (off the live write path).
     *
     *  The name is taken and cleared INSIDE the lock, so two callers racing
     *  here cannot both come away with it. `scope.cancel()` does not join, so
     *  the service's teardown close and the connection coroutine's `finally`
     *  can overlap; if both read the same name they both gzip the same source
     *  into one truncating stream, and the second to finish sees a non-empty
     *  output and deletes the original. That loses the transcript silently and
     *  reports success. Exactly one caller gets a non-null name. */
    fun close() {
        val closedName = synchronized(lock) {
            writer?.flush()
            writer?.close()
            writer = null
            val name = activeName
            activeName = null
            name
        }
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

    /** Crash-path flush: push the buffered tail to disk without closing.
     *  Registered with [CrashLogger.emergencyFlush] by the service - the last
     *  [FLUSH_INTERVAL_MS] window of ride data is exactly what a crash report
     *  needs for context. Safe no-op when no file is open. */
    fun flushNow() {
        synchronized(lock) {
            writer?.flush()
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
        // A closed setup transcript is a .gz and so always survives this gate;
        // a single aborting attempt's transcript can be under the threshold in
        // plain form, so raising the threshold is not free.
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
        /** The header's clock anchor: one wall-clock reading and one
         *  monotonic reading of the same instant.
         *
         *  Packet lines are prefixed with `unix_ms`, but the sampled series
         *  written from sensors - `# turn yaw ts_mono=` - is elapsedRealtime,
         *  and the two bases have no fixed relationship across a reboot. With
         *  no anchor, placing a yaw sample on wall time means correlating it
         *  against the packet lines around it, which is an estimate with a
         *  spread rather than a conversion. Subtracting the two values here
         *  gives the offset at the instant they were read.
         *
         *  That offset holds for the rest of the file only while the wall
         *  clock is not stepped: `System.currentTimeMillis` is settable, so
         *  an NTP correction or a manual change mid-ride moves the packet
         *  stamps and leaves the anchor where it was. Nothing in the file
         *  records that it happened, so a conversion far from the header is
         *  worth sanity-checking against the packet lines around it. */
        internal fun anchorLine(unixMs: Long, monoMs: Long): String = "# clock unix_ms=$unixMs mono_ms=$monoMs"

        const val CAPTURE_DIR = "captures"
        const val MAX_CAPTURE_LOGS = 50
        const val MIN_USEFUL_LOG_BYTES = 500L
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val TAG = "BikeRadar.Capture"
    }
}
