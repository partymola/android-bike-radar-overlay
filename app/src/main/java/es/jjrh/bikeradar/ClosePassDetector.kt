// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-JVM per-track state machine that emits at most one event per
 * genuinely-close vehicle pass. Fed one frame (vehicles + rider bike
 * speed + timestamp) at a time; returns the list of events that fired
 * on that frame.
 *
 * NOT every close pass, for three reasons, all deliberate and all in the
 * same direction: under-report rather than invent a clearance the radar
 * never saw. A pass it never measured laterally emits nothing, because
 * every one of its frames is skipped (see the `lateralUnknown` skip
 * below); `a pass made entirely of lateral-unknown frames emits nothing`
 * pins that. A pass whose sideways readings are all the radar's exact
 * zero emits nothing, which also silences a genuine pass within half a
 * quantum of boresight; `a pass the radar never resolved sideways emits
 * nothing` pins it. And a pass where the vehicle never came alongside
 * emits nothing, because the clearance is taken only from the frames
 * inside [ALONGSIDE_MAX_RANGE_Y_M]; `a vehicle that never comes
 * alongside emits nothing` pins that.
 *
 * Design target: signal, not volume. London commuting produces a steady
 * trickle of "over 1.5 m but not by much" passes — logging those is
 * noise. This detector only fires for passes whose clearance where the
 * vehicle drew level (see [passPoint]) is below [Config.emitMinRangeXM]
 * AND the vehicle was actually overtaking (closing-speed floor) AND the
 * rider was actually riding (rider-speed floor).
 *
 * Per-track state machine:
 *   WATCHING   - track not yet armed
 *   ARMED      - armed once all gates passed at least once; the alongside
 *                frames are kept from then on, so an armed track can reach
 *                termination having kept nothing at all
 *   (terminal) - track ends -> maybe emit, from its [passPoint]
 *
 * One emit per track lifecycle. A global [Config.cooldownMs] cooldown
 * between emits catches the decoder's track-ID churn where a single
 * physical vehicle briefly becomes two tracks.
 */
class ClosePassDetector {
    data class Config(
        /** Master on/off. When false, decide() is a no-op. */
        val enabled: Boolean,
        /** Minimum rider bike speed (m/s) for the detector to arm. 0 here;
         *  the app passes the rider's setting,
         *  `Prefs.closePassRiderSpeedFloorKmh`. */
        val riderSpeedFloorMs: Float = 0f,
        /** Minimum closing speed (m/s) for the detector to arm.
         *  Filters out lane-matched cruising and filtering — if the
         *  vehicle isn't genuinely overtaking, it's not a close pass
         *  event we care to log. Float at the radar's 0.5 m/s native
         *  quantum (raw byte * 0.5). */
        val closingSpeedFloorMs: Float = 6f,
        /** Emit the event only if the clearance is below this.
         *  Everything above is logged-but-not-published
         *  via the state machine (dropped at emit time). This is the
         *  strict-gate philosophy: noise rejected in the decider, not
         *  in a downstream dashboard filter. */
        val emitMinRangeXM: Float = 1.0f,
        /** Global cooldown between emits. */
        val cooldownMs: Long = 2_000L,
        /** Minimum frames observed before the detector will arm a
         *  track. Guards against single-frame decoder blips. */
        val minFramesToArm: Int = 3,
    )

    enum class Severity {
        /** Clearance < 0.5 m. */
        GRAZING,

        /** Clearance in [0.5, emitMinRangeXM). */
        VERY_CLOSE,
    }

    /** One close pass. The time, clearance, side, distance, rider speed and
     *  size are read off the [passPoint] frame. */
    data class Event(
        val timestampMs: Long,
        /** Metres, unsigned; [side] carries the sign. */
        val clearanceM: Float,
        val side: Side,
        val rangeYM: Float,
        /** The fastest closing reading within [TRACKING_RANGE_M] behind the
         *  rider, readings above [PEAK_CLOSING_MAX_MS] left out: a car often
         *  slows as it draws level, so the reading at the pass point is near
         *  zero. */
        val closingSpeedKmh: Int,
        val riderSpeedKmh: Int,
        val vehicleSize: VehicleSize,
        /** [Config.emitMinRangeXM] when the pass was logged. */
        val emitThresholdM: Float,
        val severity: Severity,
    )

    enum class Side { LEFT, RIGHT }

    private class Sample(
        val rangeXSignedM: Float,
        val rangeYM: Float,
        val riderSpeedKmh: Int,
        val size: VehicleSize,
        val timestampMs: Long,
    )

    private class TrackState(
        val tid: Int,
        /** [Vehicle.bornAtMs] of the car this state belongs to. */
        val bornAtMs: Long,
    ) {
        var framesSeen = 0
        var armed = false
        var peakClosingMs = 0f

        /** Measured frames inside [ALONGSIDE_MAX_RANGE_Y_M] since arming. */
        val alongside = ArrayList<Sample>()
    }

