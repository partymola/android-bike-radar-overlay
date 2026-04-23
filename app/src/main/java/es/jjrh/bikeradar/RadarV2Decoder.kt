// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stateful decoder for the V2 radar notify stream (Garmin 6a4e3204).
 *
 * Packet layout:
 *   [2-byte LE header] + N * [9-byte target struct]
 *
 * Header bits:
 *   0x0001 -> status/ack frame, no target payload (skip).
 *   0x0004 -> device-status frame, no targets (skip).
 *   Anything else -> decode N targets from body.
 *
 * Target struct (9 bytes). Agent C model (2026-04-22 audit):
 *
 *   distance_m = abs(int8(byte[2])) * 0.1
 *   isBehind   = int8(byte[2]) < 0
 *
 * H4 (a "signed-zone-counter" reading of `byte[3] & 7`) was tried and
 * rejected on 2026-04-23. Two pieces of decisive counter-evidence:
 *
 * (1) On the cleanest H4-fits-the-data track (tid 128, 50 frames of
 *     monotone b3_lo 0→1→2 stepping), H4 says the target recedes from
 *     15 m to 66 m at +8.5 m/s, while the radar's independently encoded
 *     byte[7] (speed_y) reports a stable -5 to -6 m/s closing — a 14 m/s
 *     sign-flipped contradiction sustained over 50 frames.
 *
 * (2) Sentinel frames (b2=0, b3_lo=0; 3.4 % of all data) appear mid-track
 *     with rx and vy unchanged from the surrounding frames. Under H4 the
 *     target teleports up to 47 m in one frame. Under Agent C the same
 *     transition is bounded.
 *
 * The H4 crosstab (zone_count derived from b2 wraps vs b3_lo) is
 * tautological: both quantities derive from b2's sign, so the
 * "correlation" is rederivation, not independent evidence.
 *
 * Conclusion: V2 is a ±12.7 m close-range alert stream; tracks beyond
 * that range are not in this characteristic. The 820's 175 m advertised
 * range must be in V1 (6a4e3203, which we cannot subscribe to without
 * killing V2) or another characteristic.
 *
 *   [0]    uint8  targetId       radar-assigned track ID (stable across frames)
 *   [1]    uint8  targetClass    see CLASS_* constants
 *   [2]    int8   rangeY         signed longitudinal offset, x0.1 m,
 *                                 -12.8..+12.7 m. Negative = behind.
 *   [3]    bits 0..2              redundant lagged sign flag for b2 sign.
 *                                 Not consulted - the b2 sign is authoritative.
 *          bits 3..7              uniform 0..31, probably a chirp / sub-frame
 *                                 counter. Not decoded.
 *   [4]    int8   rangeX         lateral offset, x0.1 m, -12.8..+12.7 m
 *                                 (positive = vehicle on the right)
 *   [5]    uint8  length         class template, x0.25 m (not a real measurement)
 *   [6]    uint8  width          class template, x0.25 m (not a real measurement)
 *   [7]    int8   speedY         claimed approach velocity, x0.5 m/s. Poor
 *                                 frame-delta correlation under any decoder;
 *                                 we recompute from frame-to-frame rangeY
 *                                 instead.
 *   [8]    uint8  0x80           constant sentinel across all observed samples
 *
 * We compute speedY from frame-to-frame delta of rangeY rather than trusting
 * byte[7], because the frame-to-frame delta is naturally smoothed by the
 * SPEED_DT_MIN/MAX windowing and byte[7]'s scale is only x0.5 m/s.
 *
 * Not thread-safe; call from a single coroutine.
 */
