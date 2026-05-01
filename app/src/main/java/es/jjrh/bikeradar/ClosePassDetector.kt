// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-JVM per-track state machine that emits a single event for every
 * genuinely-close vehicle pass. Fed one frame (vehicles + rider bike
 * speed + timestamp) at a time; returns the list of events that fired
 * on that frame.
 *
 * Design target: signal, not volume. London commuting produces a steady
 * trickle of "over 1.5 m but not by much" passes — logging those is
 * noise. This detector only fires for passes where the minimum lateral
 * clearance the radar ever saw drops below [Config.emitMinRangeX] AND
 * the vehicle was actually overtaking (closing-speed floor) AND the
 * rider was actually riding (rider-speed floor).
 *
 * Per-track state machine:
 *   WATCHING   - track not yet armed
 *   ARMED      - armed once all gates passed at least once; min-rangeX
 *                tracked until the track ends
 *   (terminal) - track ends -> maybe emit depending on tracked minimum
 *
 * One emit per track lifecycle. A global [Config.cooldownMs] cooldown
 * between emits catches the decoder's track-ID churn where a single
 * physical vehicle briefly becomes two tracks.
 */
class ClosePassDetector {
    data class Config(
        /** Master on/off. When false, decide() is a no-op. */
        val enabled: Boolean,
        /** Minimum rider bike speed (m/s) for the detector to arm.
         *  Filters out stationary-rider scenarios: red lights with
         *  cross-traffic in a right-turn lane, pushing the bike, etc.
         *  4.25 m/s arms at raw 17+, matching the prior 15 km/h gate
         *  exactly. */
        val riderSpeedFloorMs: Float = 4.25f,
        /** Minimum closing speed (m/s) for the detector to arm.
         *  Filters out lane-matched cruising and filtering — if the
         *  vehicle isn't genuinely overtaking, it's not a close pass
         *  event we care to log. */
        val closingSpeedFloorMs: Int = 6,
        /** Arm when |rangeX| drops under this. Tiered by rider speed
         *  at ARM time in [decide]: riderSpeed <= 30 km/h uses
         *  [armRangeXUrbanM]; above uses [armRangeXRuralM]. */
        val armRangeXUrbanM: Float = 1.5f,
        val armRangeXRuralM: Float = 2.0f,
        /** Emit the event only if the tracked minimum rangeX drops
         *  below this. Everything above is logged-but-not-published
         *  via the state machine (dropped at emit time). This is the
         *  strict-gate philosophy: noise rejected in the decider, not
         *  in a downstream dashboard filter. */
        val emitMinRangeXM: Float = 1.0f,
        /** Global cooldown between emits. */
        val cooldownMs: Long = 2_000L,
        /** Minimum frames observed before the detector will arm a
         *  track. Guards against single-frame decoder blips. */
        val minFramesToArm: Int = 3,
        /** Maximum rangeY at which the detector considers a target.
         *  Beyond this the vehicle is too far to be a "pass". */
        val maxRangeYM: Int = 40,
    )

    enum class Severity {
        /** Min rangeX < 0.5 m. Absolutely unsafe. */
        GRAZING,

        /** Min rangeX in [0.5, emitMinRangeXM). Illegal under UK 1.5 m
         *  rule, dangerous. */
        VERY_CLOSE,
    }

    data class Event(
        val timestampMs: Long,
        val minRangeXM: Float,
        val side: Side,
        val rangeYAtMinM: Float,
        val closingSpeedKmh: Int,
        val riderSpeedKmh: Int,
        val vehicleSize: VehicleSize,
        val thresholdArmedM: Float,
        val severity: Severity,
    )

    enum class Side { LEFT, RIGHT }

    private data class TrackState(
        val tid: Int,
        var framesSeen: Int = 0,
        var armed: Boolean = false,
        var armedThresholdM: Float = 0f,
        /** Minimum |rangeX| observed since arming. Float.MAX_VALUE
         *  until first armed sample. */
        var minAbsRangeXM: Float = Float.MAX_VALUE,
        var minRangeXSignedM: Float = 0f,
        var minRangeYM: Float = 0f,
        var closingSpeedAtMinKmh: Int = 0,
        var riderSpeedAtMinKmh: Int = 0,
        var sizeAtMin: VehicleSize = VehicleSize.CAR,
        var timestampAtMinMs: Long = 0L,
        /** Last frame we saw this tid, for pruning the state map when
         *  the radar decoder drops a track. */
        var lastSeenMs: Long = 0L,
    )

    private val tracks = HashMap<Int, TrackState>()
    private var lastEmitMs: Long = Long.MIN_VALUE / 2

