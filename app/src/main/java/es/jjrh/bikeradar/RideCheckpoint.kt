// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import java.io.File

/**
 * Crash-safe checkpoint of the in-flight ride, closing the two windows where
 * a process death silently loses ride data:
 *
 *  - **Mid-ride**: the stats accumulator is memory-only, so an OOM-kill or
 *    crash used to restart the ride from zero with no trace of the first
 *    half. The walk-away tick now checkpoints the latest meaningful snapshot
 *    (marked `partial` - the ride was still running when it was written).
 *  - **Post-ride dwell**: the history append only happens when the summary
 *    posts, three minutes after the radar goes silent - and a parked phone
 *    is exactly when Android reclaims memory. The off-episode edge rewrites
 *    the checkpoint as complete (`partial=false`, ended at the off instant),
 *    so a death inside the dwell no longer erases a finished ride.
 *
 * The next service start flushes any leftover checkpoint straight into the
 * ride history ([RideCheckpointStore.take]); the normal summary path clears
 * it instead. A death in the tiny window between the summary's history
 * append and the checkpoint clear can duplicate one history row on restart -
 * the reverse order would lose the ride instead, and a duplicate is the
 * better failure.
 *
 * [RideCheckpointDecider] is the pure write-gate; [RideCheckpointStore] is
 * the single-slot file (temp-write + rename, so a crash mid-write leaves the
 * previous checkpoint intact; a corrupt slot is discarded, never fatal).
 */
internal object RideCheckpointDecider {

    /**
     * Decide whether the current tick needs a checkpoint write, and build
     * the record if so. The change gate compares the RECORD the write would
     * produce (with the end time zeroed) against the last settled one
     * [prevRecord] - never the raw stats snapshot, whose time-derived fields
     * (alerts-per-hour) drift on every call and would turn each tick into a
     * flash write. Zeroing the end time keeps the gate blind to the two
     * legitimate drifts a record carries: the in-flight "current as of now"
     * end time, and wall-clock adjustments re-basing the off-instant
     * conversion. What remains is exactly the persisted ride data plus the
     * partial flag, so the off-episode is write-free and the off-edge flip
     * to complete still fires. Bench blips never checkpoint (the same
     * meaningful-ride gate the summary uses), so a restart cannot resurrect
     * a non-ride into history.
     */
    fun plan(
        prevRecord: RideHistoryRecord?,
        snap: RideStatsSnapshot,
        radarOffSinceMs: Long?,
        nowMonoMs: Long,
        nowWallMs: Long,
    ): RideHistoryRecord? {
        if (!RideSummaryNotificationDecider.isMeaningful(snap)) return null
        // A finished ride ended when the radar went off (the dwell is
        // detection latency, not riding time); an in-flight one is only
        // "current as of now".
        val endedAtMs = if (radarOffSinceMs != null) {
            ClockConversion.monotonicToWallMs(radarOffSinceMs, nowMonoMs, nowWallMs)
        } else {
            nowWallMs
        }
        val candidate = RideHistoryRecord.fromSnapshot(snap, endedAtMs)
            .copy(partial = radarOffSinceMs == null)
        if (prevRecord != null &&
            candidate.copy(endedAtMs = 0L) == prevRecord.copy(endedAtMs = 0L)
        ) {
            return null
        }
        return candidate
    }
}

/** Single-slot persisted checkpoint. All methods are best-effort and
 *  corruption-tolerant; the checkpoint is a safety net, never a crash source. */
internal class RideCheckpointStore(
    private val externalFilesDir: () -> File?,
) {

    /** Replace the slot atomically: write a temp file, then rename over the
     *  slot, so a crash mid-write leaves the previous checkpoint readable. */
    fun write(record: RideHistoryRecord) {
        val file = checkpointFile() ?: return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(record.toJsonLine())
            if (!tmp.renameTo(file)) {
                // Rename across a broken FS state: fall back to a direct
                // write rather than silently keeping a stale checkpoint.
                file.writeText(record.toJsonLine())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkpoint write failed: $t")
        }
    }

    /** Read AND clear the slot. Null when absent or corrupt (a corrupt slot
     *  is deleted so it cannot wedge every future start). */
    fun take(): RideHistoryRecord? {
        val file = checkpointFile() ?: return null
        if (!file.exists()) return null
        val record = try {
            RideHistoryRecord.fromJsonLine(file.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "checkpoint read failed: $t")
            null
        }
        clear()
        return record
    }

    fun clear() {
        try {
            checkpointFile()?.delete()
        } catch (_: Throwable) {}
    }

    private fun checkpointFile(): File? = externalFilesDir()?.let { root ->
        File(File(root, RideHistoryStore.HISTORY_DIR), FILE_NAME)
    }

    companion object {
        const val FILE_NAME = "checkpoint.json"
        private const val TAG = "BikeRadar.RideHistory"
    }
}
