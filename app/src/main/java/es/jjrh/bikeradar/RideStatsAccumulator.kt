// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-JVM accumulator for per-ride statistics published to Home Assistant.
 *
 * Two ingest paths:
 *   - [observeFrame] runs on every decoded RadarState snapshot (multi-Hz).
 *     Updates per-frame integrals (distance, exposure, peak/min running maxima).
 *   - [observeClosePass] runs once per close-pass event the detector emits.
 *     Updates per-event counters and the tightest-pass record.
 *
 * Threading: not thread-safe. Call from a single coroutine context (the
 * radar collect block in BikeRadarService).
 */
class RideStatsAccumulator(
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    val rideStartedAtMs: Long = nowMsProvider()

    // Per-frame state. Nullable for measurement-class fields that have no
    // sensible "no data" zero value: a ride with no observed vehicles should
    // surface "unknown" in HA, not a misleading 0 m / 0 km/h.
    private val seenTrackIds = HashSet<Int>()
    private var peakClosingKmh: Int? = null
    private var minLateralM: Float? = null
    private var distanceRiddenM: Double = 0.0
    private var exposureMs: Long = 0L
    private var lastFrameMs: Long? = null
    private var lastBikeSpeedMs: Float? = null

    // Per-event state.
    private var closePassCount: Int = 0
    private var grazingCount: Int = 0
    private var hgvClosePassCount: Int = 0
    private val closingSpeedSamples = ArrayList<Int>()
    private var tightestPass: TightestPass? = null

    // Generation counter for change detection. Incremented whenever any
    // observable state changes; the publish loop compares against
    // [lastPublishedGeneration] and skips if equal.
    private var generation: Long = 0L
    private var lastPublishedGeneration: Long = -1L

    /**
     * Ingest a RadarState snapshot. The snapshot's `bikeSpeedMs` integrates
     * into [distanceRiddenM] over the wall-clock interval since the previous
     * frame. Vehicles in the snapshot update peak-closing / min-lateral
     * running extrema and the unique-overtake set.
     */
    fun observeFrame(state: RadarState) {
        val nowMs = nowMsProvider()
        val prev = lastFrameMs
        lastFrameMs = nowMs

        if (prev != null) {
            val dtMs = (nowMs - prev).coerceAtLeast(0L)
            // Use the bike speed that applied DURING the just-ended interval —
            // i.e. the value carried from the previous frame, before this
            // frame's update overwrites it.
            val speedForInterval = lastBikeSpeedMs
            if (speedForInterval != null && speedForInterval > 0f) {
                distanceRiddenM += speedForInterval.toDouble() * dtMs / 1000.0
                generation++
            }
            // Exposure: any tracked vehicle (excluding alongside-stationary
            // and lateralUnknown sentinel frames) means the rider is in
            // traffic for this interval.
            val anyTraffic = state.vehicles.any { v ->
                !v.isBehind && !v.isAlongsideStationary && !v.lateralUnknown &&
                    v.distanceM in 0..MAX_TRACK_DISTANCE_M
            }
            if (anyTraffic) {
                exposureMs += dtMs
                generation++
            }
        }

        // Update the carried bike speed AFTER integrating the prior interval.
        // bikeSpeedMs may arrive in device-status frames at lower cadence than
        // target frames; carrying the last known value is the right approximation.
        if (state.bikeSpeedMs != null) lastBikeSpeedMs = state.bikeSpeedMs

        // Per-frame extrema and overtake-id dedup.
        for (v in state.vehicles) {
            if (v.isBehind || v.lateralUnknown) continue
            if (v.distanceM !in 0..MAX_TRACK_DISTANCE_M) continue

            if (seenTrackIds.add(v.id)) generation++

            // peakClosingKmh: only consider approaching targets (negative speedMs).
            if (v.speedMs < 0) {
                val closingKmh = (-v.speedMs * MS_TO_KMH).toInt()
                val current = peakClosingKmh
                if (current == null || closingKmh > current) {
                    peakClosingKmh = closingKmh
                    generation++
                }
            }

            if (!v.isAlongsideStationary) {
                val lateralM = abs(v.lateralPos) * RadarV2Decoder.LATERAL_FULL_M
                val current = minLateralM
                if (current == null || lateralM < current) {
                    minLateralM = lateralM
                    generation++
                }
            }
        }
    }

    /**
     * Ingest a close-pass event. Updates discrete counters, the
     * closing-speed sample for p90, and the tightest-pass record if
     * this event has lower clearance than the current minimum.
     */
    fun observeClosePass(event: ClosePassDetector.Event) {
        closePassCount++
        if (event.severity == ClosePassDetector.Severity.GRAZING) grazingCount++
        if (event.vehicleSize == VehicleSize.TRUCK) hgvClosePassCount++
        closingSpeedSamples.add(event.closingSpeedKmh)

        val current = tightestPass
        if (current == null || event.minRangeXM < current.clearanceM) {
            tightestPass = TightestPass(
                tsMs = event.timestampMs,
                side = event.side,
                vehicleSize = event.vehicleSize,
                clearanceM = event.minRangeXM,
                closingKmh = event.closingSpeedKmh,
                rangeYAtMinM = event.rangeYAtMinM,
            )
        }
        generation++
    }

    /** True when state has changed since the last [markPublished] call. */
    fun changedSinceLast(): Boolean = generation != lastPublishedGeneration

    fun markPublished() { lastPublishedGeneration = generation }

    /** Snapshot the current values. Safe to call any time. */
    fun snapshot(): RideStatsSnapshot {
        val totalOvertakes = seenTrackIds.size
        val conversionRatePct = if (totalOvertakes > 0) {
            closePassCount.toFloat() / totalOvertakes.toFloat() * 100f
        } else {
            0f
        }
        val p90 = if (closingSpeedSamples.isEmpty()) null else percentile(closingSpeedSamples, 0.9)
        return RideStatsSnapshot(
            overtakesTotal = totalOvertakes,
            closePassCount = closePassCount,
            grazingCount = grazingCount,
            hgvClosePassCount = hgvClosePassCount,
            peakClosingKmh = peakClosingKmh,
            closingSpeedP90Kmh = p90,
            minLateralClearanceM = minLateralM,
            distanceRiddenKm = (distanceRiddenM / 1000.0).toFloat(),
            exposureSeconds = exposureMs / 1000L,
            closePassConversionRatePct = conversionRatePct,
            tightestPass = tightestPass,
            rideStartedAtMs = rideStartedAtMs,
        )
    }

    private fun percentile(samples: List<Int>, p: Double): Int {
        require(samples.isNotEmpty())
        val sorted = samples.sorted()
        val rank = (p * (sorted.size - 1)).coerceAtLeast(0.0)
        val lo = rank.toInt()
        val hi = min(lo + 1, sorted.size - 1)
        val frac = rank - lo
        return (sorted[lo] + frac * (sorted[hi] - sorted[lo])).toInt()
    }

    companion object {
        private const val MAX_TRACK_DISTANCE_M = 40
        private const val MS_TO_KMH = 3.6f
    }
}

data class RideStatsSnapshot(
    val overtakesTotal: Int,
    val closePassCount: Int,
    val grazingCount: Int,
    val hgvClosePassCount: Int,
    /** Null until the first approaching vehicle is observed. */
    val peakClosingKmh: Int?,
    /** Null until the first close-pass event fires. */
    val closingSpeedP90Kmh: Int?,
    /** Null until the first vehicle is observed. */
    val minLateralClearanceM: Float?,
    val distanceRiddenKm: Float,
    val exposureSeconds: Long,
    val closePassConversionRatePct: Float,
    val tightestPass: TightestPass?,
    val rideStartedAtMs: Long,
)

data class TightestPass(
    val tsMs: Long,
    val side: ClosePassDetector.Side,
    val vehicleSize: VehicleSize,
    val clearanceM: Float,
    val closingKmh: Int,
    val rangeYAtMinM: Float,
)
