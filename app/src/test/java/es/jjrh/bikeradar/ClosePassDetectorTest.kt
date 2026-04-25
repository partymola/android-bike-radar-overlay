// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosePassDetectorTest {

    private val baseConfig = ClosePassDetector.Config(enabled = true)

    private fun veh(
        id: Int = 1,
        distanceM: Int = 20,
        speedMs: Int = -8,          // 8 m/s approaching
        size: VehicleSize = VehicleSize.CAR,
        lateralPos: Float = 0.2f,    // 0.6 m right
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

    private fun drive(
        detector: ClosePassDetector,
        frames: List<Pair<List<Vehicle>, Long>>,
        bikeSpeedKmh: Int? = 22,
        config: ClosePassDetector.Config = baseConfig,
    ): List<ClosePassDetector.Event> {
        val out = mutableListOf<ClosePassDetector.Event>()
        for ((vehicles, ts) in frames) {
            out.addAll(detector.decide(vehicles, bikeSpeedKmh, ts, config))
        }
        return out
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test fun `fires once for a classic close overtake`() {
        val d = ClosePassDetector()
        val approach = listOf(
            veh(distanceM = 30, lateralPos = 0.5f) to 0L,    // 1.5 m right
            veh(distanceM = 20, lateralPos = 0.35f) to 100L, // 1.05 m
            veh(distanceM = 15, lateralPos = 0.25f) to 200L, // 0.75 m
            veh(distanceM = 10, lateralPos = 0.18f) to 300L, // 0.54 m right
            veh(distanceM = 5, lateralPos = 0.15f) to 400L,  // 0.45 m (min)
            veh(distanceM = 2, lateralPos = 0.2f) to 500L,   // pulling away
            veh(distanceM = 0, lateralPos = 0.25f, isBehind = true) to 600L,
        )
        val events = drive(d, approach)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(ClosePassDetector.Severity.GRAZING, e.severity)
        assertTrue("min should be the closest frame", e.minRangeXM < 0.5f)
        assertEquals(ClosePassDetector.Side.RIGHT, e.side)
    }

    // ── gate: rider speed floor ──────────────────────────────────────────────

    @Test fun `does not fire when rider is stationary`() {
        val d = ClosePassDetector()
        val frames = (0..5).map { i ->
            listOf(veh(distanceM = 20 - i * 2, lateralPos = 0.2f)) to i * 100L
        }
        // bikeSpeedKmh = 0 — below default floor of 15
        val events = drive(d, frames, bikeSpeedKmh = 0)
        assertTrue(events.isEmpty())
    }

    @Test fun `does not fire when bike speed is unknown`() {
        val d = ClosePassDetector()
        val frames = (0..5).map { i ->
            listOf(veh(distanceM = 20 - i * 2, lateralPos = 0.2f)) to i * 100L
        }
        val events = drive(d, frames, bikeSpeedKmh = null)
        assertTrue(events.isEmpty())
    }

    // ── gate: closing speed floor ────────────────────────────────────────────

    @Test fun `does not fire for lane-matched traffic at low closing speed`() {
        val d = ClosePassDetector()
        // Only closing at 2 m/s — below the 6 m/s default floor.
        val frames = listOf(
            veh(distanceM = 20, lateralPos = 0.3f, speedMs = -2) to 0L,
            veh(distanceM = 18, lateralPos = 0.3f, speedMs = -2) to 100L,
            veh(distanceM = 16, lateralPos = 0.28f, speedMs = -2) to 200L,
            veh(distanceM = 10, lateralPos = 0.2f, speedMs = -2) to 300L,
            veh(distanceM = 5, lateralPos = 0.15f, speedMs = -2) to 400L,
            veh(distanceM = 0, lateralPos = 0.2f, speedMs = -2, isBehind = true) to 500L,
        )
        val events = drive(d, frames)
        assertTrue(events.isEmpty())
    }

    // ── gate: vehicle class ──────────────────────────────────────────────────

    @Test fun `does not fire for a BIKE class target`() {
        val d = ClosePassDetector()
        val frames = listOf(
            veh(distanceM = 20, lateralPos = 0.25f, size = VehicleSize.BIKE) to 0L,
            veh(distanceM = 15, lateralPos = 0.18f, size = VehicleSize.BIKE) to 100L,
            veh(distanceM = 10, lateralPos = 0.15f, size = VehicleSize.BIKE) to 200L,
            veh(distanceM = 5, lateralPos = 0.15f, size = VehicleSize.BIKE) to 300L,
            veh(distanceM = 0, lateralPos = 0.2f, size = VehicleSize.BIKE, isBehind = true) to 400L,
        )
        val events = drive(d, frames)
        assertTrue(events.isEmpty())
    }

    // ── gate: minimum frames to arm ──────────────────────────────────────────

    @Test fun `does not arm on a single-frame glitch`() {
        val d = ClosePassDetector()
        val frames = listOf(
            listOf(veh(distanceM = 5, lateralPos = 0.1f)) to 0L,
            emptyList<Vehicle>() to 100L,
        )
        val events = drive(d, frames)
        assertTrue(events.isEmpty())
    }

    // ── strict emit threshold ────────────────────────────────────────────────

    @Test fun `does not fire when min rangeX stays above emit threshold`() {
        val d = ClosePassDetector()
        // Armed because 1.4 < 1.5 urban threshold, but min never
        // drops below 1.0 m emit cutoff.
        val frames = listOf(
            veh(distanceM = 25, lateralPos = 0.47f) to 0L,   // 1.41 m
            veh(distanceM = 20, lateralPos = 0.45f) to 100L, // 1.35 m
            veh(distanceM = 15, lateralPos = 0.42f) to 200L, // 1.26 m
            veh(distanceM = 10, lateralPos = 0.4f) to 300L,  // 1.2 m (min)
            veh(distanceM = 5, lateralPos = 0.45f) to 400L,  // receding laterally
            veh(distanceM = 0, lateralPos = 0.5f, isBehind = true) to 500L,
        )
        val events = drive(d, frames)
        assertTrue("1.2 m min should not emit; above 1.0 m cutoff", events.isEmpty())
    }

    // ── min tracking ─────────────────────────────────────────────────────────

    @Test fun `emitted min is the tightest frame not the first under threshold`() {
        val d = ClosePassDetector()
        val frames = listOf(
            veh(distanceM = 25, lateralPos = 0.48f) to 0L,   // 1.44 m  (arms under urban 1.5)
            veh(distanceM = 20, lateralPos = 0.4f) to 100L,  // 1.2 m
            veh(distanceM = 15, lateralPos = 0.28f) to 200L, // 0.84 m  <-- not yet min
            veh(distanceM = 10, lateralPos = 0.2f) to 300L,  // 0.6 m  <-- tightest
            veh(distanceM = 5, lateralPos = 0.22f) to 400L,  // 0.66 m
            veh(distanceM = 0, lateralPos = 0.3f, isBehind = true) to 500L,
        )
        val events = drive(d, frames)
        assertEquals(1, events.size)
        val e = events[0]
        assertTrue("min should be ~0.6, got ${e.minRangeXM}", e.minRangeXM in 0.58f..0.62f)
        assertEquals(ClosePassDetector.Severity.VERY_CLOSE, e.severity)
    }

    // ── cooldown ─────────────────────────────────────────────────────────────

    @Test fun `respects global cooldown between emits`() {
        val d = ClosePassDetector()
        val longCooldown = baseConfig.copy(cooldownMs = 5_000L)
        // First overtake: tid 1, min 0.3 m, emits at t=500.
        val firstFrames = (0..5).map { i ->
            val lp = listOf(0.48f, 0.3f, 0.18f, 0.1f, 0.12f, 0.2f)[i]
            val isBehind = i == 5
            listOf(veh(id = 1, distanceM = 25 - i * 5, lateralPos = lp, isBehind = isBehind)) to (i * 100L)
        }
        val firstEvents = drive(d, firstFrames, config = longCooldown)
        assertEquals(1, firstEvents.size)

        // Second overtake immediately after (tid 2): within cooldown.
        val secondFrames = (0..5).map { i ->
            val lp = listOf(0.48f, 0.3f, 0.18f, 0.1f, 0.12f, 0.2f)[i]
            val isBehind = i == 5
            listOf(veh(id = 2, distanceM = 25 - i * 5, lateralPos = lp, isBehind = isBehind)) to (600L + i * 100L)
        }
        val secondEvents = drive(d, secondFrames, config = longCooldown)
        assertTrue("cooldown should suppress the second emit", secondEvents.isEmpty())
    }

    // ── alongside-stationary skip ────────────────────────────────────────────

    @Test fun `real overtake ending in mutual junction stop does not emit`() {
        // tid 1 is a genuine overtake whose closest frame stays above
        // the 1.0 m emit threshold. The rider then brakes to a junction
        // stop alongside the just-overtaken vehicle; both end up
        // near-stationary at the junction so the decoder flags the
        // continuing track as isAlongsideStationary. Without the
        // alongside-stationary skip, the close alongside frame's
        // |rx|=0.1 m would falsely pull minRangeX below the emit
        // threshold and the terminate path would fire a bogus GRAZING
        // event. With the skip, min stays at the genuine overtake's
        // 1.14 m and the event is correctly suppressed.
        val d = ClosePassDetector()
        val frames = listOf(
            // Real overtake phase: arms at 1.41 m, drives to 1.14 m min.
            listOf(veh(distanceM = 25, lateralPos = 0.47f)) to 0L,
            listOf(veh(distanceM = 20, lateralPos = 0.45f)) to 100L,
            listOf(veh(distanceM = 15, lateralPos = 0.4f)) to 200L,
            listOf(veh(distanceM = 10, lateralPos = 0.38f)) to 300L,
            listOf(veh(distanceM = 5, lateralPos = 0.4f)) to 400L,
            // Alongside phase: same tid, now stationary alongside the
            // rider. This frame would falsely pull min to 0.1 m without
            // the skip.
            listOf(
                veh(
                    distanceM = 5,
                    speedMs = 0,
                    size = VehicleSize.TRUCK,
                    lateralPos = 0.033f,  // 0.1 m right
                    isAlongsideStationary = true,
                ),
            ) to 500L,
            // Track drops; terminate path runs.
            emptyList<Vehicle>() to 600L,
        )
        val events = drive(d, frames)
        assertTrue("alongside frame must not pull min below the emit threshold", events.isEmpty())
    }

    // ── lateral-unknown skip ─────────────────────────────────────────────────

    @Test fun `lateral-unknown frames do not pollute min tracking`() {
        // Track armed on a real overtake whose closest frame stayed above
        // the 1.0 m emit threshold. A subsequent far-range frame fires the
        // decoder's lateralUnknown flag (radar's rxBits=0 sentinel). The
        // detector must skip that frame; otherwise its raw |rx|=0 would
        // pull min-rangeX to zero and the terminate path would emit a
        // bogus GRAZING event.
        val d = ClosePassDetector()
        val frames = listOf(
            // Real overtake phase: arms at 1.41 m, drives to 1.14 m min.
            listOf(veh(distanceM = 25, lateralPos = 0.47f)) to 0L,
            listOf(veh(distanceM = 20, lateralPos = 0.45f)) to 100L,
            listOf(veh(distanceM = 15, lateralPos = 0.4f)) to 200L,
            listOf(veh(distanceM = 10, lateralPos = 0.38f)) to 300L,
            // Far-range frame with lateralUnknown=true and a raw |rx|=0
            // that would falsely update min if the detector didn't skip it.
            listOf(
                veh(
                    distanceM = 25,
                    lateralPos = 0f,
                    lateralUnknown = true,
                ),
            ) to 400L,
            // Track drops; terminate path runs.
            emptyList<Vehicle>() to 500L,
        )
        val events = drive(d, frames)
        assertTrue("lateral-unknown frame must not pollute min tracking", events.isEmpty())
    }

    // ── disabled ─────────────────────────────────────────────────────────────

    @Test fun `disabled config never emits`() {
        val d = ClosePassDetector()
        val disabled = baseConfig.copy(enabled = false)
        val frames = listOf(
            listOf(veh(distanceM = 20, lateralPos = 0.2f)) to 0L,
            listOf(veh(distanceM = 10, lateralPos = 0.1f)) to 100L,
            listOf(veh(distanceM = 5, lateralPos = 0.08f)) to 200L,
            listOf(veh(distanceM = 0, lateralPos = 0.15f, isBehind = true)) to 300L,
        )
        val events = drive(d, frames, config = disabled)
        assertTrue(events.isEmpty())
    }
}

/** Helper: single-vehicle + ts pairs. Forwards with the default
 *  baseConfig (enabled, default thresholds) since cooldown + disabled
 *  tests use the multi-vehicle overload with explicit config. */
private fun ClosePassDetectorTest.drive(
    d: ClosePassDetector,
    frame: List<Pair<Vehicle, Long>>,
    bikeSpeedKmh: Int? = 22,
): List<ClosePassDetector.Event> {
    val cfg = ClosePassDetector.Config(enabled = true)
    val out = mutableListOf<ClosePassDetector.Event>()
    for ((v, ts) in frame) {
        out.addAll(d.decide(listOf(v), bikeSpeedKmh, ts, cfg))
    }
    return out
}
