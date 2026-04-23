// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stateful decoder for the V2 radar notify stream on characteristic
 * `6a4e3204`.
 *
 * Packet layout:
 *   [2-byte little-endian header] + N * [9-byte target struct]
 *
 * Header bits:
 *   0x0001 -> status/ack frame, no target payload.
 *   0x0004 -> device-status frame; the final byte is the rider's own bike
 *             speed, scaled by 0.25 km/h.
 *   Anything else -> body is N consecutive 9-byte target structs.
 *
 * Target struct (9 bytes):
 *   [0]    uint8  targetId       radar-assigned track ID (stable across frames)
 *   [1]    uint8  targetClass    see CLASS_* constants
 *   [2..4] 24-bit little-endian packed range field:
 *            bits 0..10  = rangeX (11-bit signed, x0.1 m)
 *            bits 11..23 = rangeY (13-bit signed, x0.1 m)
 *          For this rear radar, rangeY > 0 means the target is behind the
 *          bike (the dominant case); rangeY < 0 means a target that has
 *          overtaken and is now ahead of the rider. Positive rangeX = right.
 *   [5]    uint8  length         class template, x0.25 m
 *   [6]    uint8  width          class template, x0.25 m
 *   [7]    int8   speedY         signed closing speed, x0.5 m/s
 *                                 (negative = approaching).
 *   [8]    int8   speedX         signed lateral speed, x0.5 m/s; raw 0x80
 *                                 is a sentinel meaning "no lateral
 *                                 velocity available".
 *
 * See PROTOCOL.md in the bike-radar-docs sibling repo for the full spec.
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
        /** VehicleSize currently committed to the overlay. Upgrades apply
         *  immediately; downgrades require [DOWNGRADE_FRAMES] consecutive
         *  frames at the smaller size. Prevents mid-overtake box-size flips
         *  when firmware briefly reclassifies a car as NORMAL_STABLE<->HIGH. */
        val committedSize: VehicleSize,
        val downgradeCandidate: VehicleSize?,
        val downgradeFrames: Int,
    )

    private val tracks = HashMap<Int, Track>()

    /** Rider's own bike speed in km/h, last reported by a device-status
     *  frame (byte[len-1] x 0.25 km/h). Null until the first such frame. */
    private var lastBikeSpeedKmh: Int? = null

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
        if (isDeviceStatus) {
            // Device-status frame carries the rider's own bike speed in the
            // final byte, scaled by 0.25 km/h. Always emit a snapshot so
            // bikeSpeedKmh propagates even when no targets changed.
            if (payload.size > HEADER_SIZE) {
                val raw = payload[payload.size - 1].toInt() and 0xFF
                lastBikeSpeedKmh = (raw * 0.25f).roundToInt()
            }
            pruneStale(now)
            return snapshot(now)
        }
        if (isStatus) {
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

            // 24-bit little-endian packed range field over bytes [2..4]:
            //   bits 0..10  = rangeX (11-bit signed, x0.1 m, -204.8..+204.7 m)
            //   bits 11..23 = rangeY (13-bit signed, x0.1 m, -409.6..+409.5 m)
            // For this rear radar, rangeY > 0 means behind the bike;
            // rangeY < 0 indicates a target that has overtaken and is now
            // in front of the rider.
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

            // isBehind means "target ahead of bike" (post-overtake);
            // the overlay and alert paths filter these out because the rear
            // radar cannot reliably track once a target is in front.
            val isBehind = rangeYSigned < 0f

            val rangeY = abs(rangeYSigned)
            val effectiveDistance = rangeY.roundToInt()
            val rangeX = rangeXSigned

            val speedMs = (payload[off + 7].toInt() * 0.5f).roundToInt()

            val speedXRaw = payload[off + 8].toInt() and 0xFF
            val speedXMs: Int? = if (speedXRaw == LATERAL_VELOCITY_SENTINEL) {
                null
            } else {
                (payload[off + 8].toInt() * 0.5f).roundToInt()
            }

            val rawSize = classifySize(payload[off + 1].toInt() and 0xFF)
            val lateralPos = (rangeX / LATERAL_FULL_M).coerceIn(-1f, 1f)

            val stale = if (abs(speedMs) > MOVING_SPEED_MS) STALE_MOVING_MS else STALE_PARKED_MS

            val debounced = debounceSize(prev, rawSize)

            tracks[tid] = Track(
                vehicle = Vehicle(
                    id = tid,
                    distanceM = effectiveDistance,
                    speedMs = speedMs,
                    size = debounced.committed,
                    lateralPos = lateralPos,
                    isBehind = isBehind,
                    speedXMs = speedXMs,
                ),
                lastSeen = now,
                staleMs = stale,
                committedSize = debounced.committed,
                downgradeCandidate = debounced.candidate,
                downgradeFrames = debounced.frames,
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
                .map { it.vehicle }
                .sortedBy { it.distanceM },
            timestamp = now,
            source = DataSource.V2,
            bikeSpeedKmh = lastBikeSpeedKmh,
        )

    fun reset() {
        tracks.clear()
        lastBikeSpeedKmh = null
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
        const val LATERAL_FULL_M = 3.0f
        /** Consecutive frames required at a smaller size before committing a
         *  downgrade. ~90 ms per frame observed, so 5 ~= 450 ms. */
        const val DOWNGRADE_FRAMES = 5
        /** Raw byte[8] value the radar emits when no lateral velocity is
         *  available for that target. Decoded as a null [Vehicle.speedXMs]. */
        const val LATERAL_VELOCITY_SENTINEL = 0x80
    }
}
