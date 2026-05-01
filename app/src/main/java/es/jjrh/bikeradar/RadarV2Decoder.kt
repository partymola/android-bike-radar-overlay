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
 *             speed. Scaling is empirical (not documented in the canonical
 *             protocol spec): 0.25 m/s per LSB (= 0.9 km/h per LSB).
 *             Stationary floor is raw 2 (doppler noise floor, not true
 *             zero). Observed ceiling is raw 63 (~56.7 km/h) as a
 *             single-frame peak; bits 6-7 have never been set across
 *             6,499 device-status frames, so the field may be a 6-bit
 *             value with reserved upper bits or a plain uint8 whose
 *             range is never exercised. Accept the full uint8 range to
 *             stay robust either way. See PROTOCOL.md for the full
 *             evidence base.
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
 * The decoder additionally annotates each emitted [Vehicle] with
 * [Vehicle.isAlongsideStationary] when it represents a near-stationary
 * vehicle next to a slow-moving rider (parked car / queued traffic in the
 * adjacent lane). The flag is recomputed every snapshot from the gating
 * constants in this class's companion.
 *
 * Not thread-safe; call from a single coroutine.
 */
class RadarV2Decoder(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Track(
        val vehicle: Vehicle,
        val lastSeen: Long,
        /** Monotonic millis the first time this tid appeared. Used by the
         *  alongside-stationary dwell-time gate; never updated. */
        val firstSeen: Long,
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

    /** Rider's own bike speed in m/s, last reported by a device-status
     *  frame (byte[len-1] x 0.25 m/s per LSB - native protocol resolution).
     *  Carried as Float so downstream Float thresholds land on the same
     *  raw-byte boundaries the prior km/h thresholds did. Null until
     *  the first such frame. */
    private var lastBikeSpeedMs: Float? = null

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
            // Device-status frame carries the rider's own bike speed in
            // the final byte, scaled by 0.9 km/h (0.25 m/s per LSB).
            // Always emit a snapshot so bikeSpeedMs propagates even
            // when no targets changed.
            if (payload.size > HEADER_SIZE) {
                val raw = payload[payload.size - 1].toInt() and 0xFF
                // 0.25 m/s per LSB (PROTOCOL.md). Keep as Float so the
                // 0.25-m/s resolution propagates to threshold checks
                // exactly; UI converts to km/h at presentation time.
                lastBikeSpeedMs = raw * 0.25f
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

            // Lateral-unknown sentinel: the radar emits rxBits = 0 on a
            // far track when it briefly loses lateral confidence. At
            // rangeY < 10 m a literal 0 is plausible (target dead
            // behind), so the sentinel only applies at rangeY >= 10 m.
            // Requiring the previous frame to have a non-centred lateral
            // position confirms this is a discontinuity, not a target
            // that has actually been centred throughout. When detected,
            // carry the previous lateralPos forward so visual consumers
            // see continuity; the flag tells downstream gates
            // (close-pass detector) that the lateral data on this frame
            // isn't usable. Once the flag fires for a track, the
            // held-over saturated lateralPos becomes the next frame's
            // `prev`, so the detection propagates across the entire run
            // of zero readings without a per-track state machine.
            val lateralUnknown = rxBits == 0 &&
                rangeY >= LATERAL_UNKNOWN_MIN_RANGE_Y_M &&
                prev != null &&
                abs(prev.vehicle.lateralPos) >= LATERAL_UNKNOWN_PREV_LATERAL_THRESHOLD

            val rangeX = if (lateralUnknown) {
                prev.vehicle.lateralPos * LATERAL_FULL_M
            } else {
                rangeXSigned
            }

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
                    lateralUnknown = lateralUnknown,
                ),
                lastSeen = now,
                firstSeen = prev?.firstSeen ?: now,
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

    private fun snapshot(now: Long): RadarState {
        // Alongside-stationary detection is gated on rider speed too, so it
        // is computed at snapshot time (not in ingestTargets) - that way
        // each emitted snapshot reflects the most recent device-status
        // bikeSpeedMs against every track's current state. Null bike speed
        // defaults to "not slow" so the dock never activates without
        // confirmation that the rider is crawling.
        val riderSlow =
            (lastBikeSpeedMs ?: (ALONGSIDE_RIDER_SLOW_MS + 1)) <= ALONGSIDE_RIDER_SLOW_MS
        return RadarState(
            vehicles = tracks.values
                .map { t ->
                    val v = t.vehicle
                    val lateralM = abs(v.lateralPos) * LATERAL_FULL_M
                    val alongside = riderSlow
                        && abs(v.speedMs) <= STATIONARY_SPEED_MS
                        && v.distanceM <= ALONGSIDE_RANGE_Y_M
                        && lateralM >= ALONGSIDE_MIN_LATERAL_M
                        && (now - t.firstSeen) >= ALONGSIDE_MIN_DURATION_MS
                        && !v.isBehind
                    if (alongside) v.copy(isAlongsideStationary = true) else v
                }
                .sortedBy { it.distanceM },
            timestamp = now,
            source = DataSource.V2,
            bikeSpeedMs = lastBikeSpeedMs,
        )
    }

    fun reset() {
        tracks.clear()
        lastBikeSpeedMs = null
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

        // ── Alongside-stationary gate ────────────────────────────────────
        // Parked / queued vehicles in the next lane while the rider crawls
        // past sit on the radar at very close range with ~zero closing
        // speed for many seconds, and their class-sized filled boxes end
        // up overlapping the rider chevron at the top of the panel.
        // Gating these into a hollow edge-docked render needs ALL of
        // these constants to hold; the moment any breaks, the next
        // snapshot reverts to a normal filled box (the visual jump is
        // the attention cue that the target has just woken up).
        /** |speedY| <= this rounds to "near-stationary" given the radar's
         *  0.5 m/s quantisation. Same value as [MOVING_SPEED_MS] on
         *  purpose - they share the "is this thing moving" boundary. */
        const val STATIONARY_SPEED_MS = 1
        /** Maximum rangeY (m) for the alongside dock to consider a target.
         *  Beyond this the target box is too far down the panel to ever
         *  collide with the rider chevron, so normal rendering is fine. */
        const val ALONGSIDE_RANGE_Y_M = 8
        /** Minimum |rangeX| (m) for the alongside dock. A target dead
         *  behind the rider is not the parked-car case - it might be a
         *  follower and must keep its centre-lane render. */
        const val ALONGSIDE_MIN_LATERAL_M = 0.5f
        /** Rider's own bike speed (km/h) below which the dock activates.
         *  At cruising speed even truly parked cars sweep past quickly and
         *  never linger over the chevron; only crawling traffic produces
         *  the multi-second overlap that motivated this rule. */
        /** Rider speed (m/s) at or below which alongside-stationary
         *  docking applies. 2.75 m/s catches raw bytes 0..11 inclusive,
         *  matching the prior 10 km/h gate exactly (raw 11 = 9.9 km/h
         *  rounded to 10 was at the edge; raw 12 = 10.8 km/h was just
         *  above). */
        const val ALONGSIDE_RIDER_SLOW_MS = 2.75f
        /** Minimum dwell time (ms) on a track before the dock activates.
         *  Prevents the visual mode from flipping for a brief slow target
         *  that's about to start closing. */
        const val ALONGSIDE_MIN_DURATION_MS = 3000L

        // ── Lateral-unknown sentinel ─────────────────────────────────────
        /** rangeY (m) at or above which `rangeXBits = 0` is treated as
         *  the radar's "lateral-unknown" signal rather than a real
         *  centred target. Below this distance a literal zero is
         *  plausible (target directly behind), so the sentinel doesn't
         *  apply. */
        const val LATERAL_UNKNOWN_MIN_RANGE_Y_M = 10f
        /** Previous frame's |lateralPos| floor for the lateral-unknown
         *  detection. The bug shows up as a discontinuity from a
         *  saturated lateral (|pos| ~= 1.0 -> 0.0 in one frame); a
         *  threshold of 0.5 (= 1.5 m off-centre) ensures we only flag
         *  when there's a real prior lateral signal to fall back to. */
        const val LATERAL_UNKNOWN_PREV_LATERAL_THRESHOLD = 0.5f
    }
}