    private val tracks = HashMap<Int, TrackState>()
    private var lastEmitMs: Long = Long.MIN_VALUE / 2

    /**
     * Feed one snapshot to the detector.
     *
     * @param vehicles the current vehicle list from [RadarState]
     * @param bikeSpeedMs rider's own bike speed (null when the decoder
     *   hasn't yet received a device-status frame; the detector is
     *   strictly gated on a known rider speed)
     * @param nowMs wall-clock timestamp (currentTimeMillis): gates the emit
     *   dedup cooldown and is captured as the emitted [Event.timestampMs], which
     *   is rendered downstream as a unix epoch, so it must be wall, not monotonic
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
            // A new birth on the same id is a different car, even with no
            // frame between them: the old one ends here
            // (`a reused track id with no gap frame does not inherit the last car's arming`,
            // `a new car taking the id is when the last car's pass is logged`).
            val previous = tracks[v.id]
            if (previous != null && previous.bornAtMs != v.bornAtMs) {
                maybeEmit(previous, nowMs, config)?.let { emitted.add(it) }
                tracks.remove(v.id)
            }
            val state = tracks.getOrPut(v.id) { TrackState(v.id, v.bornAtMs) }
            state.framesSeen++

            // isBehind in this codebase means "target has overtaken and is
            // now in front": a passing overtake completes by flipping it true,
            // which the termination below reads. Nothing more is taken from it.
            if (v.isBehind) continue

            // Over the frames the ride's published peak closing speed reads,
            // so the ride's closing-speed p90 stays at or under that peak
            // (`OverlayPipelineDrivingTest.aFastApproachFromFarBackLeavesTheP90AtThePeak`),
            // and before the skips below, which are about the lateral reading
            // (`the approach peak counts frames the clearance cannot use`).
            if (v.distanceM in 0..TRACKING_RANGE_M && -v.speedMs <= PEAK_CLOSING_MAX_MS) {
                state.peakClosingMs = maxOf(state.peakClosingMs, -v.speedMs)
            }

            // Skip targets the decoder has flagged as alongside-stationary
            // (parked / queued vehicle next to a slow rider). The decoder
            // applies dwell + lateral + closing-speed gates upstream; an
            // alongside flag means this is not an overtake and shouldn't
            // influence the clearance. Without this skip, a real overtake
            // that ends with the rider braking to a junction stop alongside
            // the just-overtaken vehicle (both then near-stationary at the
            // junction) would have its clearance dragged toward zero by the
            // close alongside frames, emitting a bogus close-pass event when
            // the track terminates.
            if (v.isAlongsideStationary) continue

            // Skip frames where the decoder couldn't determine lateral
            // position reliably. The decoder's lateralUnknown flag fires
            // wherever the radar emits its rangeXBits=0 sentinel, close range
            // included once a run has started; without this skip a held-over
            // offset would be taken as a clearance. What it costs when a whole
            // pass is flagged is in this class's KDoc.
            if (v.lateralUnknown) continue

            // [Vehicle.lateralUnknown] does not cover every exact zero: it
            // starts a run only on a far, non-centred track, since within 10 m
            // the decoder reads a zero as a plausible dead-behind target. Read
            // RAW, because the mount-offset correction moves a zero off zero
            // and would hide it from a rider who has set one.
            if (abs(v.rangeXmRaw) < RAW_LATERAL_EPSILON) continue

            // Arm the track if all gates pass. No lateral gate: a car often
            // closes fast out to the side and comes in only as it slows, so no
            // one frame is both, and the clearance is judged at the pass
            // anyway (`a car that closes while off to the side and comes in as
            // it slows still counts`).
            if (!state.armed) {
                val rangeYOk = v.distanceM in 0..TRACKING_RANGE_M
                val closingOk = v.speedMs <= -config.closingSpeedFloorMs && -v.speedMs <= PEAK_CLOSING_MAX_MS
                val riderOk = riderMs >= config.riderSpeedFloorMs
                val framesOk = state.framesSeen >= config.minFramesToArm
                if (rangeYOk && closingOk && riderOk && framesOk) state.armed = true
            }

            if (state.armed && v.distanceM <= ALONGSIDE_MAX_RANGE_Y_M) {
                state.alongside.add(
                    Sample(
                        rangeXSignedM = v.lateralPos * LATERAL_FULL_M,
                        rangeYM = v.distanceM.toFloat(),
                        // Convert at the boundary: HA wire format keeps km/h
                        // (`rider_speed_kmh`) so historic Recorder/InfluxDB
                        // dashboards aren't broken by the unit migration.
                        riderSpeedKmh = (riderMs * 3.6f).roundToInt(),
                        size = v.size,
                        timestampMs = nowMs,
                    ),
                )
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
            // No "has a sample" conjunct: an armed track that never came
            // alongside has none, and holding it open leaves a stale armed
            // state for the decoder to hand to the next vehicle on the same
            // tid. maybeEmit already refuses a track with no sample.
            val justCrossedAhead = presentVehicle?.isBehind == true && state.armed
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
        val pass = passPoint(state.alongside) { abs(it.rangeXSignedM) } ?: return null
        val clearanceM = abs(pass.rangeXSignedM)
        if (clearanceM >= config.emitMinRangeXM) return null
        if (nowMs - lastEmitMs < config.cooldownMs) return null

        val severity = if (clearanceM < 0.5f) Severity.GRAZING else Severity.VERY_CLOSE
        val side = if (pass.rangeXSignedM >= 0f) Side.RIGHT else Side.LEFT

        lastEmitMs = nowMs
        return Event(
            timestampMs = pass.timestampMs,
            clearanceM = clearanceM,
            side = side,
            rangeYM = pass.rangeYM,
            closingSpeedKmh = (state.peakClosingMs * 3.6f).toInt(),
            riderSpeedKmh = pass.riderSpeedKmh,
            vehicleSize = pass.size,
            emitThresholdM = config.emitMinRangeXM,
            severity = severity,
        )
    }

    companion object {
        /** Farthest rangeY (m) at which a vehicle counts as tracked: beyond it
         *  a vehicle is too far to be a pass. The detector arms within it, and
         *  the ride's statistics and the event's approach peak read it
         *  (`does not arm when target stays beyond the tracking range`,
         *  `the approach peak is taken within the 40 m the ride's own peak reads`,
         *  `overtakesTotalSkipsTracksBeyond40m`). A constant rather than a
         *  [Config] field, for the reason [ALONGSIDE_MAX_RANGE_Y_M] gives. */
        internal const val TRACKING_RANGE_M = 40

