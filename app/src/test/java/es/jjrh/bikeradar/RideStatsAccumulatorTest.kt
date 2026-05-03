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
        fun advance(ms: Long) { now += ms }
    }

    private fun acc(clock: FakeClock = FakeClock()) = RideStatsAccumulator(clock.provider)

    private fun veh(
        id: Int,
        distanceM: Int = 20,
        speedMs: Int = -8,
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
        a.observeFrame(radarState(listOf(
            veh(1, isBehind = true),
            veh(2, lateralUnknown = true),
            veh(3),
        )))
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
        a.observeFrame(radarState(listOf(veh(1, speedMs = -5))))   // 18 km/h
        a.observeFrame(radarState(listOf(veh(2, speedMs = -12))))  // 43 km/h
        a.observeFrame(radarState(listOf(veh(3, speedMs = -7))))   // 25 km/h
        assertEquals(43, a.snapshot().peakClosingKmh)
    }

    @Test
    fun peakClosingIgnoresStationaryAndReceding() {
        val a = acc()
        a.observeFrame(radarState(listOf(
            veh(1, speedMs = 0),    // stationary
            veh(2, speedMs = 5),    // receding
            veh(3, speedMs = -3),   // 11 km/h closing
        )))
        assertEquals(10, a.snapshot().peakClosingKmh) // -3 * 3.6 = 10.8 → 10 (toInt truncates)
    }

    // ── min lateral clearance ────────────────────────────────────────────────

    @Test
    fun minLateralTrackedAcrossFrames() {
        val a = acc()
        a.observeFrame(radarState(listOf(veh(1, lateralPos = 0.5f))))   // 1.5 m
        a.observeFrame(radarState(listOf(veh(1, lateralPos = 0.3f))))   // 0.9 m
        a.observeFrame(radarState(listOf(veh(1, lateralPos = 0.4f))))   // 1.2 m
        val s = a.snapshot()
        assertTrue("expected ~0.9 m, got ${s.minLateralClearanceM}",
            s.minLateralClearanceM in 0.85f..0.95f)
    }

    @Test
    fun minLateralIsZeroWhenNoVehiclesObserved() {
        val a = acc()
        a.observeFrame(radarState(emptyList()))
        assertEquals(0f, a.snapshot().minLateralClearanceM, 0.001f)
    }

    @Test
    fun minLateralSkipsAlongsideStationary() {
        val a = acc()
        a.observeFrame(radarState(listOf(
            veh(1, lateralPos = 0.05f, isAlongsideStationary = true), // 0.15 m
            veh(2, lateralPos = 0.4f),                                // 1.2 m
        )))
        val s = a.snapshot()
        assertTrue("alongside-stationary must not pull min; got ${s.minLateralClearanceM}",
            s.minLateralClearanceM in 1.15f..1.25f)
    }

    // ── distance ridden ──────────────────────────────────────────────────────

    @Test
    fun distanceIntegratesBikeSpeedOverTime() {
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(bikeSpeedMs = 5f))      // first frame, no prev → no integration
        clock.advance(1_000L)                              // 1 s elapsed
        a.observeFrame(radarState(bikeSpeedMs = 5f))      // 5 m/s * 1 s = 5 m
        clock.advance(2_000L)                              // 2 s elapsed
        a.observeFrame(radarState(bikeSpeedMs = 10f))     // ... carries 10 m/s for next interval
        val s = a.snapshot()
        // First interval: 5 m. Second integrated using last known speed at frame 2 (5 m/s) for 2s = 10 m.
        // Total: 15 m = 0.015 km.
        assertTrue("expected ~0.015 km, got ${s.distanceRiddenKm}",
            s.distanceRiddenKm in 0.013f..0.017f)
    }

    @Test
    fun distanceCarriesLastKnownBikeSpeedAcrossNullFrames() {
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(bikeSpeedMs = 8f))
        clock.advance(1_000L)
        a.observeFrame(radarState(bikeSpeedMs = null))   // no speed update; previous still applies
        // 8 m/s * 1 s = 8 m
        assertTrue(a.snapshot().distanceRiddenKm in 0.007f..0.009f)
    }

    // ── exposure ─────────────────────────────────────────────────────────────

    @Test
    fun exposureCountsOnlyIntervalsWithTraffic() {
        val clock = FakeClock(start = 0L)
        val a = acc(clock)
        a.observeFrame(radarState(emptyList()))             // no prev → no interval
        clock.advance(2_000L)
        a.observeFrame(radarState(listOf(veh(1))))          // traffic now; 2 s exposure
        clock.advance(3_000L)
        a.observeFrame(radarState(emptyList()))              // no traffic; this 3s interval has traffic per the START frame
        clock.advance(1_000L)
        a.observeFrame(radarState(emptyList()))              // no traffic this interval either
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
        a.observeClosePass(closeEvent(size = VehicleSize.BIKE))
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
        assertTrue("p90 of 20..70 should sit near 60-65, got $p90", p90 in 60..65)
    }

    @Test
    fun p90IsZeroWithNoSamples() {
        val a = acc()
        assertEquals(0, a.snapshot().closingSpeedP90Kmh)
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
}
