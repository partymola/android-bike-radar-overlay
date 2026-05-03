// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end pipeline replay: drives [RadarV2Decoder] -> [ClosePassDetector]
 * -> [RideStatsAccumulator] from the captured 30s window, then asserts the
 * accumulator's final snapshot is internally consistent and inside plausible
 * ranges. Catches integration regressions across the post-decode pipeline
 * that per-component tests miss — e.g. a refactor that changes the
 * RadarState schema in a way the detector keeps accepting but the
 * accumulator silently drops.
 *
 * Fixture is the same 30s real capture used by [RadarV2DecoderReplayTest].
 */
class PipelineReplayTest {

    private data class Frame(val relMs: Long, val bytes: ByteArray)

    private fun loadFixture(): List<Frame> {
        val stream = javaClass.classLoader!!.getResourceAsStream("replay-fixture.txt")
            ?: error("replay-fixture.txt missing from test resources")
        return stream.bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split(' ', limit = 2)
                    Frame(parts[0].toLong(), parts[1].hexToBytes())
                }
                .toList()
        }
    }

    @Test
    fun fullPipelineProducesConsistentSnapshot() {
        val frames = loadFixture()
        assertTrue("fixture must contain frames", frames.isNotEmpty())

        var clock = 0L
        val decoder = RadarV2Decoder(nowMs = { clock })
        val detector = ClosePassDetector()
        val stats = RideStatsAccumulator(nowMsProvider = { clock })

        // Permissive config: the fixture window is 30 s of real traffic;
        // the default emitMinRangeX of 1.0 m can be too strict on a short
        // sample. Loosen so the detector is exercised as well as gated.
        val cfg = ClosePassDetector.Config(
            enabled = true,
            riderSpeedFloorMs = 0f,
            closingSpeedFloorMs = 1,
            armRangeXUrbanM = 3.0f,
            armRangeXRuralM = 3.0f,
            emitMinRangeXM = 3.0f,
            minFramesToArm = 1,
        )

        var snapshotCount = 0
        var totalEvents = 0

        for (frame in frames) {
            clock = frame.relMs
            val state = decoder.feed(frame.bytes) ?: continue
            snapshotCount++

            stats.observeFrame(state)
            val events = detector.decide(
                vehicles = state.vehicles,
                bikeSpeedMs = state.bikeSpeedMs ?: 5f,
                nowMs = clock,
                config = cfg,
            )
            for (e in events) {
                totalEvents++
                stats.observeClosePass(e)
            }
        }

        assertTrue("decoder must produce snapshots from the fixture", snapshotCount > 0)

        val snap = stats.snapshot()

        // Consistency invariants — these should hold regardless of how the
        // tuning numbers shift, because they are properties of the
        // accumulator's bookkeeping, not of the fixture content.
        assertTrue(
            "closePass count must equal events fed in (got ${snap.closePassCount}, fed $totalEvents)",
            snap.closePassCount == totalEvents,
        )
        assertTrue(
            "grazing count cannot exceed total close passes",
            snap.grazingCount <= snap.closePassCount,
        )
        assertTrue(
            "HGV count cannot exceed total close passes",
            snap.hgvClosePassCount <= snap.closePassCount,
        )
        assertTrue(
            "conversion-rate must be 0 when there are no overtakes",
            snap.overtakesTotal > 0 || snap.closePassConversionRatePct == 0f,
        )
        if (snap.overtakesTotal > 0) {
            assertTrue(
                "conversion rate must be in [0,100], got ${snap.closePassConversionRatePct}",
                snap.closePassConversionRatePct in 0f..100f,
            )
        }

        // Plausibility — ranges that should hold for a 30 s urban capture.
        assertTrue(
            "ride start must be the early clock value, got ${snap.rideStartedAtMs}",
            snap.rideStartedAtMs in 0L..1000L,
        )
        assertTrue("exposure cannot be negative", snap.exposureSeconds >= 0)
        assertTrue("distance ridden cannot be negative", snap.distanceRiddenKm >= 0f)
        assertTrue(
            "distance ridden must be plausible for a 30 s clip, got ${snap.distanceRiddenKm} km",
            snap.distanceRiddenKm < 0.6f,
        )
        if (snap.peakClosingKmh != null) {
            assertTrue(
                "peak closing must be plausible, got ${snap.peakClosingKmh}",
                snap.peakClosingKmh in 0..200,
            )
        }
        if (snap.minLateralClearanceM != null) {
            assertTrue(
                "min lateral clearance must be non-negative, got ${snap.minLateralClearanceM}",
                snap.minLateralClearanceM >= 0f,
            )
        }
    }

    @Test
    fun pipelineWithDetectorDisabledLeavesEventCountersAtZero() {
        // Strict invariant: the master on/off flag really stops events
        // from reaching the accumulator.
        val frames = loadFixture()
        var clock = 0L
        val decoder = RadarV2Decoder(nowMs = { clock })
        val detector = ClosePassDetector()
        val stats = RideStatsAccumulator(nowMsProvider = { clock })
        val disabled = ClosePassDetector.Config(enabled = false)

        for (frame in frames) {
            clock = frame.relMs
            val state = decoder.feed(frame.bytes) ?: continue
            stats.observeFrame(state)
            val events = detector.decide(
                vehicles = state.vehicles,
                bikeSpeedMs = state.bikeSpeedMs ?: 5f,
                nowMs = clock,
                config = disabled,
            )
            for (e in events) stats.observeClosePass(e)
        }

        val snap = stats.snapshot()
        assertEquals(0, snap.closePassCount)
        assertEquals(0, snap.grazingCount)
        assertEquals(0, snap.hgvClosePassCount)
        assertEquals(0f, snap.closePassConversionRatePct, 0f)
        // Per-frame stats (overtakesTotal, distanceRiddenKm, etc.) still
        // accumulate on the frame path even when the detector is disabled.
    }
}
