// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Local, append-only ride history: one JSON line per finished ride,
 * written when the post-ride summary fires (see
 * [BikeRadarService.maybePostRideSummary]) and read back by the Ride
 * history screen.
 *
 * Deliberately minimal - a single JSON-lines file in app-private
 * storage, no database. The history is a per-ride log, not a sync
 * target: it never leaves the phone, holds no location, and is capped
 * at [MAX_RIDES] (oldest dropped) so it can't grow unbounded.
 *
 * Thread-safety: [append] runs on the service's walk-away tick (IO
 * dispatcher) and [readAll] on a UI-launched IO coroutine, on separate
 * instances (service vs screen). The lock therefore lives on the
 * companion - one process-global monitor for the one process-global
 * file - so a read can never observe the truncate-and-rewrite window
 * of [trimIfNeeded].
 *
 * Corrupt lines (interrupted write, manual edit) are skipped on read
 * rather than failing the whole file.
 */
internal class RideHistoryStore(
    private val externalFilesDir: () -> File?,
) {

    /** Append one finished ride; trims the file to [MAX_RIDES] entries. */
    fun append(record: RideHistoryRecord) {
        synchronized(lock) {
            val file = historyFile() ?: return
            try {
                file.parentFile?.mkdirs()
                file.appendText(record.toJsonLine() + "\n")
                trimIfNeeded(file)
            } catch (t: Throwable) {
                Log.w(TAG, "ride-history append failed: $t")
            }
        }
    }

    /** All stored rides, newest first. Empty when no history exists. */
    fun readAll(): List<RideHistoryRecord> {
        synchronized(lock) {
            val file = historyFile() ?: return emptyList()
            if (!file.exists()) return emptyList()
            return try {
                file.readLines()
                    .mapNotNull { RideHistoryRecord.fromJsonLine(it) }
                    .asReversed()
            } catch (t: Throwable) {
                Log.w(TAG, "ride-history read failed: $t")
                emptyList()
            }
        }
    }

    private fun historyFile(): File? = externalFilesDir()?.let { root ->
        File(File(root, HISTORY_DIR), FILE_NAME)
    }

    private fun trimIfNeeded(file: File) {
        val lines = file.readLines()
        if (lines.size <= MAX_RIDES) return
        val kept = lines.takeLast(MAX_RIDES)
        file.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val HISTORY_DIR = "ride-history"
        const val FILE_NAME = "rides.jsonl"
        const val MAX_RIDES = 200
        private const val TAG = "BikeRadar.RideHistory"

        /** Process-global: the service's writer instance and any screen's
         *  reader instance must contend on the same monitor. */
        private val lock = Any()
    }
}

/**
 * One finished ride. Field set mirrors the post-ride summary
 * notification plus the measurement extrema the HA ride-summary sensor
 * publishes, so the local history is useful standalone (no HA).
 * Nullable fields follow [RideStatsSnapshot]: null = "no data", never a
 * misleading zero.
 */
internal data class RideHistoryRecord(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val overtakes: Int,
    val closePasses: Int,
    val grazingPasses: Int,
    val hgvClosePasses: Int,
    val peakClosingKmh: Int?,
    val closingSpeedP90Kmh: Int?,
    val minLateralClearanceM: Float?,
    val distanceKm: Float,
    val exposureSeconds: Long,
    val alertsPerKm: Float?,
    val tightestPassClearanceM: Float?,
    val tightestPassClosingKmh: Int?,
) {
    fun toJsonLine(): String = JSONObject()
        .put("v", SCHEMA_VERSION)
        .put("start_ms", startedAtMs)
        .put("end_ms", endedAtMs)
        .put("overtakes", overtakes)
        .put("close_passes", closePasses)
        .put("grazing", grazingPasses)
        .put("hgv_close_passes", hgvClosePasses)
        .putOpt("peak_closing_kmh", peakClosingKmh)
        .putOpt("closing_p90_kmh", closingSpeedP90Kmh)
        .putOpt("min_lateral_m", minLateralClearanceM)
        .put("distance_km", distanceKm.toDouble())
        .put("exposure_s", exposureSeconds)
        .putOpt("alerts_per_km", alertsPerKm)
        .putOpt("tightest_m", tightestPassClearanceM)
        .putOpt("tightest_kmh", tightestPassClosingKmh)
        .toString()

    companion object {
        /** Bump when a field changes meaning; readers tolerate unknown
         *  keys, so additions don't need a bump. */
        const val SCHEMA_VERSION = 1

        fun fromSnapshot(snap: RideStatsSnapshot, endedAtMs: Long): RideHistoryRecord = RideHistoryRecord(
            startedAtMs = snap.rideStartedAtMs,
            endedAtMs = endedAtMs,
            overtakes = snap.overtakesTotal,
            closePasses = snap.closePassCount,
            grazingPasses = snap.grazingCount,
            hgvClosePasses = snap.hgvClosePassCount,
            peakClosingKmh = snap.peakClosingKmh,
            closingSpeedP90Kmh = snap.closingSpeedP90Kmh,
            minLateralClearanceM = snap.minLateralClearanceM,
            distanceKm = snap.distanceRiddenKm,
            exposureSeconds = snap.exposureSeconds,
            alertsPerKm = snap.alertsPerKm,
            tightestPassClearanceM = snap.tightestPass?.clearanceM,
            tightestPassClosingKmh = snap.tightestPass?.closingKmh,
        )

        /** Parse one stored line; null for blank or corrupt lines. */
        fun fromJsonLine(line: String): RideHistoryRecord? {
            if (line.isBlank()) return null
            return try {
                val o = JSONObject(line)
                RideHistoryRecord(
                    startedAtMs = o.getLong("start_ms"),
                    endedAtMs = o.getLong("end_ms"),
                    overtakes = o.getInt("overtakes"),
                    closePasses = o.getInt("close_passes"),
                    grazingPasses = o.optInt("grazing", 0),
                    hgvClosePasses = o.optInt("hgv_close_passes", 0),
                    peakClosingKmh = o.optIntOrNull("peak_closing_kmh"),
                    closingSpeedP90Kmh = o.optIntOrNull("closing_p90_kmh"),
                    minLateralClearanceM = o.optFloatOrNull("min_lateral_m"),
                    distanceKm = o.getDouble("distance_km").toFloat(),
                    exposureSeconds = o.getLong("exposure_s"),
                    alertsPerKm = o.optFloatOrNull("alerts_per_km"),
                    tightestPassClearanceM = o.optFloatOrNull("tightest_m"),
                    tightestPassClosingKmh = o.optIntOrNull("tightest_kmh"),
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

        private fun JSONObject.optFloatOrNull(key: String): Float? = if (has(key) && !isNull(key)) {
            getDouble(key).toFloat()
        } else {
            null
        }
    }
}