    /**
     * Feed one snapshot to the detector.
     *
     * @param vehicles the current vehicle list from [RadarState]
     * @param bikeSpeedMs rider's own bike speed (null when the decoder
     *   hasn't yet received a device-status frame; the detector is
     *   strictly gated on a known rider speed)
     * @param nowMs monotonic timestamp
     * @return any [Event]s that fired on this frame. Usually empty.
     */
    fun decide(
        vehicles: List<Vehicle>,
        bikeSpeedMs: Float?,
        nowMs: Long,
        config: Config,
    ): List<Event> {
        if (!config.enabled) return emptyList()
        val riderMs = bikeSpeedMs ?: return emptyList()

        val emitted = mutableListOf<Event>()
        val currentTids = HashSet<Int>(vehicles.size)

        for (v in vehicles) {
            currentTids.add(v.id)
            // Ignore targets we've decided aren't overtake candidates.
            // Note: isBehind in this codebase means "target has
            // overtaken and is now in front" — a passing overtake
            // completes by flipping this true, which we use downstream
            // as the termination signal. We still track them while
            // they're genuinely behind (isBehind == false).
            val state = tracks.getOrPut(v.id) { TrackState(v.id) }
            state.lastSeenMs = nowMs
            state.framesSeen++

            // Skip targets already marked isBehind (they've finished
            // passing and we either already emitted or no longer care).
            if (v.isBehind) continue

            // Skip targets the decoder has flagged as alongside-stationary
            // (parked / queued vehicle next to a slow rider). The decoder
            // applies dwell + lateral + closing-speed gates upstream; an
            // alongside flag means this is not an overtake and shouldn't
            // influence min-rangeX tracking. Without this skip, a real
            // overtake that ends with the rider braking to a junction
            // stop alongside the just-overtaken vehicle (both then
            // near-stationary at the junction) would have its minRangeX
            // falsely pulled toward zero by the close alongside frames,
            // emitting a bogus close-pass event when the track
            // terminates.
            if (v.isAlongsideStationary) continue

            // Skip frames where the decoder couldn't determine lateral
            // position reliably. The decoder's lateralUnknown flag fires
            // on far-range frames where the radar emits its rangeXBits=0
            // sentinel; without this skip those frames pull min-rangeX
            // to zero artificially.
            if (v.lateralUnknown) continue

            // Arm the track if all gates pass.
            if (!state.armed) {
                val rangeYOk = v.distanceM in 0..config.maxRangeYM
                val closingOk = v.speedMs <= -config.closingSpeedFloorMs
                val sizeOk = v.size == VehicleSize.CAR || v.size == VehicleSize.TRUCK
                val riderOk = riderMs >= config.riderSpeedFloorMs
                val framesOk = state.framesSeen >= config.minFramesToArm
                // Urban-cruise branch when rider speed <= 8.25 m/s
                // (catches raw 0..33 inclusive, matching the prior
                // 30 km/h cut exactly).
                val armThreshold = if (riderMs <= 8.25f) config.armRangeXUrbanM else config.armRangeXRuralM
                val lateralOk = abs(v.lateralPos * LATERAL_FULL_M) <= armThreshold
                if (rangeYOk && closingOk && sizeOk && riderOk && framesOk && lateralOk) {
                    state.armed = true
                    state.armedThresholdM = armThreshold
                }
            }

            // Once armed, update the min tracking each frame.
            if (state.armed) {
                val rangeXAbsM = abs(v.lateralPos * LATERAL_FULL_M)
                if (rangeXAbsM < state.minAbsRangeXM) {
                    state.minAbsRangeXM = rangeXAbsM
                    state.minRangeXSignedM = v.lateralPos * LATERAL_FULL_M
                    state.minRangeYM = v.distanceM.toFloat()
                    state.closingSpeedAtMinKmh = (abs(v.speedMs) * 3.6f).toInt()
                    // Convert at the boundary: HA wire format keeps km/h
                    // (`rider_speed_kmh`) so historic Recorder/InfluxDB
                    // dashboards aren't broken by the unit migration.
                    state.riderSpeedAtMinKmh = (riderMs * 3.6f).roundToInt()
                    state.sizeAtMin = v.size
                    state.timestampAtMinMs = nowMs
                }
            }
        }

        // Terminate tracks that aren't present this frame (decoder
        // dropped them: overtake completed, or went stale). Also
        // terminate any track whose vehicle now shows isBehind — the
        // overtake has finished.
        val terminatingIds = mutableListOf<Int>()
        for ((tid, state) in tracks) {
            val presentVehicle = vehicles.firstOrNull { it.id == tid }
            val dropped = tid !in currentTids
            val justCrossedAhead = presentVehicle?.isBehind == true && state.armed &&
                state.minAbsRangeXM < Float.MAX_VALUE
            if (dropped || justCrossedAhead) {
                val event = maybeEmit(state, nowMs, config)
                if (event != null) emitted.add(event)
                terminatingIds.add(tid)
            }
        }
        for (tid in terminatingIds) tracks.remove(tid)

        return emitted
    }

    private fun maybeEmit(state: TrackState, nowMs: Long, config: Config): Event? {
        if (!state.armed) return null
        if (state.minAbsRangeXM >= config.emitMinRangeXM) return null
        if (nowMs - lastEmitMs < config.cooldownMs) return null

        val severity = if (state.minAbsRangeXM < 0.5f) Severity.GRAZING else Severity.VERY_CLOSE
        val side = if (state.minRangeXSignedM >= 0f) Side.RIGHT else Side.LEFT

        lastEmitMs = nowMs
        return Event(
            timestampMs = state.timestampAtMinMs,
            minRangeXM = state.minAbsRangeXM,
            side = side,
            rangeYAtMinM = state.minRangeYM,
            closingSpeedKmh = state.closingSpeedAtMinKmh,
            riderSpeedKmh = state.riderSpeedAtMinKmh,
            vehicleSize = state.sizeAtMin,
            thresholdArmedM = state.armedThresholdM,
            severity = severity,
        )
    }

    /** Reset all tracking state. Call when the radar connection drops
     *  so a stale in-flight track from a prior session doesn't fire a
     *  phantom event on reconnect. */
    fun reset() {
        tracks.clear()
        lastEmitMs = Long.MIN_VALUE / 2
    }

    companion object {
        /** Decoder's ±lateralPos 1.0 maps to this metres each side.
         *  Kept in sync with [RadarV2Decoder.LATERAL_FULL_M]. */
        private const val LATERAL_FULL_M = RadarV2Decoder.LATERAL_FULL_M
    }
}
