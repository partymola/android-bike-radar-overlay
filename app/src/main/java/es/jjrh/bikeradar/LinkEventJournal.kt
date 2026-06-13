// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Always-on, size-capped journal of BLE link events: connect attempts,
 * GATT state changes, handshake outcomes, watchdog teardowns, bond
 * changes, reconnect scheduling. One line per event.
 *
 * Why it exists: the capture log is opt-in and only open while a radar
 * connection is live, so exactly the events that explain a connection
 * FAILURE (the attempts that never reached the decode loop, the
 * disconnect storm at 3 am, the bond loss) used to vanish. The journal
 * is cheap enough to keep on for everyone - a handful of short lines
 * per link transition - and survives across rides, so a "why didn't it
 * reconnect yesterday?" question has an answer on the Debug screen.
 *
 * Not a capture log: no radar packets, no movement data, no location -
 * just link lifecycle with device names. Capped at [MAX_BYTES] /
 * trimmed to [KEEP_LINES], so it can't grow unbounded under a flapping
 * link.
 *
 * Thread-safety: [log] is called from GATT callback threads and IO
 * coroutines; [readTail] from the Debug screen. The lock is on the
 * companion (one process-global monitor for the one process-global
 * file), matching [RideHistoryStore].
 */
internal class LinkEventJournal(
    private val externalFilesDir: () -> File?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Append one event line, stamped with local wall-clock time. */
    fun log(event: String) {
        synchronized(lock) {
            val file = journalFile() ?: return
            try {
                file.parentFile?.mkdirs()
                val stamp = SimpleDateFormat(STAMP_FORMAT, Locale.ROOT).format(Date(nowMs()))
                file.appendText("$stamp $event\n")
                if (file.length() > MAX_BYTES) {
                    val kept = file.readLines().takeLast(KEEP_LINES)
                    file.writeText(kept.joinToString("\n", postfix = "\n"))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "journal append failed: $t")
            }
        }
    }

    /** The newest [maxLines] events, newest first. */
    fun readTail(maxLines: Int = TAIL_LINES): List<String> {
        synchronized(lock) {
            val file = journalFile() ?: return emptyList()
            if (!file.exists()) return emptyList()
            return try {
                file.readLines().filter { it.isNotBlank() }.takeLast(maxLines).asReversed()
            } catch (t: Throwable) {
                Log.w(TAG, "journal read failed: $t")
                emptyList()
            }
        }
    }

    private fun journalFile(): File? = externalFilesDir()?.let { root ->
        File(File(root, JOURNAL_DIR), FILE_NAME)
    }

    companion object {
        const val JOURNAL_DIR = "link-journal"
        const val FILE_NAME = "link-events.log"

        /** Trim trigger; ~128 KB is months of normal link churn. */
        const val MAX_BYTES = 128 * 1024L
        const val KEEP_LINES = 600
        const val TAIL_LINES = 100
        private const val STAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
        private const val TAG = "BikeRadar.LinkJournal"

        /** Process-global: the service's writer and the Debug screen's
         *  reader are separate instances contending on one file. */
        private val lock = Any()
    }
}