        /** Decoder's ±lateralPos 1.0 maps to this metres each side.
         *  Kept in sync with [RadarV2Decoder.LATERAL_FULL_M]. */
        private const val LATERAL_FULL_M = RadarV2Decoder.LATERAL_FULL_M

        /** Take the clearance only from frames at or inside this rangeY: the
         *  vehicle alongside the rider, or about to be. A vehicle following
         *  directly behind reads as laterally centred, so a reading taken from
         *  back there is a clearance that never happened. Arming is
         *  deliberately NOT windowed, so a pass is recognised from far back and
         *  only measured up close; `a track that arms far away still reports
         *  its alongside pass` pins that. Metres, on the same rounded scale as
         *  [Vehicle.distanceM].
         *
         *  A constant rather than a [Config] field: nothing configures the
         *  window, so a configurable would be a second copy of the value with
         *  nothing comparing the two. */
        internal const val ALONGSIDE_MAX_RANGE_Y_M = 2

        /** Fastest closing speed (m/s) that counts as one: the bottom of
         *  [AlertDecider]'s ceiling range. Always on, unlike that ceiling, since
         *  it drops a number, never a cue; a rider whose ceiling is higher can
         *  be warned about a closer this leaves out. It bounds arming as well
         *  as the event's figure, and the ride's peak closing speed and its
         *  clearance read it too
         *  (`a reading above 35 metres per second is not the approach peak`,
         *  `a reading above 35 metres per second does not arm a track`,
         *  `thePeakBoundIsThirtyFiveMetresPerSecondInclusive`,
         *  `aReadingAtThePhantomBoundIsStillAnApproach`). */
        internal const val PEAK_CLOSING_MAX_MS = AlertDecider.MIN_CLOSING_CEILING_MS

        /** Where the vehicle drew level: the upper median by clearance of a
         *  track's alongside frames, `sorted[n / 2]`, or null for none. A
         *  minimum would report a car still in line behind the rider, a moment
         *  before it pulled out (`a car still in line behind is not where it
         *  passed`). The upper median is what was measured on the ride corpus;
         *  the lower median and the mean are different rules
         *  (`with an even number of frames the wider middle one counts`).
         *  Shared with the ride's tightest clearance, which applies it to the
         *  frames it keeps. */
        internal fun <T> passPoint(alongside: List<T>, clearanceOf: (T) -> Float): T? = alongside.sortedBy(clearanceOf).getOrNull(alongside.size / 2)

        /** Below this a raw lateral reading IS the radar's zero, not a small
         *  measurement: the channel is quantised well above it, so nothing
         *  real lands here. Float comparison, not equality.
         *
         *  Not private: the ride record's tightest-clearance figure has to
         *  reject the same readings, and two copies of the rule would drift. */
        internal const val RAW_LATERAL_EPSILON = 0.001f
    }
}