class RadarV2Decoder(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Track(
        val vehicle: Vehicle,
        val lastSeen: Long,
        val staleMs: Long,
        val speedRefRangeY: Float,
        val speedRefTs: Long,
        /** False until the radar has ever emitted a non-(0,0) template on
         *  bytes [5]/[6] for this tid. Unlocked tracks are still ingested so
         *  stale-prune works, but are suppressed from snapshots — they are
         *  the dominant source of false phantom renders (tid 0x89 on the
         *  2026-04-21 capture spent its entire 8-frame life unlocked). */
        val locked: Boolean,
        /** VehicleSize currently committed to the overlay. Upgrades apply
         *  immediately; downgrades require [DOWNGRADE_FRAMES] consecutive
         *  frames at the smaller size. Prevents mid-overtake box-size flips
         *  when firmware briefly reclassifies a car as NORMAL_STABLE<->HIGH. */
        val committedSize: VehicleSize,
        val downgradeCandidate: VehicleSize?,
        val downgradeFrames: Int,
        /** Consecutive negative-sign byte[2] frames seen but not yet
         *  committed to isBehind=true. Entering isBehind is debounced by
         *  [BEHIND_ENTRY_FRAMES]; exiting is instant on any positive-sign
         *  frame. Protects against firmware edge-oscillation when a
         *  target paces the bike at distance≈0. */
        val pendingBehindFrames: Int = 0,
    )

    private val tracks = HashMap<Int, Track>()

    /**
     * Feed one notification payload. Returns the new [RadarState] if the
     * packet changed anything visible, else null (pure-status frame).
     */
    fun feed(payload: ByteArray): RadarState? {
        val now = nowMs()
        if (payload.size < HEADER_SIZE) return if (pruneStale(now)) snapshot(now) else null
        val header = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val isStatus = header and STATUS_FRAME_BIT != 0
        val isDeviceStatus = header and DEVICE_STATUS_BIT != 0
        if (isStatus || isDeviceStatus) {
            return if (pruneStale(now)) snapshot(now) else null
        }
        ingestTargets(payload, now)
        return snapshot(now)
    }

    private fun ingestTargets(payload: ByteArray, now: Long): Boolean {
        var changed = pruneStale(now)
        val bodyLen = payload.size - HEADER_SIZE
        val n = bodyLen / TARGET_SIZE
        for (i in 0 until n) {
            val off = HEADER_SIZE + i * TARGET_SIZE
            val tid = payload[off].toInt() and 0xFF

            val b5 = payload[off + 5].toInt() and 0xFF
            val b6 = payload[off + 6].toInt() and 0xFF

            // 24-bit little-endian packed range field over bytes [2..4]:
            //   bits 0..10  = rangeX (11-bit signed, x0.1 m, -204.8..+204.7 m)
            //   bits 11..23 = rangeY (13-bit signed, x0.1 m, -409.6..+409.5 m)
            // For this rear radar, rangeY > 0 means behind the bike (the
            // dominant case at ~99% of frames); rangeY < 0 indicates a
            // target that has overtaken and is now in front of the rider.
            val prev = tracks[tid]
            val b2 = payload[off + 2].toInt() and 0xFF
            val b3 = payload[off + 3].toInt() and 0xFF
            val b4 = payload[off + 4].toInt() and 0xFF
            val packed = (b4 shl 16) or (b3 shl 8) or b2
            val rxBits = packed and 0x7FF
            val rxSigned = if ((packed and 0x400) != 0) rxBits - 2048 else rxBits
            val ryBits = (packed shr 11) and 0x1FFF
            val rySigned = if (((packed shr 11) and 0x1000) != 0) ryBits - 8192 else ryBits
            val rangeXSigned = rxSigned * 0.1f
            val rangeYSigned = rySigned * 0.1f

            // isBehind here means "target ahead of bike" (post-overtake),
            // matching the prior decoder's filter semantics: filtered out of
            // overlay + alert paths because the rear radar can't reliably
            // track once a target is in front.
            val signSaysAhead = rangeYSigned < 0f
            val newPendingBehindFrames =
                if (signSaysAhead) (prev?.pendingBehindFrames ?: 0) + 1 else 0
            val isBehind = signSaysAhead && newPendingBehindFrames >= BEHIND_ENTRY_FRAMES

            val rangeY = kotlin.math.abs(rangeYSigned)
            val effectiveDistance = rangeY.roundToInt()
            val rangeX = rangeXSigned

            val rawSpeedMs: Int
            val newRefRangeY: Float
            val newRefTs: Long
            // Transitioning from an in-front zone to behind (or vice versa)
            // makes frame-to-frame rangeY delta meaningless for velocity,
            // so reset the speed reference at the crossover.
            val sideFlipped = prev != null && prev.vehicle.isBehind != isBehind
            if (prev == null || sideFlipped) {
                rawSpeedMs = 0
                newRefRangeY = rangeY
                newRefTs = now
            } else {
                val dtRefMs = now - prev.speedRefTs
                when {
                    dtRefMs > SPEED_DT_MAX_MS -> {
                        rawSpeedMs = 0
                        newRefRangeY = rangeY
                        newRefTs = now
                    }
                    dtRefMs < SPEED_DT_MIN_MS -> {
                        rawSpeedMs = prev.vehicle.speedMs
                        newRefRangeY = prev.speedRefRangeY
                        newRefTs = prev.speedRefTs
                    }
                    else -> {
                        val slope = (prev.speedRefRangeY - rangeY) * 1000f / dtRefMs
                        rawSpeedMs = slope.roundToInt().coerceIn(-SPEED_CAP_MS, SPEED_CAP_MS)
                        newRefRangeY = rangeY
                        newRefTs = now
                    }
                }
            }
            val speedMs = rawSpeedMs

            val rawSize = classifySize(payload[off + 1].toInt() and 0xFF)
            val lateralPos = (rangeX / LATERAL_FULL_M).coerceIn(-1f, 1f)

            val stale = if (abs(speedMs) > MOVING_SPEED_MS) STALE_MOVING_MS else STALE_PARKED_MS

            val frameLocked = b5 != 0 || b6 != 0
            val locked = prev?.locked == true || frameLocked

            val debounced = debounceSize(prev, rawSize)

            tracks[tid] = Track(
                vehicle = Vehicle(
                    id = tid,
                    distanceM = effectiveDistance,
                    speedMs = speedMs,
                    size = debounced.committed,
                    lateralPos = lateralPos,
                    isBehind = isBehind,
                ),
                lastSeen = now,
                staleMs = stale,
                speedRefRangeY = newRefRangeY,
                speedRefTs = newRefTs,
                locked = locked,
                committedSize = debounced.committed,
                downgradeCandidate = debounced.candidate,
                downgradeFrames = debounced.frames,
                pendingBehindFrames = newPendingBehindFrames,
            )
            changed = true
        }
        return changed
    }

    private data class SizeDebounceResult(
        val committed: VehicleSize,
        val candidate: VehicleSize?,
        val frames: Int,
    )

    /** Upgrades commit immediately; downgrades need [DOWNGRADE_FRAMES]
     *  consecutive frames at the smaller bucket before committing. Rationale:
     *  firmware promotes class as evidence accumulates (safe to follow), but
     *  brief HIGH<->NORMAL flips mid-overtake should not resize the overlay box. */
    private fun debounceSize(prev: Track?, raw: VehicleSize): SizeDebounceResult {
        if (prev == null) return SizeDebounceResult(raw, null, 0)
        val committed = prev.committedSize
        if (sizeRank(raw) >= sizeRank(committed)) {
            return SizeDebounceResult(raw, null, 0)
        }
        // Proposed size is smaller than what's on the overlay.
        val nextFrames = if (raw == prev.downgradeCandidate) prev.downgradeFrames + 1 else 1
        return if (nextFrames >= DOWNGRADE_FRAMES) {
            SizeDebounceResult(raw, null, 0)
        } else {
            SizeDebounceResult(committed, raw, nextFrames)
        }
    }

    private fun sizeRank(s: VehicleSize): Int = when (s) {
        VehicleSize.BIKE -> 0
        VehicleSize.CAR -> 1
        VehicleSize.TRUCK -> 2
    }

    private fun pruneStale(now: Long): Boolean {
        val before = tracks.size
        val it = tracks.entries.iterator()
        while (it.hasNext()) {
            val t = it.next().value
            if (now - t.lastSeen > t.staleMs) it.remove()
        }
        return tracks.size != before
    }

    private fun snapshot(now: Long): RadarState =
        RadarState(
            vehicles = tracks.values
                .filter { it.locked }
                .map { it.vehicle }
                .sortedBy { it.distanceM },
            timestamp = now,
            source = DataSource.V2,
        )

    fun reset() {
        tracks.clear()
    }

    private fun classifySize(cls: Int): VehicleSize = when (cls) {
        CLASS_LOW, CLASS_LOW_STABLE -> VehicleSize.BIKE
        CLASS_HIGH -> VehicleSize.TRUCK
        else -> VehicleSize.CAR
    }

    companion object {
        const val HEADER_SIZE = 2
        const val TARGET_SIZE = 9
        const val STATUS_FRAME_BIT = 0x0001
        const val DEVICE_STATUS_BIT = 0x0004

        const val CLASS_UNKNOWN = 4
        const val CLASS_LOW_STABLE = 13
        const val CLASS_LOW = 16
        const val CLASS_NORMAL = 23
        const val CLASS_NORMAL_STABLE = 26
        const val CLASS_HIGH = 36

        const val MOVING_SPEED_MS = 1
        const val STALE_MOVING_MS = 800L
        const val STALE_PARKED_MS = 5000L
        const val SPEED_DT_MIN_MS = 150L
        const val SPEED_DT_MAX_MS = 1500L
        const val SPEED_CAP_MS = 30
        const val LATERAL_FULL_M = 3.0f
        /** Consecutive frames required at a smaller size before committing a
         *  downgrade. ~90 ms per frame observed, so 5 ~= 450 ms. */
        const val DOWNGRADE_FRAMES = 5
        const val BEHIND_ENTRY_FRAMES = 2
    }
}
