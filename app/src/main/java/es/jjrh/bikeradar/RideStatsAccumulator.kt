// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure-JVM accumulator for per-ride statistics published to Home Assistant.
 *
 * Two ingest paths:
 *   - [observeFrame] runs on every decoded RadarState snapshot (multi-Hz).
 *     Updates per-frame integrals (distance, exposure, peak closing) and the
 *     per-track readings behind the tightest clearance.
 *   - [observeClosePass] runs once per close-pass event the detector emits.
 *     Updates per-event counters and the tightest-pass record.
 *
 * Threading: not thread-safe. Call from a single coroutine context (the
 * radar collect block in [OverlayPipeline]).
 */
class RideStatsAccumulator(
    /** Wall clock (currentTimeMillis), for [rideStartedAtMs] which is exported
     *  as an ISO instant / persisted to ride history. */
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    /** Monotonic clock (elapsedRealtime), for the distance/exposure dt and ride
     *  duration so a wall-clock jump can't inflate or drop the ride distance. */
    private val monoMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** The first frame, or construction until one arrives: the service can
     *  build this hours before the radar is switched on
     *  (`theRideIsDatedFromItsFirstFrame`, `theHourlyRateCountsFromTheFirstFrame`).
     *  Volatile for [snapshot]'s off-thread readers. */
    @Volatile var rideStartedAtMs: Long = nowMsProvider()
        private set

    @Volatile private var rideStartedAtMono: Long = monoMsProvider()

    /** (tid, bornAtMs) of each vehicle detected behind within range, passed or
     *  not: the radar reuses ids within a ride
     *  (`aSecondCarOnAReusedIdIsASecondOvertake`). A car re-born on its
     *  old id after a prune or a reconnect counts again, deliberately
     *  (`aCarRebornOnItsOldIdAfterAPruneCountsAgain`); a legacy track carries
     *  bornAtMs 0 and counts once per id (`aLegacyStreamCountsOneOvertakePerId`). */
    private val seenTracks = HashSet<Pair<Int, Long>>()

    /** Vehicles in range on a frame close-pass counting ran on, keyed as
     *  [seenTracks]: what the conversion rate divides by, so vehicles detected
     *  before counting was switched on do not dilute it
     *  (`theRateDividesByOvertakesSeenWhileCounting`). */
    private val countedTracks = HashSet<Pair<Int, Long>>()

    /** The [seenTracks] taken as having passed the rider, keyed the same way,
     *  so a vehicle is one pass however often it is judged. Approximate by
     *  design: see [markPassedAhead] and [markPassedFading]. */
    private val passedTracks = HashSet<Pair<Int, Long>>()

    /** Whether a frame from a source with a lateral channel has arrived. The
     *  passed figure reads the lateral offset of a vehicle seen ahead, so a
     *  ride with no such frame has none to give, as with the close passes
     *  (`aRangeOnlyRideHasNoPassedFigure`). */
    private var passedFigureKnown = false

    // Per-frame state. Nullable for measurement-class fields that have no
    // sensible "no data" zero value: a ride with no observed vehicles should
    // surface "unknown" in HA, not a misleading 0 m / 0 km/h.
    private var peakClosingKmh: Int? = null
    private var minLateralM: Float? = null
    private var distanceRiddenM: Double = 0.0
    private var exposureMs: Long = 0L
    private var lastFrameMs: Long? = null
    private var lastBikeSpeedMs: Float? = null

    /** tid -> the open track on it. A track ends when its id leaves the frame,
     *  when it is seen ahead, or when a new birth takes the id
     *  (`aReusedTrackIdDoesNotInheritTheLastCarsApproach`). */
    private val openTracks = HashMap<Int, OpenTrack>()

    /** A ghost filter of its own, fed the frames and the turn state the alert
     *  decider is given, so the passed count refuses the tracks the beeps
     *  refuse as a pass. It runs on every frame, paused or not; the decider's
     *  gate is reset while paused. */
    private val bornCloseGate = BornCloseGate()

    private class OpenTrack(val id: Int, val bornAtMs: Long) {
        /** The fastest closing (m/s) seen behind the rider (not
         *  [Vehicle.isBehind], which means ahead). */
        var peakClosingMs = 0f

        /** [Vehicle.heardAtMs] of the last frame on which the radar heard the
         *  track, and the readings taken on it. The decoder keeps a track it no
         *  longer hears for up to [RadarV2Decoder.STALE_PARKED_MS], re-judging
         *  its dock on every frame, so the frame it leaves the list on is not
         *  the one to judge it by (`VehiclesPassedDecoderTest`). A reading change
         *  on a frame with the same stamp is a second report
         *  (`aSecondReportInTheSameMillisecondIsHeard`); the dock is not a
         *  reading (`aDockOnlyTheHoldShowsIsNotReadAtAConstantRiderSpeed`). The
         *  latch rests on the decoder replaying a held track's distance and
         *  speed unchanged (`aHeldTrackCarriesItsLastDistanceAndSpeedUnchanged`).
         *  Null before the track's first frame (`aTrackHeardAtTimeZeroIsHeard`). */
        var lastHeardAtMs: Long? = null
        var lastDistanceM = 0
        var lastSpeedMs = 0f
        var riderMovingWhenHeard = false
        var dockedWhenHeard = false

        /** Whether the ghost filter held it back on its last frame. */
        var lastGhost = false

        /** Clearances (m) that qualified for the ride's figure. */
        val alongside = ArrayList<Float>()
    }

    // Per-event state.
    private var closePassCount: Int = 0
    private var grazingCount: Int = 0
    private var hgvClosePassCount: Int = 0
    private val closingSpeedSamples = ArrayList<Int>()
    private var tightestPass: TightestPass? = null
    private var closePassesCounted = false

    // Alarm-cue tally. Counts only the close-pass ALARM cues that actually
    // sounded (beep + urgent); informational cues (clear, radar_drop,
    // radar_reconnect) are excluded so the derived per-km / per-hour rates
    // measure alarm burden.
    private var alarmCueCount: Int = 0

    // Generation counter for change detection. Incremented whenever any
    // observable state changes; the publish loop compares against
    // [lastPublishedGeneration] and skips if equal.
    private var generation: Long = 0L
    private var lastPublishedGeneration: Long = -1L

    /**
     * Ingest a RadarState snapshot. The snapshot's `bikeSpeedMs` integrates
     * into [distanceRiddenM] over the elapsed (monotonic) interval since the
     * previous frame. Vehicles in the snapshot update the peak closing speed,
     * the per-track readings behind the tightest clearance, the set of
     * vehicles detected behind and the approximate passed count.
     * [closingFloorMs] is the
     * rider's close-pass closing floor, the value [ClosePassDetector] reads.
     * [counting] says close-pass counting runs on this frame, which puts its
     * in-range cars in the conversion rate's denominator. [turnState] is the
     * one the alert decider would be given, which the ghost filter reads.
     */
    fun observeFrame(
        state: RadarState,
        closingFloorMs: Float,
        counting: Boolean,
        turnState: TurnStateDecider.State,
    ) {
        val nowMs = monoMsProvider()
        val prev = lastFrameMs
        lastFrameMs = nowMs
        if (prev == null) {
            rideStartedAtMs = nowMsProvider()
            rideStartedAtMono = nowMs
            generation++
        }

        // A gap wider than the link's own stall threshold means frames stopped
        // arriving: a mid-ride radar drop, a tunnel, a dead radar battery.
        // Nothing was observed across it, so nothing is integrated. Carrying
        // the last known speed over the gap instead would invent distance -
        // ten minutes at 5.5 m/s is 3.3 km of ride history and Home Assistant
        // data that no sensor ever saw, and it deflates alerts-per-km by the
        // same factor. Under-reporting an unobserved stretch is the honest
        // failure.
        //
        // Scope of that, precisely, because one field deliberately differs:
        // distanceRiddenM and exposureMs count observed time only, while
        // rideDurationMs is wall-elapsed and spans the gap on purpose - it
        // answers "how long was the ride", which a dropout does not shorten.
        // So after a long dropout alertsPerKm and alertsPerHourOfRide rest on
        // different denominators, by intent. Do not "fix" the rate to exposure
        // time: per hour OF RIDE is what alarm fatigue is measured against.
        if (prev != null && (nowMs - prev) <= MAX_FRAME_GAP_MS) {
            val dtMs = (nowMs - prev).coerceAtLeast(0L)
            // Use the bike speed that applied DURING the just-ended interval —
            // i.e. the value carried from the previous frame, before this
            // frame's update overwrites it.
            val speedForInterval = lastBikeSpeedMs
            if (speedForInterval != null && speedForInterval > 0f) {
                distanceRiddenM += speedForInterval.toDouble() * dtMs / 1000.0
                generation++
            }
            // Exposure: any tracked vehicle (excluding alongside-stationary)
            // means the rider is in traffic for this interval.
            //
            // A lateral-unknown frame counts. The sentinel says one CHANNEL is
            // stale, not that the frame is bad: the vehicle's presence and its
            // distance are measured on it either way, and presence is the only
            // thing this gate asks about. Skipping it stopped "in traffic" time
            // accruing while a car the radar was actively reporting sat behind
            // the rider, which the decoder's longer sentinel runs made common.
            val anyTraffic = state.vehicles.any { v ->
                !v.isBehind &&
                    !v.isAlongsideStationary &&
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
        val riderMoving = (lastBikeSpeedMs ?: 0f) >= RIDER_MOVING_MS
        // The decider's speed bar for a moving rider, not [RIDER_MOVING_MS]: a
        // car fading beside a rider rolling at walking pace went by as much as
        // one beside a rider at speed
        // (`aVehicleLostBesideARiderAtWalkingPaceIsPassed`). Radar speed only:
        // the eBike's stop signal is deliberately not read here (see
        // [AlertDecider.STATIONARY_MS_THRESHOLD]).
        val riderMovingForPass = (lastBikeSpeedMs ?: 0f) > AlertDecider.STATIONARY_MS_THRESHOLD
        if (state.source.hasLateral && !passedFigureKnown) {
            passedFigureKnown = true
            generation++
        }
        bornCloseGate.update(state.vehicles, turnState, nowMs)

        // Per-frame extrema and the vehicles-detected and passed sets.
        for (v in state.vehicles) {
            if (v.isBehind) {
                markPassedAhead(v)
                // A different birth under the id is another car that has left
                // the list; the same birth is this car, seen ahead.
                openTracks.remove(v.id)?.let { endTrack(it, faded = it.bornAtMs != v.bornAtMs) }
                continue
            }
            val open = openTracks[v.id]
            val track = if (open?.bornAtMs == v.bornAtMs) {
                open
            } else {
                open?.let { endTrack(it, faded = true) }
                OpenTrack(v.id, v.bornAtMs).also { openTracks[v.id] = it }
            }
            if (v.heardAtMs != track.lastHeardAtMs || v.distanceM != track.lastDistanceM || v.speedMs != track.lastSpeedMs) {
                track.lastHeardAtMs = v.heardAtMs
                track.lastDistanceM = v.distanceM
                track.lastSpeedMs = v.speedMs
                track.riderMovingWhenHeard = riderMovingForPass
                track.dockedWhenHeard = v.isAlongsideStationary
            }
            // On every frame, as the decider reads it
            // (`aVehicleFirstSeenCloseIsJudgedAsAGhostOnEveryFrame`).
            track.lastGhost = bornCloseGate.isGated(v)
            // Before the range check: an approach is often seen only far back
            // (`aFastApproachFromBeyondTrackingRangeStillCounts`). A reading
            // closing faster than the bound does not count as one
            // (`aReadingAboveTheBoundIsNotAnApproach`).
            if (-v.speedMs <= ClosePassDetector.PEAK_CLOSING_MAX_MS) {
                track.peakClosingMs = maxOf(track.peakClosingMs, -v.speedMs)
            }
            if (v.distanceM !in 0..MAX_TRACK_DISTANCE_M) continue

            val car = v.id to v.bornAtMs
            if (seenTracks.add(car)) generation++
            if (counting && countedTracks.add(car)) generation++

            if (v.speedMs < 0 && -v.speedMs <= ClosePassDetector.PEAK_CLOSING_MAX_MS) {
                val closingKmh = (-v.speedMs * MS_TO_KMH).toInt()
                val current = peakClosingKmh
                if (current == null || closingKmh > current) {
                    peakClosingKmh = closingKmh
                    generation++
                }
            }

            // The ONLY figure the lateral sentinel is allowed to veto, and the
            // reason the skip is here rather than at the top of the loop: a
            // held-over offset is not a measurement, so recording it would
            // write a clearance into the ride record and publish it to Home
            // Assistant as though the radar had seen it. Null is the honest
            // value for "never measured"; the count, the closing peak and the
            // exposure gate above all read the frame regardless, because they
            // rest on channels that ARE measured.
            //
            // Source capability guards the same figure for a different reason:
            // on a stream with no lateral channel every lateralPos is 0f, which
            // would record a 0.0 m clearance on every track.
            //
            // The window and the raw-zero rule are [ClosePassDetector]'s
            // geometry rules, read from its constants. The last two keep it to
            // overtakes of a moving rider. A track that never closed at the
            // floor is following, not passing, and reads near dead centre; a
            // car pacing alongside that never closed at the floor is left out
            // with it, deliberately (`aSlowFollowerIsNotAClearance`). Closing
            // is judged over the track's life so far, not on the frame,
            // since a passing car often reads near zero closing as it draws
            // level (`anOvertakeThatSlowsAlongsideStillCounts`). A car drawing
            // level with a stopped rider is caught by the rider gate
            // (`aCarPassingAStoppedRiderIsNotAClearance`).
            if (!v.isAlongsideStationary &&
                state.source.hasLateral &&
                !v.lateralUnknown &&
                v.distanceM <= ClosePassDetector.ALONGSIDE_MAX_RANGE_Y_M &&
                abs(v.rangeXmRaw) >= ClosePassDetector.RAW_LATERAL_EPSILON &&
                track.peakClosingMs >= closingFloorMs &&
                riderMoving
            ) {
                track.alongside.add(abs(v.lateralPos) * RadarV2Decoder.LATERAL_FULL_M)
            }
        }
        val present = state.vehicles.mapTo(HashSet()) { it.id }
        openTracks.keys.filter { it !in present }.forEach { tid ->
            openTracks.remove(tid)?.let { endTrack(it, faded = true) }
        }
        if (counting && !closePassesCounted) {
            closePassesCounted = true
            generation++
        }
    }

    /** Forget every open track without counting it, as a clearance or as a
     *  pass. Called as each radar connection starts, when the close-pass
     *  detector starts empty too: a car the link dropped was last seen
     *  wherever it stopped, often still in line behind
     *  (`aCarCutOffByARadarDropIsNotCounted`,
     *  `aVehicleCutOffByARadarDropIsNotPassed`). */
    fun dropOpenTracks() {
        openTracks.clear()
    }

    /** A vehicle seen ahead of the rider has gone by. Counted when it was
     *  detected behind first and its raw lateral offset is within
     *  [AlertDecider.RX_ABSURD_M], the decider's own bar for off the road: a
     *  target further out is on another street, not a vehicle that passed
     *  (`aVehicleSeenAheadButFarToTheSideIsNotPassed`). A [Vehicle.lateralUnknown]
     *  frame is within the bar, as in the decider, which fails open there too.
     *  Approximate by design: a track can fall back behind after being seen
     *  ahead, and it is still one pass (`aVehicleSeenAheadTwiceIsPassedOnce`). */
    private fun markPassedAhead(ahead: Vehicle) {
        val car = ahead.id to ahead.bornAtMs
        val onTheRoad = ahead.lateralUnknown || abs(ahead.rangeXmRaw) <= AlertDecider.RX_ABSURD_M
        if (car in seenTracks && onTheRoad && passedTracks.add(car)) generation++
    }

    /** A track that left the list went by when the radar last heard it
     *  within [AlertDecider.PASS_FADE_MAX_M] along the road while the rider
     *  was moving: the radar often drops a car as it draws level instead of
     *  reporting it ahead, the allowance the beeps use
     *  (`aVehicleLostBesideAMovingRiderIsPassed`). Further back it may have
     *  turned off or dropped out while still behind, and at a stop it may be
     *  a car behind going quiet, so neither counts
     *  (`aVehicleLostFurtherBackIsNotPassed`, `aVehicleLostBesideAStoppedRiderIsNotPassed`).
     *  Nor does one docked when last heard, queued or parked traffic the rider
     *  crawled past (`aDockedVehicleLostBesideAMovingRiderIsNotPassed`); one
     *  moving away when last heard, as the rider rode past it
     *  (`aVehicleMovingAwayWhenLostIsNotPassed`); or one the ghost filter held
     *  back on its last frame, which the beeps refuse as a pass too
     *  (`aGhostLostBesideAMovingRiderIsNotPassed`). */
    private fun markPassedFading(track: OpenTrack) {
        val car = track.id to track.bornAtMs
        if (car in seenTracks &&
            track.riderMovingWhenHeard &&
            track.lastDistanceM <= AlertDecider.PASS_FADE_MAX_M &&
            !track.dockedWhenHeard &&
            track.lastSpeedMs <= 0f &&
            !track.lastGhost &&
            passedTracks.add(car)
        ) {
            generation++
        }
    }

    /** The track's clearance is its [ClosePassDetector.passPoint], as the
     *  close-pass event's is, taken when the track ends. A car still alongside
     *  when the ride ends is never counted, as the detector never emits it
     *  (`aPassStillUnderwayIsNotYetCounted`). [faded] says the track left the
     *  list rather than being seen ahead, so [markPassedFading] may judge it. */
    private fun endTrack(track: OpenTrack, faded: Boolean) {
        if (faded) markPassedFading(track)
        val clearanceM = ClosePassDetector.passPoint(track.alongside) { it } ?: return
        val current = minLateralM
        if (current == null || clearanceM < current) {
            minLateralM = clearanceM
            generation++
        }
    }

    /**
     * Ingest a close-pass event. Updates discrete counters, the
     * closing-speed sample for p90, and the tightest-pass record if
     * this event has lower clearance than the current minimum. The event's
     * clearance also feeds [minLateralM], so the figure is never wider than
     * the ride's tightest pass: the detector checks rider speed only when it
     * arms, this figure on every frame
     * (`theFigureIsNeverWiderThanTheTightestPass`).
     */
    fun observeClosePass(event: ClosePassDetector.Event) {
        closePassCount++
        if (event.severity == ClosePassDetector.Severity.GRAZING) grazingCount++
        if (event.vehicleSize == VehicleSize.TRUCK) hgvClosePassCount++
        closingSpeedSamples.add(event.closingSpeedKmh)
        minLateralM = minOf(minLateralM ?: event.clearanceM, event.clearanceM)

        val current = tightestPass
        if (current == null || event.clearanceM < current.clearanceM) {
            tightestPass = TightestPass(
                tsMs = event.timestampMs,
                side = event.side,
                vehicleSize = event.vehicleSize,
                clearanceM = event.clearanceM,
                closingKmh = event.closingSpeedKmh,
                rangeYM = event.rangeYM,
            )
        }
        closePassesCounted = true
        generation++
    }

    /**
     * Ingest one sounded alert cue, tagged by [AlertBeeper]'s `onCue`
     * chokepoint. Only the close-pass alarm cues ([AlertBeeper] tags `beep…`
     * and `urgent`) increment the tally; the informational cues (`clear`,
     * `radar_drop`, `radar_reconnect`) are ignored - as are failed plays
     * (`cue_failed …` prefix), which never sounded and so must not count as
     * heard alerts.
     */
    fun observeAlertCue(tag: String) {
        if (tag.startsWith("beep") || tag == "urgent") {
            alarmCueCount++
            generation++
        }
    }

    /** True when state has changed since the last [markPublished] call. */
    fun changedSinceLast(): Boolean = generation != lastPublishedGeneration

    fun markPublished() {
        lastPublishedGeneration = generation
    }

    /** Snapshot the current values. Reads off the Main writer context (the
     *  HA publish loop, the ride checkpoint) can race an in-flight ingest;
     *  those callers treat a torn read as best-effort and must not let it
     *  propagate. */
    fun snapshot(): RideStatsSnapshot {
        val totalOvertakes = seenTracks.size
        val countedOvertakes = countedTracks.size
        val conversionRatePct = if (countedOvertakes > 0) {
            closePassCount.toFloat() / countedOvertakes.toFloat() * 100f
        } else {
            0f
        }
        val p90 = if (closingSpeedSamples.isEmpty()) null else percentile(closingSpeedSamples, 0.9)
        // Alarm-fatigue rates. Null (rendered Unknown in HA) rather than a
        // misleading number when the denominator is non-positive: a ride with
        // no distance ridden yet, or a zero-length ride.
        val distanceKm = distanceRiddenM / 1000.0
        val rideDurationMs = (monoMsProvider() - rideStartedAtMono).coerceAtLeast(0L)
        val alertsPerKm = if (distanceKm > 0.0) (alarmCueCount / distanceKm).toFloat() else null
        val alertsPerHour = if (rideDurationMs > 0L) {
            (alarmCueCount.toDouble() / (rideDurationMs / 3_600_000.0)).toFloat()
        } else {
            null
        }
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
            alertsPerKm = alertsPerKm,
            alertsPerHourOfRide = alertsPerHour,
            closePassesCounted = closePassesCounted,
            vehiclesPassed = if (passedFigureKnown) passedTracks.size else null,
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
        private const val MAX_TRACK_DISTANCE_M = ClosePassDetector.TRACKING_RANGE_M

        /** Widest frame interval still treated as continuous observation.
         *  Reads the radar link's own stall threshold rather than copying it:
         *  that value is what tears the GATT down when frames stop, so the two
         *  must move together and a second literal could drift. */
        private val MAX_FRAME_GAP_MS = RadarLinkController.V2_FRAME_STALL_MS
        private const val MS_TO_KMH = 3.6f

        /** Rider speed (m/s) from which the clearance counts; below it the rider
         *  is treated as stopped, so a car drawing level is not an overtake
         *  (`theRiderGateIsTwoMetresPerSecondInclusive`). Deliberately not the
         *  detector's rider floor, `Prefs.closePassRiderSpeedFloorKmh`, whose
         *  default is high enough to leave out a car passing a rider who is
         *  slowing down. */
        private const val RIDER_MOVING_MS = 2f
    }
}

data class RideStatsSnapshot(
    /** Vehicles detected behind within range, passed or not. The HA key
     *  `overtakes_total` and the history key `overtakes` keep this name, so a
     *  rider's automations and stored rides still read it. */
    val overtakesTotal: Int,
    val closePassCount: Int,
    val grazingCount: Int,
    val hgvClosePassCount: Int,
    /** Null until the first approaching vehicle is observed. Readings closing
     *  faster than 35 m/s (126 km/h) are left out. */
    val peakClosingKmh: Int?,
    /** Null until the first close-pass event fires. */
    val closingSpeedP90Kmh: Int?,
    /**
     * The tightest clearance of an overtaking vehicle where it drew level with
     * a moving rider, counted once its track ends, or the tightest close pass
     * if that is tighter. Null until there is one.
     */
    val minLateralClearanceM: Float?,
    val distanceRiddenKm: Float,
    val exposureSeconds: Long,
    /** Close passes per hundred vehicles detected behind while close-pass
     *  counting ran, which can be fewer than [overtakesTotal]. Per vehicle
     *  detected, not per vehicle passed. */
    val closePassConversionRatePct: Float,
    val tightestPass: TightestPass?,
    val rideStartedAtMs: Long,
    /** Alarm cues (close-pass beep + urgent) per km ridden. Null when no
     *  distance has accumulated (avoids a div-by-zero / absurd rate). */
    val alertsPerKm: Float?,
    /** Alarm cues per hour of ride elapsed time. Null for a zero-length ride. */
    val alertsPerHourOfRide: Float?,
    /** False when close-pass counting never ran in this ride (counting off, a
     *  range-only radar, or no rider speed), so [closePassCount] is not a
     *  measurement. */
    val closePassesCounted: Boolean = false,
    /** An approximate count of the [overtakesTotal] that went by the rider:
     *  seen ahead of the bike within [AlertDecider.RX_ABSURD_M] to the side,
     *  or lost within [AlertDecider.PASS_FADE_MAX_M] while the rider moved
     *  (radar speed above [AlertDecider.STATIONARY_MS_THRESHOLD]), judged on
     *  the frame the radar last heard it. The lost path alone leaves out a
     *  track that was docked ([Vehicle.isAlongsideStationary]) or moving away
     *  when last heard, or a ghost ([BornCloseGate]) on its last frame. Null
     *  when no frame had a lateral channel (a range-only radar), which is not
     *  a zero. */
    val vehiclesPassed: Int? = null,
)

data class TightestPass(
    val tsMs: Long,
    val side: ClosePassDetector.Side,
    val vehicleSize: VehicleSize,
    val clearanceM: Float,
    val closingKmh: Int,
    val rangeYM: Float,
)
