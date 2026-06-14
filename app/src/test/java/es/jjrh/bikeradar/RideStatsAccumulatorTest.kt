// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStatsAccumulatorTest {

    private class FakeClock(start: Long = 1_000L) {
        var now: Long = start
        val provider: () -> Long = { now }
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun acc(clock: FakeClock = FakeClock()) = RideStatsAccumulator(clock.provider, clock.provider)

    private fun veh(
        id: Int,
        distanceM: Int = 20,
        speedMs: Float = -8f,
        lateralPos: Float = 0.5f,
        size: VehicleSize = VehicleSize.CAR,
        isBehind: Boolean = false,
        isAlongsideStationary: Boolean = false,
        lateralUnknown: Boolean = false,
    ) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        size = size,
        lateralPos = lateralPos,
        isBehind = isBehind,
        isAlongsideStationary = isAlongsideStationary,
        lateralUnknown = lateralUnknown,
    )

    private fun radarState(
        vehicles: List<Vehicle> = emptyList(),
        bikeSpeedMs: Float? = null,
    ) = RadarState(vehicles = vehicles, source = DataSource.V2, bikeSpeedMs = bikeSpeedMs)

    private fun closeEvent(
        ts: Long = 1_000L,
        clearance: Float = 0.7f,
        closingKmh: Int = 30,
        side: ClosePassDetector.Side = ClosePassDetector.Side.RIGHT,
        size: VehicleSize = VehicleSize.CAR,
        severity: ClosePassDetector.Severity = ClosePassDetector.Severity.VERY_CLOSE,
    ) = ClosePassDetector.Event(
        timestampMs = ts,
        minRangeXM = clearance,
        side = side,
        rangeYAtMinM = 5f,
        closingSpeedKmh = closingKmh,
        riderSpeedKmh = 25,
        vehicleSize = size,
        thresholdArmedM = 1.5f,
        severity = severity,
    )

    // ── overtakes_total ──────────────────────────────────────────────────────

    @Test
    fun overtakesTotalDedupsByTrackId() {
        val a = acc()
        a.observeFrame(radarState(listOf(veh(1), veh(2))))
        a.observeFrame(radarState(listOf(veh(1), veh(2), veh(3))))
        a.observeFrame(radarState(listOf(veh(2), veh(3))))
        assertEquals(3, a.snapshot().overtakesTotal)
    }

    @Test
    fun overtakesTotalSkipsBehindAndLateralUnknownTracks() {
        val a = acc()
        a.observeFrame(
            radarState(
                listOf(
                    veh(1, isBehind = true),
                    veh(2, lateralUnknown = true),
                    veh(3),
                ),
            ),
        )
        assertEquals(1, a.snapshot().overtakesTotal)
    }

    @Test
    fun overtakesTotalSkipsTracksBeyond40m() {
        val a = acc()
        a.observeFrame(radarState(listOf(veh(1, distanceM = 50), veh(2, distanceM = 30))))
        assertEquals(1, a.snapshot().overtakesTotal)
    }

    // ── peak closing speed ───────────────────────────────────────────────────

    @Test
    fun peakClosingTakesMaxAcrossFrames() {
        val a = acc()
        a.observeFrame(radarState(listOf(veh(1, speedMs = -5f)))) // 18 km/h
        a.observeFrame(radarState(listOf(veh(2, speedMs = -12f)))) // 43 km/h
        a.observeFrame(radarState(listOf(veh(3, speedMs = -7f)))) // 25 km/h
        assertEquals(43, a.snapshot().peakClosingKmh)
    }

    @Test
    fun peakClosingIgnoresStationaryAndReceding() {
        val a = acc()
        a.observeFrame(
            radarState(
                listOf(
                    veh(1, speedMs = 0f), // stationary
                    veh(2, speedMs = 5f), // receding
                    veh(3, speedMs = -3f), // 11 km/h closing
                ),
            ),
        )
        assertEquals(10, a.snapshot().peakClosingKmh) // -3 * 3.6 = 10.8 → 10 (toInt truncates)
    }

    // ── min lateral clearance ────────────────────────────────────────────────

    @Test
    fun minLateralTrackedAcrossFrames() {
        val a = acc()
        a.observeFrame(radarState(listOf(veh(1, lateralPos = 0.5f)))) // 1.5 m
        a.observeFrame(radarState(listOf(veh(1, lateralPos = 0.3f)))) // 0.9 m
        a.observeFrame(radarState(listOf(veh(1, lateralPos = 0.4f)))) // 1.2 m
        val m = a.snapshot().minLateralClearanceM
        assertNotNull(m)
        assertTrue("expected ~0.9 m, got $m", m!! in 0.85f..0.95f)
    }

    @Test
    fun minLateralIsNullWhenNoVehiclesObserved() {
        val a = acc()
        a.observeFrame(radarState(emptyList()))
        assertNull(a.snapshot().minLateralClearanceM)
    }

    @Test
    fun peakClosingIsNullWhenNoApproachingVehicleObserved() {
        val a = acc()
        a.observeFrame(radarState(emptyList()))
        a.observeFrame(radarState(listOf(veh(1, speedMs = 0f)))) // stationary
        a.observeFrame(radarState(listOf(veh(1, speedMs = 5f)))) // receding
        assertNull(a.snapshot().peakClosingKmh)
    }

    @Test
    fun minLateralSkipsAlongsideStationary() {
        val a = acc()
        a.observeFrame(
            radarState(
                listOf(
                    veh(1, lateralPos = 0.05f, isAlongsideStationary = true), // 0.15 m
                    veh(2, lateralPos = 0.4f), // 1.2 m
                ),
            ),
        )
        val m = a.snapshot().minLateralClearanceM
        assertNotNull(m)
        assertTrue("alongside-stationary must not pull min; got $m", m!! in 1.15f..1.25f)
    }

    // ── distance ridden ──────────────────────────────────────────────────────

    @Test
    fun distanceIntegratesBikeSpeedOverTime() {
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(bikeSpeedMs = 5f)) // first frame, no prev → no integration
        clock.advance(1_000L) // 1 s elapsed
        a.observeFrame(radarState(bikeSpeedMs = 5f)) // 5 m/s * 1 s = 5 m
        clock.advance(2_000L) // 2 s elapsed
        a.observeFrame(radarState(bikeSpeedMs = 10f)) // ... carries 10 m/s for next interval
        val s = a.snapshot()
        // First interval: 5 m. Second integrated using last known speed at frame 2 (5 m/s) for 2s = 10 m.
        // Total: 15 m = 0.015 km.
        assertTrue(
            "expected ~0.015 km, got ${s.distanceRiddenKm}",
            s.distanceRiddenKm in 0.013f..0.017f,
        )
    }

    @Test
    fun distanceCarriesLastKnownBikeSpeedAcrossNullFrames() {
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(bikeSpeedMs = 8f))
        clock.advance(1_000L)
        a.observeFrame(radarState(bikeSpeedMs = null)) // no speed update; previous still applies
        // 8 m/s * 1 s = 8 m
        assertTrue(a.snapshot().distanceRiddenKm in 0.007f..0.009f)
    }

    // ── exposure ─────────────────────────────────────────────────────────────

    @Test
    fun exposureCountsOnlyIntervalsWithTraffic() {
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(emptyList())) // no prev → no interval
        clock.advance(2_000L)
        a.observeFrame(radarState(listOf(veh(1)))) // traffic now; 2 s exposure
        clock.advance(3_000L)
        a.observeFrame(radarState(emptyList())) // no traffic; this 3s interval has traffic per the START frame
        clock.advance(1_000L)
        a.observeFrame(radarState(emptyList())) // no traffic this interval either
        // Exposure semantics: interval counts iff the FRAME being processed has any traffic.
        // Interval 2 (2s): empty→veh1, frame has traffic → 2s.
        // Interval 3 (3s): veh1→empty, frame has no traffic → 0s.
        // Interval 4 (1s): empty→empty → 0s.
        assertEquals(2L, a.snapshot().exposureSeconds)
    }

    // ── close-pass counters ──────────────────────────────────────────────────

    @Test
    fun closePassCountIncrementsPerEvent() {
        val a = acc()
        a.observeClosePass(closeEvent())
        a.observeClosePass(closeEvent())
        a.observeClosePass(closeEvent())
        assertEquals(3, a.snapshot().closePassCount)
    }

    @Test
    fun grazingCountTracksGrazingSubset() {
        val a = acc()
        a.observeClosePass(closeEvent(severity = ClosePassDetector.Severity.VERY_CLOSE))
        a.observeClosePass(closeEvent(severity = ClosePassDetector.Severity.GRAZING))
        a.observeClosePass(closeEvent(severity = ClosePassDetector.Severity.GRAZING))
        val s = a.snapshot()
        assertEquals(3, s.closePassCount)
        assertEquals(2, s.grazingCount)
    }

    @Test
    fun hgvCountTracksTruckSubset() {
        val a = acc()
        a.observeClosePass(closeEvent(size = VehicleSize.CAR))
        a.observeClosePass(closeEvent(size = VehicleSize.TRUCK))
        a.observeClosePass(closeEvent(size = VehicleSize.TRUCK))
        a.observeClosePass(closeEvent(size = VehicleSize.CAR))
        val s = a.snapshot()
        assertEquals(4, s.closePassCount)
        assertEquals(2, s.hgvClosePassCount)
    }

    // ── tightest pass ────────────────────────────────────────────────────────

    @Test
    fun tightestPassUpdatesOnNewMinimum() {
        val a = acc()
        a.observeClosePass(closeEvent(clearance = 0.8f, side = ClosePassDetector.Side.RIGHT))
        a.observeClosePass(closeEvent(clearance = 0.4f, side = ClosePassDetector.Side.LEFT))
        a.observeClosePass(closeEvent(clearance = 0.6f, side = ClosePassDetector.Side.RIGHT))
        val tp = a.snapshot().tightestPass
        assertNotNull(tp)
        assertEquals(0.4f, tp!!.clearanceM, 0.001f)
        assertEquals(ClosePassDetector.Side.LEFT, tp.side)
    }

    @Test
    fun tightestPassNullBeforeAnyEvent() {
        val a = acc()
        a.observeFrame(radarState(listOf(veh(1))))
        assertNull(a.snapshot().tightestPass)
    }

    // ── conversion rate ──────────────────────────────────────────────────────

    @Test
    fun conversionRateIsZeroWithNoOvertakes() {
        val a = acc()
        assertEquals(0f, a.snapshot().closePassConversionRatePct, 0.001f)
    }

    @Test
    fun conversionRateIsCloseOverOvertakesAsPct() {
        val a = acc()
        a.observeFrame(radarState(listOf(veh(1), veh(2), veh(3), veh(4))))
        a.observeClosePass(closeEvent())
        assertEquals(25f, a.snapshot().closePassConversionRatePct, 0.001f)
    }

    // ── p90 closing speed ────────────────────────────────────────────────────

    @Test
    fun p90PicksHighEndOfDistribution() {
        val a = acc()
        for (kmh in listOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 70)) {
            a.observeClosePass(closeEvent(closingKmh = kmh))
        }
        val p90 = a.snapshot().closingSpeedP90Kmh
        assertNotNull(p90)
        assertTrue("p90 of 20..70 should sit near 60-65, got $p90", p90!! in 60..65)
    }

    @Test
    fun p90IsNullWithNoSamples() {
        val a = acc()
        assertNull(a.snapshot().closingSpeedP90Kmh)
    }

    // ── change detection ─────────────────────────────────────────────────────

    @Test
    fun changedSinceLastFlipsOnObserveFrame() {
        val a = acc()
        a.markPublished()
        assertEquals(false, a.changedSinceLast())
        a.observeFrame(radarState(listOf(veh(1))))
        assertEquals(true, a.changedSinceLast())
    }

    @Test
    fun changedSinceLastFlipsOnClosePass() {
        val a = acc()
        a.markPublished()
        assertEquals(false, a.changedSinceLast())
        a.observeClosePass(closeEvent())
        assertEquals(true, a.changedSinceLast())
    }

    @Test
    fun markPublishedClearsChangedFlag() {
        val a = acc()
        a.observeFrame(radarState(listOf(veh(1))))
        assertEquals(true, a.changedSinceLast())
        a.markPublished()
        assertEquals(false, a.changedSinceLast())
    }

    // ── ride start ───────────────────────────────────────────────────────────

    @Test
    fun rideStartedAtMsCapturedOnConstruction() {
        val clock = FakeClock(start = 12_345L)
        val a = acc(clock)
        clock.advance(60_000L)
        // Ride start should not move forward.
        assertEquals(12_345L, a.snapshot().rideStartedAtMs)
    }

    // ── alarm-fatigue rates ───────────────────────────────────────────────────

    /** Accrue 12 km of distance: 10 m/s integrated over a 1200 s interval. */
    private fun rideTwelveKm(a: RideStatsAccumulator, clock: FakeClock) {
        a.observeFrame(radarState(bikeSpeedMs = 10f)) // seed speed, no integration yet
        clock.advance(1_200_000L) // 1200 s
        a.observeFrame(radarState(bikeSpeedMs = 10f)) // 10 m/s * 1200 s = 12 000 m
    }

    @Test
    fun freshRideHasNullAlarmRates() {
        val s = acc().snapshot()
        assertNull(s.alertsPerKm)
        assertNull(s.alertsPerHourOfRide)
    }

    @Test
    fun rideStartTracksWallWhileDistanceTracksMonotonic() {
        // The dual-clock split: rideStartedAtMs is the wall epoch
        // (exported/persisted), while distance + duration integrate on the
        // monotonic clock so a mid-ride wall jump can't inflate or drop them.
        val wall = FakeClock(start = 1_700_000_000_000L)
        val mono = FakeClock(start = 10_000L)
        val a = RideStatsAccumulator(wall.provider, mono.provider)
        assertEquals(1_700_000_000_000L, a.rideStartedAtMs)
        a.observeFrame(radarState(bikeSpeedMs = 10f))
        wall.advance(-3_600_000L) // wall clock steps back 1 h mid-ride
        mono.advance(1_000L) // 1 s of real elapsed time
        a.observeFrame(radarState(bikeSpeedMs = 10f))
        val s = a.snapshot()
        // rideStartedAtMs is the wall value captured at construction, immune to
        // the later wall jump; distance is the 1 s monotonic dt at 10 m/s.
        assertEquals(1_700_000_000_000L, s.rideStartedAtMs)
        assertEquals(0.01f, s.distanceRiddenKm, 1e-4f)
    }

    @Test
    fun thirtyMinuteRideWithSixAlarmsYieldsTwelvePerHourAndHalfPerKm() {
        val clock = FakeClock()
        val a = acc(clock)
        rideTwelveKm(a, clock)
        repeat(3) { a.observeAlertCue("beep count=3") }
        repeat(3) { a.observeAlertCue("urgent") }
        clock.advance(600_000L) // total ride elapsed (monotonic) = 30 min
        val s = a.snapshot()
        assertEquals(0.5f, s.alertsPerKm!!, 1e-4f)
        assertEquals(12.0f, s.alertsPerHourOfRide!!, 1e-4f)
    }

    @Test
    fun oneCueWithZeroDistanceSuppressesPerKmButKeepsPerHour() {
        val clock = FakeClock()
        val a = acc(clock)
        a.observeAlertCue("beep count=2")
        clock.advance(60_000L) // 1 min elapsed, no distance ridden
        val s = a.snapshot()
        assertNull("div-by-zero distance must not publish a per-km rate", s.alertsPerKm)
        assertEquals(60.0f, s.alertsPerHourOfRide!!, 1e-4f)
    }

    @Test
    fun informationalCuesAreExcludedFromTheAlarmTally() {
        val clock = FakeClock()
        val a = acc(clock)
        rideTwelveKm(a, clock)
        a.observeAlertCue("clear")
        a.observeAlertCue("critical_battery")
        a.observeAlertCue("radar_drop")
        a.observeAlertCue("radar_reconnect")
        val s = a.snapshot()
        // Distance accrued (so not null) but no alarm cue counted -> rate 0.
        assertEquals(0.0f, s.alertsPerKm!!, 1e-4f)
        assertEquals(0.0f, s.alertsPerHourOfRide!!, 1e-4f)
    }

    // ── distance: carried-speed positivity gate ───────────────────────────────

    // covers RideStatsAccumulator.kt:78
    @Test
    fun negativeCarriedSpeedAccruesNoDistance() {
        // A spurious negative bike speed must never subtract distance. The
        // first frame seeds lastBikeSpeedMs = -5, so the next interval's
        // speedForInterval is -5f and the `> 0f` gate rejects it.
        // Catches gate-removal (no gate -> -5 m/s * dt subtracts distance) and
        // a flip to `< 0f` (which would admit the negative speed and subtract).
        // Note: `>= 0f` is an EQUIVALENT mutant here, not a killed one - it
        // differs from `> 0f` only at speed == 0, where the increment is 0
        // either way, so no input distinguishes them.
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(bikeSpeedMs = -5f)) // seed carried speed = -5
        clock.advance(1_000L)
        a.observeFrame(radarState(bikeSpeedMs = -5f))
        assertEquals(0.0f, a.snapshot().distanceRiddenKm, 1e-6f)
    }

    // ── exposure filters ──────────────────────────────────────────────────────

    // covers RideStatsAccumulator.kt:87
    @Test
    fun exposureSkipsAlongsideStationaryFrames() {
        // The interval is attributed to the current frame's traffic. A frame
        // whose only vehicle is alongside-stationary does not count as
        // in-traffic, so exposure stays 0. Kills a mutant that drops the
        // `!v.isAlongsideStationary` term from anyTraffic.
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(emptyList())) // seed prev
        clock.advance(2_000L)
        a.observeFrame(radarState(listOf(veh(1, isAlongsideStationary = true))))
        assertEquals(0L, a.snapshot().exposureSeconds)
    }

    // covers RideStatsAccumulator.kt:88
    @Test
    fun exposureSkipsLateralUnknownFrames() {
        // A lateral-unknown sentinel frame is not real traffic. Kills a mutant
        // that drops the `!v.lateralUnknown` term from anyTraffic.
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(emptyList())) // seed prev
        clock.advance(2_000L)
        a.observeFrame(radarState(listOf(veh(1, lateralUnknown = true))))
        assertEquals(0L, a.snapshot().exposureSeconds)
    }

    // covers RideStatsAccumulator.kt:89
    @Test
    fun exposureSkipsOutOfRangeFrames() {
        // A vehicle beyond MAX_TRACK_DISTANCE_M (40 m) does not put the rider
        // in traffic for exposure purposes. Kills a mutant that drops or widens
        // the `v.distanceM in 0..MAX_TRACK_DISTANCE_M` bound in anyTraffic.
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(emptyList())) // seed prev
        clock.advance(2_000L)
        a.observeFrame(radarState(listOf(veh(1, distanceM = 50))))
        assertEquals(0L, a.snapshot().exposureSeconds)
    }

    // ── extrema: out-of-range skip ────────────────────────────────────────────

    // covers RideStatsAccumulator.kt:105
    @Test
    fun outOfRangeVehicleSkippedForPeakAndMinExtrema() {
        // A vehicle beyond MAX_TRACK_DISTANCE_M is `continue`d before the
        // peak-closing and min-lateral updates, so both extrema stay null even
        // though this vehicle is fast-closing and very tight laterally.
        // Kills a mutant that drops the `distanceM !in 0..MAX_TRACK_DISTANCE_M`
        // continue (peak/min would pick up the out-of-range reading).
        val a = acc()
        a.observeFrame(
            radarState(listOf(veh(1, distanceM = 50, speedMs = -20f, lateralPos = 0.05f))),
        )
        val s = a.snapshot()
        assertNull("out-of-range vehicle must not set peak closing", s.peakClosingKmh)
        assertNull("out-of-range vehicle must not set min lateral", s.minLateralClearanceM)
    }

    // ── p90: exact interpolated values ────────────────────────────────────────

    // covers RideStatsAccumulator.kt:213
    @Test
    fun p90InterpolatesExactlyBetweenTwoSamples() {
        // [10, 20]: rank = 0.9 * (2 - 1) = 0.9, lo = 0, hi = 1, frac = 0.9.
        // value = (10 + 0.9 * (20 - 10)).toInt() = 19.0.toInt() = 19.
        // Asserting the exact value (not a range) so a dropped interpolation
        // term (e.g. returning sorted[lo] = 10) fails.
        val a = acc()
        a.observeClosePass(closeEvent(closingKmh = 10))
        a.observeClosePass(closeEvent(closingKmh = 20))
        assertEquals(19, a.snapshot().closingSpeedP90Kmh)
    }

    // covers RideStatsAccumulator.kt:213
    @Test
    fun p90OfSingleSampleIsThatSample() {
        // [42]: size 1, rank = 0.9 * 0 = 0, lo = 0, hi = min(1, 0) = 0,
        // frac = 0. value = (42 + 0 * 0).toInt() = 42. Exercises the hi-clamp
        // (min(lo + 1, size - 1)) that prevents an index overflow on one sample.
        val a = acc()
        a.observeClosePass(closeEvent(closingKmh = 42))
        assertEquals(42, a.snapshot().closingSpeedP90Kmh)
    }
}
