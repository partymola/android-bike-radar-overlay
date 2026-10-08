// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosePassDetectorTest {

    private val baseConfig = ClosePassDetector.Config(enabled = true)

    private fun veh(
        id: Int = 1,
        distanceM: Int = 20,
        speedMs: Float = -8f, // 8 m/s approaching
        size: VehicleSize = VehicleSize.CAR,
        lateralPos: Float = 0.2f, // 0.6 m right
        isBehind: Boolean = false,
        isAlongsideStationary: Boolean = false,
        lateralUnknown: Boolean = false,
        /** The sensor's own reading, which for a measured frame agrees with
         *  [lateralPos]. Defaulted rather than left at the data class's 0f:
         *  every frame in this file would otherwise carry a zero, which the
         *  skip that reads it treats as no usable clearance, blanking the whole
         *  suite while looking correct. Pass 0f to mean an unresolved frame. */
        rangeXmRaw: Float = lateralPos * RadarV2Decoder.LATERAL_FULL_M,
        bornAtMs: Long = 0L,
    ) = Vehicle(
        id = id,
        distanceM = distanceM,
        speedMs = speedMs,
        size = size,
        lateralPos = lateralPos,
        isBehind = isBehind,
        isAlongsideStationary = isAlongsideStationary,
        lateralUnknown = lateralUnknown,
        rangeXmRaw = rangeXmRaw,
        bornAtMs = bornAtMs,
    )

    private fun drive(
        detector: ClosePassDetector,
        frames: List<Pair<List<Vehicle>, Long>>,
        bikeSpeedMs: Float? = 7f,
        config: ClosePassDetector.Config = baseConfig,
    ): List<ClosePassDetector.Event> {
        val out = mutableListOf<ClosePassDetector.Event>()
        for ((vehicles, ts) in frames) {
            out.addAll(detector.decide(vehicles, bikeSpeedMs, ts, config))
        }
        return out
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test fun `fires once for a classic close overtake`() {
        val d = ClosePassDetector()
        val approach = listOf(
            veh(distanceM = 30, lateralPos = 0.5f) to 0L, // 1.5 m right
            veh(distanceM = 20, lateralPos = 0.35f) to 100L, // 1.05 m
            veh(distanceM = 15, lateralPos = 0.25f) to 200L, // 0.75 m
            veh(distanceM = 10, lateralPos = 0.18f) to 300L, // 0.54 m right
            veh(distanceM = 2, lateralPos = 0.15f) to 400L, // 0.45 m alongside
            veh(distanceM = 1, lateralPos = 0.16f) to 500L, // 0.48 m alongside
            // Tight AND already past: dropping the isBehind skip adds it to the
            // alongside frames and moves the median to 0.45 m.
            veh(distanceM = 0, lateralPos = 0.02f, isBehind = true) to 600L,
        )
        val events = drive(d, approach)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(ClosePassDetector.Severity.GRAZING, e.severity)
        assertEquals(0.48f, e.clearanceM, 0.005f)
        assertEquals(ClosePassDetector.Side.RIGHT, e.side)
    }

    // ── gate: rider speed floor ──────────────────────────────────────────────

    @Test fun `fires for stationary-rider close pass at junction`() {
        // A junction close-pass happens while the rider is
        // decelerating into a waiting line - a real safety
        // event that should be logged. The previous rider-speed
        // floor excluded these from the HA log.
        // Floor is now 0; the other gates (closing speed,
        // lateral arm threshold, frames-to-arm) filter the noise.
        val d = ClosePassDetector()
        // Approach + pass + termination (track drops out → emit).
        val frames = (0..6).map { i ->
            listOf(veh(distanceM = 20 - i * 3, lateralPos = 0.2f, speedMs = -8f)) to i * 100L
        } + listOf<Pair<List<Vehicle>, Long>>(emptyList<Vehicle>() to 700L)
        val events = drive(d, frames, bikeSpeedMs = 0f)
        assertTrue("stationary-rider close pass must emit", events.isNotEmpty())
    }

    @Test fun `does not fire when bike speed is unknown`() {
        val d = ClosePassDetector()
        val frames = (0..9).map { i ->
            listOf(veh(distanceM = 20 - i * 2, lateralPos = 0.2f)) to i * 100L
        } + listOf<Pair<List<Vehicle>, Long>>(emptyList<Vehicle>() to 1_000L)
        val events = drive(d, frames, bikeSpeedMs = null)
        assertTrue(events.isEmpty())
    }

    // ── gate: closing speed floor ────────────────────────────────────────────

    @Test fun `does not fire for lane-matched traffic at low closing speed`() {
        val d = ClosePassDetector()
        // Only closing at 2 m/s - below the 6 m/s default floor.
        val frames = listOf(
            veh(distanceM = 20, lateralPos = 0.3f, speedMs = -2f) to 0L,
            veh(distanceM = 18, lateralPos = 0.3f, speedMs = -2f) to 100L,
            veh(distanceM = 16, lateralPos = 0.28f, speedMs = -2f) to 200L,
            veh(distanceM = 10, lateralPos = 0.2f, speedMs = -2f) to 300L,
            veh(distanceM = 2, lateralPos = 0.15f, speedMs = -2f) to 400L, // 0.45 m alongside
            veh(distanceM = 0, lateralPos = 0.2f, speedMs = -2f, isBehind = true) to 500L,
        )
        val events = drive(d, frames)
        assertTrue(events.isEmpty())
    }

    // ── gate: minimum frames to arm ──────────────────────────────────────────

    @Test fun `does not arm on a single-frame glitch`() {
        val d = ClosePassDetector()
        val frames = listOf(
            listOf(veh(distanceM = 2, lateralPos = 0.1f)) to 0L,
            emptyList<Vehicle>() to 100L,
        )
        val events = drive(d, frames)
        assertTrue(events.isEmpty())
    }

    // ── strict emit threshold ────────────────────────────────────────────────

    @Test fun `does not fire when the clearance stays above emit threshold`() {
        val d = ClosePassDetector()
        // Armed because 1.4 < 1.5 urban threshold, but the clearance is
        // above the 1.0 m emit cutoff.
        val frames = listOf(
            veh(distanceM = 25, lateralPos = 0.47f) to 0L, // 1.41 m
            veh(distanceM = 20, lateralPos = 0.45f) to 100L, // 1.35 m
            veh(distanceM = 15, lateralPos = 0.42f) to 200L, // 1.26 m
            veh(distanceM = 2, lateralPos = 0.4f) to 300L, // 1.2 m alongside
            veh(distanceM = 1, lateralPos = 0.45f) to 400L, // receding laterally
            veh(distanceM = 0, lateralPos = 0.5f, isBehind = true) to 500L,
        )
        val events = drive(d, frames)
        assertTrue("a 1.35 m clearance should not emit; above 1.0 m cutoff", events.isEmpty())
    }

    // ── cooldown ─────────────────────────────────────────────────────────────

    @Test fun `respects global cooldown between emits`() {
        val d = ClosePassDetector()
        val longCooldown = baseConfig.copy(cooldownMs = 5_000L)
        // First overtake: tid 1, min 0.3 m, emits at t=500.
        val firstFrames = (0..5).map { i ->
            val lp = listOf(0.48f, 0.3f, 0.18f, 0.1f, 0.12f, 0.2f)[i]
            val isBehind = i == 5
            listOf(veh(id = 1, distanceM = listOf(25, 20, 15, 3, 2, 0)[i], lateralPos = lp, isBehind = isBehind)) to (i * 100L)
        }
        val firstEvents = drive(d, firstFrames, config = longCooldown)
        assertEquals(1, firstEvents.size)

        // Second overtake immediately after (tid 2): within cooldown.
        val secondFrames = (0..5).map { i ->
            val lp = listOf(0.48f, 0.3f, 0.18f, 0.1f, 0.12f, 0.2f)[i]
            val isBehind = i == 5
            listOf(veh(id = 2, distanceM = listOf(25, 20, 15, 3, 2, 0)[i], lateralPos = lp, isBehind = isBehind)) to (600L + i * 100L)
        }
        val secondEvents = drive(d, secondFrames, config = longCooldown)
        assertTrue("cooldown should suppress the second emit", secondEvents.isEmpty())
    }

    // ── alongside-stationary skip ────────────────────────────────────────────

    @Test fun `real overtake ending in mutual junction stop does not emit`() {
        // tid 1 is a genuine overtake whose clearance stays above the 1.0 m
        // emit threshold. The rider then brakes to a junction stop alongside
        // the just-overtaken vehicle; both end up near-stationary at the
        // junction so the decoder flags the continuing track as
        // isAlongsideStationary. Without the alongside-stationary skip, the
        // two close stationary frames at |rx|=0.1 m would drag the median
        // below the emit threshold and the terminate path would fire a bogus
        // GRAZING event. With the skip, the clearance stays at the genuine
        // overtake's 1.14 m and the event is correctly suppressed.
        val d = ClosePassDetector()
        val stopped = veh(
            distanceM = 2,
            speedMs = 0f,
            size = VehicleSize.TRUCK,
            lateralPos = 0.033f, // 0.1 m right
            isAlongsideStationary = true,
        )
        val frames = listOf(
            // Real overtake phase: arms at 1.41 m, 1.14 m alongside.
            listOf(veh(distanceM = 25, lateralPos = 0.47f)) to 0L,
            listOf(veh(distanceM = 20, lateralPos = 0.45f)) to 100L,
            listOf(veh(distanceM = 15, lateralPos = 0.4f)) to 200L,
            listOf(veh(distanceM = 2, lateralPos = 0.38f)) to 300L, // 1.14 m alongside
            // Alongside phase: same tid, now stationary alongside the rider.
            listOf(stopped) to 400L,
            listOf(stopped) to 500L,
            // Track drops; terminate path runs.
            emptyList<Vehicle>() to 600L,
        )
        val events = drive(d, frames)
        assertTrue("alongside frame must not pull min below the emit threshold", events.isEmpty())
    }

    // ── lateral-unknown skip ─────────────────────────────────────────────────

    /** A sentinel frame as the DECODER builds one: the previous frame's offset
     *  carried forward, on a track already wider than
     *  [RadarV2Decoder.LATERAL_UNKNOWN_PREV_LATERAL_THRESHOLD], which is what
     *  lets a run start. So a real one is never centred and never carries a raw
     *  zero, and a fixture writing `lateralPos = 0f` describes a frame the
     *  decoder cannot produce. */
    private fun sentinel(distanceM: Int, carried: Float = 0.5f) = veh(distanceM = distanceM, lateralPos = carried, lateralUnknown = true)

    @Test fun `lateral-unknown frames do not pollute min tracking`() {
        // Track armed on a real overtake whose closest frame stayed above the
        // 1.0 m emit threshold, then a held-over frame arrives alongside. The
        // held offset is not a measurement and must not become the clearance.
        val d = ClosePassDetector()
        val frames = listOf(
            // Real overtake phase: arms at 1.41 m, 1.14 m alongside.
            listOf(veh(distanceM = 25, lateralPos = 0.47f)) to 0L,
            listOf(veh(distanceM = 20, lateralPos = 0.45f)) to 100L,
            listOf(veh(distanceM = 15, lateralPos = 0.4f)) to 200L,
            listOf(veh(distanceM = 2, lateralPos = 0.38f)) to 300L, // 1.14 m alongside
            listOf(sentinel(distanceM = 1)) to 400L,
            // Track drops; terminate path runs.
            emptyList<Vehicle>() to 500L,
        )
        val events = drive(d, frames)
        assertTrue("a held offset must not become this pass's clearance", events.isEmpty())
    }

    @Test fun `a pass made entirely of lateral-unknown frames emits nothing`() {
        // The blast radius of that skip, pinned rather than left to be
        // rediscovered. The decoder holds a sentinel run open all the way in,
        // so a whole overtake can arrive with every frame flagged. The pass
        // goes uncounted instead of being counted at the held-over offset.
        // Under-reporting is the deliberate direction; see the skip's comment
        // in ClosePassDetector.
        val d = ClosePassDetector()
        val frames = listOf(
            listOf(sentinel(distanceM = 25)) to 0L,
            listOf(sentinel(distanceM = 18)) to 100L,
            listOf(sentinel(distanceM = 12)) to 200L,
            listOf(sentinel(distanceM = 6)) to 300L,
            listOf(sentinel(distanceM = 2)) to 400L,
            emptyList<Vehicle>() to 500L,
        )
        assertTrue(
            "an unmeasured pass must be uncounted, never counted at a held-over offset",
            drive(d, frames).isEmpty(),
        )
    }

    @Test fun `a held offset tighter than the emit cutoff is still skipped`() {
        // What makes the skip load-bearing rather than merely agreeing with the
        // decoder. A run cannot start below a carried 0.5 today, which is 1.5 m
        // and already above the 1.0 m emit cutoff, so every faithful sentinel
        // frame is harmless whatever the detector does with it. Lower that
        // decoder threshold and this is the frame that arrives: held at 0.45 m,
        // alongside, and sampled it emits a GRAZING pass the radar never took.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 2, lateralPos = 0.15f, lateralUnknown = true) to 300L,
        )
        val events = drive(d, frames) + terminate(d, 400L)
        assertTrue("a held offset must never be sampled, however tight, got $events", events.isEmpty())
    }

    // ── disabled ─────────────────────────────────────────────────────────────

    @Test fun `disabled config never emits`() {
        val d = ClosePassDetector()
        val disabled = baseConfig.copy(enabled = false)
        val frames = listOf(
            listOf(veh(distanceM = 20, lateralPos = 0.2f)) to 0L,
            listOf(veh(distanceM = 10, lateralPos = 0.1f)) to 100L,
            listOf(veh(distanceM = 2, lateralPos = 0.08f)) to 200L,
            listOf(veh(distanceM = 0, lateralPos = 0.15f, isBehind = true)) to 300L,
        )
        val events = drive(d, frames, config = disabled)
        assertTrue(events.isEmpty())
    }

    // ── gate: rangeY out of band ─────────────────────────────────────────────

    // covers the rangeY arm gate
    @Test fun `does not arm when target stays beyond maxRangeY`() {
        // Every frame sits at 45 m > maxRangeYM (40), so rangeYOk is false on
        // each arm attempt; the lateral track is tight and closing fast, so
        // only the rangeY gate is keeping it from arming. Track drops at the
        // end and must NOT emit. Kills a mutant that drops the rangeY upper
        // bound (e.g. `in 0..config.maxRangeYM` → `>= 0`).
        val d = ClosePassDetector()
        val frames = (0..4).map { i ->
            listOf(veh(distanceM = 45, lateralPos = 0.1f, speedMs = -8f)) to i * 100L
        } + listOf(
            // Alongside, but closing too slowly to arm anything by itself: it
            // gives a mutant that armed at 45 m something to emit.
            listOf(veh(distanceM = 2, lateralPos = 0.1f, speedMs = -1f)) to 500L,
            emptyList<Vehicle>() to 600L,
        )
        val events = drive(d, frames)
        assertTrue("a target beyond maxRangeY must never arm", events.isEmpty())
    }

    // ── gate: rider speed floor ──────────────────────────────────────────────

    // covers the rider-speed arm gate
    @Test fun `does not arm when rider speed is below the configured floor`() {
        // Non-default floor of 5 m/s with the rider at 3 m/s makes riderOk
        // false on every arm attempt; all other gates (rangeY, closing, frames,
        // lateral) pass. No event. Kills a mutant that flips the `>=` to `<=`
        // or drops the riderOk conjunct.
        val d = ClosePassDetector()
        val floored = baseConfig.copy(riderSpeedFloorMs = 5f)
        val frames = (0..4).map { i ->
            listOf(veh(distanceM = 20 - i * 3, lateralPos = 0.1f, speedMs = -8f)) to i * 100L
        } + listOf(
            listOf(veh(distanceM = 2, lateralPos = 0.1f, speedMs = -8f)) to 500L,
            emptyList<Vehicle>() to 600L,
        )
        val events = drive(d, frames, bikeSpeedMs = 3f, config = floored)
        assertTrue("rider below the speed floor must never arm", events.isEmpty())
    }

    // ── the rider's threshold ────────────────────────────────────────────────

    @Test fun `the rider's threshold decides, and the event carries it`() {
        // A 1.8 m pass under a 2.0 m threshold: counted, with the threshold
        // that counted it.
        val d = ClosePassDetector()
        val wide = baseConfig.copy(emitMinRangeXM = 2.0f)
        val frames = listOf(
            listOf(veh(distanceM = 30, lateralPos = 0.6f)) to 0L,
            listOf(veh(distanceM = 25, lateralPos = 0.6f)) to 100L,
            listOf(veh(distanceM = 20, lateralPos = 0.6f)) to 200L,
            listOf(veh(distanceM = 2, lateralPos = 0.6f)) to 300L, // 1.8 m
            emptyList<Vehicle>() to 400L,
        )
        val events = drive(d, frames, config = wide)
        assertEquals(1, events.size)
        assertEquals(2.0f, events[0].emitThresholdM, 0.001f)
        assertEquals(1.8f, events[0].clearanceM, 0.01f)
    }

    // ── LEFT side ────────────────────────────────────────────────────────────

    // covers the emitted side test
    @Test fun `negative signed range emits a LEFT side close pass`() {
        // A close overtake on the rider's left: lateralPos negative throughout,
        // so the pass point's signed offset is negative and the side resolves to
        // LEFT. The -1.5 m frame sits on the urban gate but cannot arm -
        // minFramesToArm is 3 - so arming happens on the third frame, and the
        // pass reads -0.15 → 0.45 m alongside (< 0.5 m → GRAZING, < 1.0 m →
        // emits). Kills a mutant that flips the `>= 0f` side test or hardcodes
        // RIGHT.
        val d = ClosePassDetector()
        val frames = listOf(
            veh(distanceM = 30, lateralPos = -0.5f, speedMs = -8f) to 0L, // -1.5 m, on the urban gate
            veh(distanceM = 22, lateralPos = -0.35f, speedMs = -8f) to 100L, // -1.05 m
            veh(distanceM = 14, lateralPos = -0.25f, speedMs = -8f) to 200L, // -0.75 m
            veh(distanceM = 3, lateralPos = -0.16f, speedMs = -8f) to 300L, // -0.48 m, still behind
            veh(distanceM = 2, lateralPos = -0.15f, speedMs = -8f) to 400L, // -0.45 m alongside
            veh(distanceM = 0, lateralPos = -0.25f, speedMs = -8f, isBehind = true) to 500L,
        )
        val events = drive(d, frames)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(ClosePassDetector.Side.LEFT, e.side)
        assertEquals(ClosePassDetector.Severity.GRAZING, e.severity)
        assertTrue("clearance should be ~0.45 m, got ${e.clearanceM}", e.clearanceM in 0.43f..0.47f)
    }

    // ── alongside window ─────────────────────────────────────────────────────

    /** Three wide frames that arm the track well before any of the frames a
     *  window test is actually about. Alongside frames are kept only from
     *  arming on, so a discriminating frame placed earlier is never sampled. */
    private fun armingPrefix(lateralPos: Float = 0.4f) = listOf(
        veh(distanceM = 30, lateralPos = lateralPos) to 0L,
        veh(distanceM = 28, lateralPos = lateralPos) to 100L,
        // minFramesToArm is 3, so this is the earliest frame that can arm.
        veh(distanceM = 26, lateralPos = lateralPos) to 200L,
    )

    /** Drop the track so the terminate path runs. */
    private fun terminate(d: ClosePassDetector, tsMs: Long) = d.decide(emptyList(), 7f, tsMs, baseConfig)

    @Test fun `a vehicle that never comes alongside emits nothing`() {
        // Tight lateral readings the whole way, but the track ends while the
        // vehicle is still 10 m back, so nothing was ever measured about a
        // pass. This is the common case rather than the corner one: a vehicle
        // following directly behind reads as centred, which logs as a
        // clearance of centimetres that never happened.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 22, lateralPos = 0.05f) to 300L, // 0.15 m
            veh(distanceM = 16, lateralPos = 0.02f) to 400L, // 0.06 m
            veh(distanceM = 10, lateralPos = 0.01f) to 500L, // 0.03 m
        )
        val events = drive(d, frames) + terminate(d, 600L)
        assertTrue("a pass measured only from far behind is not a pass", events.isEmpty())
    }

    @Test fun `the clearance comes from the alongside frames not a tighter one further back`() {
        // Both populations on one track: a centimetres reading at 20 m, and a
        // real 0.72 m as the vehicle draws level. The event must report the
        // alongside figure, which is the wider of the two.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 20, lateralPos = 0.02f) to 300L, // 0.06 m, far behind
            veh(distanceM = 8, lateralPos = 0.3f) to 400L, // 0.9 m
            veh(distanceM = 2, lateralPos = 0.24f) to 500L, // 0.72 m alongside
            veh(distanceM = 0, lateralPos = 0.3f, isBehind = true) to 600L,
        )
        val events = drive(d, frames)
        assertEquals(1, events.size)
        val e = events[0]
        assertTrue("clearance should be the alongside 0.72 m, got ${e.clearanceM}", e.clearanceM in 0.70f..0.74f)
        assertEquals(ClosePassDetector.Severity.VERY_CLOSE, e.severity)
        // The exact frame, not merely one inside the window: a `<= 2f` bound
        // also passes for the 20 m frame's rangeY being recorded wrongly as 2.
        assertEquals("the recorded rangeY must be the 2 m frame's", 2f, e.rangeYM, 0.01f)
    }

    @Test fun `a frame exactly at the window boundary is alongside`() {
        // The 4 m frame is tighter and outside; the 2 m frame is the boundary
        // itself. An exclusive boundary leaves the track with no alongside
        // sample at all, so it emits nothing and the count assertion catches it.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 4, lateralPos = 0.07f) to 300L, // 0.21 m, outside
            veh(distanceM = 2, lateralPos = 0.2f) to 400L, // 0.6 m, exactly at the boundary
        )
        val events = drive(d, frames) + terminate(d, 500L)
        assertEquals(1, events.size)
        assertTrue("the boundary frame must count, got ${events[0].clearanceM}", events[0].clearanceM in 0.58f..0.62f)
    }

    @Test fun `a frame just outside the window is not alongside`() {
        val d = ClosePassDetector()
        // Two frames outside, so a window one metre wider moves the median.
        val frames = armingPrefix() + listOf(
            veh(distanceM = 3, lateralPos = 0.2f) to 300L, // 0.6 m, just outside
            veh(distanceM = 3, lateralPos = 0.2f) to 350L, // 0.6 m, just outside
            veh(distanceM = 2, lateralPos = 0.3f) to 400L, // 0.9 m alongside
        )
        val events = drive(d, frames) + terminate(d, 500L)
        assertEquals(1, events.size)
        assertTrue("must report the alongside 0.9 m, got ${events[0].clearanceM}", events[0].clearanceM in 0.88f..0.92f)
    }

    @Test fun `an armed track that never came alongside does not survive its own pass`() {
        // It reaches the isBehind flip with nothing sampled, so it must still
        // terminate. Holding it open leaves an armed state for the decoder to
        // hand to the next vehicle on the same track id, which would then skip
        // every arming gate and report a threshold from a different vehicle.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            sentinel(distanceM = 2) to 300L,
            sentinel(distanceM = 2) to 400L,
            veh(distanceM = 0, lateralPos = 0.25f, isBehind = true) to 500L,
            // Same track id, a fresh vehicle that cannot arm on its own:
            // one frame, and closing far too slowly.
            veh(distanceM = 2, lateralPos = 0.1f, speedMs = -1f) to 600L,
        )
        val events = drive(d, frames) + terminate(d, 700L)
        assertTrue("a dead track must not lend its arming to the next vehicle, got $events", events.isEmpty())
    }

    @Test fun `a reused track id with no gap frame does not inherit the last car's arming`() {
        // The decoder can prune a track and give its id to a new one within a
        // single frame; the birth stamp is what tells the two cars apart.
        val d = ClosePassDetector()
        val frames = listOf(
            veh(distanceM = 30, lateralPos = 0.4f, bornAtMs = 100L) to 0L,
            veh(distanceM = 28, lateralPos = 0.4f, bornAtMs = 100L) to 100L,
            veh(distanceM = 26, lateralPos = 0.4f, bornAtMs = 100L) to 200L, // armed
            // A new car on the same id, alongside but closing too slowly to arm.
            veh(distanceM = 2, lateralPos = 0.1f, speedMs = -1f, bornAtMs = 300L) to 300L,
        )
        val events = drive(d, frames) + terminate(d, 400L)
        assertTrue("a new car on a reused id must arm on its own, got $events", events.isEmpty())
    }

    // ── an unresolved lateral reading ────────────────────────────────────────

    @Test fun `an exact-zero lateral reading is not a clearance of zero`() {
        // Alongside and inside every gate, but the raw lateral is exactly 0.
        // That is either the radar's no-answer value or a target dead behind
        // the bike, and neither is a clearance. Taken as one it logs a pass at
        // 0.00 m, which would describe a collision.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 2, lateralPos = 0.28f) to 300L, // 0.84 m, measured
            veh(distanceM = 1, lateralPos = 0f, rangeXmRaw = 0f) to 400L, // unresolved
            veh(distanceM = 0, lateralPos = 0f, rangeXmRaw = 0f) to 500L, // unresolved
        )
        val events = drive(d, frames) + terminate(d, 600L)
        assertEquals(1, events.size)
        assertTrue("must report the measured 0.84 m, got ${events[0].clearanceM}", events[0].clearanceM in 0.82f..0.86f)
    }

    @Test fun `a pass the radar never resolved sideways emits nothing`() {
        // Every alongside frame read zero, so nothing about this vehicle's
        // clearance was ever measured. Replaying the ride corpus put this
        // shape behind most reported passes of a few centimetres: a track that
        // read dead centre its whole life and was never seen to overtake.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 2, lateralPos = 0f, rangeXmRaw = 0f) to 300L,
            veh(distanceM = 1, lateralPos = 0f, rangeXmRaw = 0f) to 400L,
            veh(distanceM = 0, lateralPos = 0f, rangeXmRaw = 0f) to 500L,
        )
        val events = drive(d, frames) + terminate(d, 600L)
        assertTrue("an unresolved pass must be uncounted, got $events", events.isEmpty())
    }

    @Test fun `the sentinel is recognised on a radar mounted off centre`() {
        // The only fixtures where raw and corrected disagree, and so the only
        // ones that can tell which the skip reads. A rider with a 20 cm mount
        // offset has every sentinel frame corrected to 0.20 m: reading the
        // corrected value would take it as a measurement and log a pass at
        // 0.20 m. lateralPos here is the corrected value the decoder produces
        // (0.0667 * 3.0 = 0.20 m), with the raw reading still the radar's zero.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 2, lateralPos = 0.28f) to 300L, // 0.84 m, measured
            veh(distanceM = 1, lateralPos = 0.0667f, rangeXmRaw = 0f) to 400L, // unresolved
            veh(distanceM = 0, lateralPos = 0.0667f, rangeXmRaw = 0f) to 500L, // unresolved
        )
        val events = drive(d, frames) + terminate(d, 600L)
        assertEquals(1, events.size)
        assertTrue(
            "a sentinel under a mount offset must still be skipped, got ${events[0].clearanceM}",
            events[0].clearanceM in 0.82f..0.86f,
        )
    }

    @Test fun `a measured reading corrected onto the riders line is still a measurement`() {
        // The converse, and the reason the skip cannot simply test the
        // corrected value: a real 0.20 m reading that the mount offset
        // corrects onto the rider's own line is a measurement of a genuinely
        // tight pass, not an unresolved frame.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 2, lateralPos = 0f, rangeXmRaw = -0.20f) to 300L,
        )
        val events = drive(d, frames) + terminate(d, 400L)
        assertEquals(1, events.size)
        assertEquals(ClosePassDetector.Severity.GRAZING, events[0].severity)
        // The value itself, because this is the one fixture where the app does
        // report 0.00 m: a real reading the rider's own mount offset puts on
        // their line. Severity alone passes for anything under 0.5 m.
        assertEquals("the corrected reading is what is reported", 0f, events[0].clearanceM, 0.001f)
    }

    @Test fun `a genuinely tight pass the radar did measure still emits`() {
        // The bound on that skip: a real reading near zero is not the sentinel
        // and must survive, or the feature stops reporting the passes it exists
        // for.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 2, lateralPos = 0.05f) to 300L, // 0.15 m, measured
        )
        val events = drive(d, frames) + terminate(d, 400L)
        assertEquals(1, events.size)
        assertEquals(ClosePassDetector.Severity.GRAZING, events[0].severity)
        assertTrue("must keep the measured 0.15 m, got ${events[0].clearanceM}", events[0].clearanceM in 0.13f..0.17f)
    }

    @Test fun `a track that arms far away still reports its alongside pass`() {
        // The window governs the measurement, never the arming. The alongside
        // frame here is no longer closing fast enough to arm anything, so a
        // window wrongly applied to the arm gate leaves this pass unlogged.
        val d = ClosePassDetector()
        val frames = armingPrefix(lateralPos = 0.45f) + listOf(
            veh(distanceM = 2, lateralPos = 0.15f, speedMs = -1f) to 300L, // 0.45 m alongside
        )
        val events = drive(d, frames) + terminate(d, 400L)
        assertEquals(1, events.size)
        assertEquals(ClosePassDetector.Severity.GRAZING, events[0].severity)
    }

    // ── arming ───────────────────────────────────────────────────────────────

    @Test fun `a car that closes while off to the side and comes in as it slows still counts`() {
        // Closing fast 2.4 m to the side, then slowing as it comes in to pass
        // 0.75 m away: no single frame is both close sideways and fast.
        val d = ClosePassDetector()
        val frames = listOf(
            veh(distanceM = 30, lateralPos = 0.8f, speedMs = -8f) to 0L,
            veh(distanceM = 25, lateralPos = 0.8f, speedMs = -8f) to 100L,
            veh(distanceM = 20, lateralPos = 0.8f, speedMs = -8f) to 200L,
            veh(distanceM = 8, lateralPos = 0.3f, speedMs = -2f) to 300L,
            veh(distanceM = 2, lateralPos = 0.25f, speedMs = -1f) to 400L, // 0.75 m
        )
        val events = drive(d, frames) + terminate(d, 500L)
        assertEquals(1, events.size)
        assertEquals(0.75f, events[0].clearanceM, 0.01f)
    }

    // ── the pass point ───────────────────────────────────────────────────────

    @Test fun `a car still in line behind is not where it passed`() {
        // A ride's shape: in line at 3 m and 2 m, then out to 1.64 m and 2.14 m
        // as it draws level. The in-line readings are where it was, not how
        // close it came.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 3, lateralPos = -0.08f) to 300L, // -0.24 m
            veh(distanceM = 2, lateralPos = -0.1133f) to 400L, // -0.34 m
            veh(distanceM = 1, lateralPos = -0.5467f) to 500L, // -1.64 m
            veh(distanceM = 0, lateralPos = -0.7133f) to 600L, // -2.14 m
            veh(distanceM = 0, lateralPos = -0.8467f, isBehind = true) to 700L,
        )
        val events = drive(d, frames)
        assertTrue("a pass at 1.64 m is not a close pass, got $events", events.isEmpty())
    }

    @Test fun `a car pulling out as it passes reports where it drew level`() {
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 3, lateralPos = 0.1533f) to 300L, // 0.46 m, still behind
            veh(distanceM = 2, lateralPos = 0.22f) to 400L, // 0.66 m
            veh(distanceM = 1, lateralPos = 0.2867f) to 500L, // 0.86 m
            veh(distanceM = 0, lateralPos = 0.3533f) to 600L, // 1.06 m
            veh(distanceM = 0, lateralPos = 0.3867f, isBehind = true) to 700L,
        )
        val events = drive(d, frames)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(0.86f, e.clearanceM, 0.01f)
        assertEquals(ClosePassDetector.Severity.VERY_CLOSE, e.severity)
        // The rest of the event comes from the same frame.
        assertEquals(1f, e.rangeYM, 0.01f)
        assertEquals(500L, e.timestampMs)
        assertEquals(ClosePassDetector.Side.RIGHT, e.side)
    }

    @Test fun `the clearance is the median of the alongside frames`() {
        // In time order 0.84, 0.66, 0.60: the median is neither the first, the
        // last, the tightest nor the widest.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 2, lateralPos = 0.28f) to 300L, // 0.84 m
            veh(distanceM = 1, lateralPos = 0.22f) to 400L, // 0.66 m
            veh(distanceM = 0, lateralPos = 0.2f) to 500L, // 0.60 m
        )
        val events = drive(d, frames) + terminate(d, 600L)
        assertEquals(1, events.size)
        assertEquals(0.66f, events[0].clearanceM, 0.01f)
    }

    @Test fun `the event's rider speed and size are the pass frame's`() {
        // Armed as a car with the rider at 7 m/s; drawing level the radar sizes
        // it as a truck and the rider has slowed to 5 m/s (18 km/h).
        val d = ClosePassDetector()
        drive(d, armingPrefix())
        val pass = d.decide(listOf(veh(distanceM = 2, lateralPos = 0.2f, size = VehicleSize.TRUCK)), 5f, 300L, baseConfig)
        val events = pass + terminate(d, 400L)
        assertEquals(1, events.size)
        assertEquals(18, events[0].riderSpeedKmh)
        assertEquals(VehicleSize.TRUCK, events[0].vehicleSize)
    }

    @Test fun `with an even number of frames the wider middle one counts`() {
        // The upper median, as measured on the ride corpus. The lower one would
        // report 0.30 m here and log a pass the upper one does not.
        val d = ClosePassDetector()
        val frames = armingPrefix() + listOf(
            veh(distanceM = 2, lateralPos = 0.1f) to 300L, // 0.30 m
            veh(distanceM = 1, lateralPos = 0.4f) to 400L, // 1.20 m
        )
        val events = drive(d, frames) + terminate(d, 500L)
        assertTrue("the upper median is 1.20 m, got $events", events.isEmpty())
    }

    @Test fun `the closing speed is the approach peak, not the speed alongside`() {
        // Cars slow as they draw level, so the speed at the pass reads near zero.
        val d = ClosePassDetector()
        val frames = listOf(
            veh(distanceM = 30, lateralPos = 0.4f, speedMs = -12f) to 0L,
            veh(distanceM = 25, lateralPos = 0.4f, speedMs = -12f) to 100L,
            veh(distanceM = 20, lateralPos = 0.4f, speedMs = -8f) to 200L,
            veh(distanceM = 2, lateralPos = 0.2f, speedMs = -1f) to 300L,
        )
        val events = drive(d, frames) + terminate(d, 400L)
        assertEquals(1, events.size)
        assertEquals(43, events[0].closingSpeedKmh)
    }

    @Test fun `a reading above 35 metres per second is not the approach peak`() {
        val d = ClosePassDetector()
        val frames = listOf(
            veh(distanceM = 30, lateralPos = 0.4f, speedMs = -35.5f) to 0L,
            veh(distanceM = 25, lateralPos = 0.4f, speedMs = -10f) to 100L,
            veh(distanceM = 20, lateralPos = 0.4f, speedMs = -8f) to 200L,
            veh(distanceM = 2, lateralPos = 0.2f, speedMs = -1f) to 300L,
        )
        val events = drive(d, frames) + terminate(d, 400L)
        assertEquals(1, events.size)
        assertEquals(36, events[0].closingSpeedKmh)
    }

    @Test fun `a reading of exactly 35 metres per second is still the approach peak`() {
        val d = ClosePassDetector()
        val frames = listOf(
            veh(distanceM = 30, lateralPos = 0.4f, speedMs = -35f) to 0L,
            veh(distanceM = 25, lateralPos = 0.4f, speedMs = -10f) to 100L,
            veh(distanceM = 20, lateralPos = 0.4f, speedMs = -8f) to 200L,
            veh(distanceM = 2, lateralPos = 0.2f, speedMs = -1f) to 300L,
        )
        val events = drive(d, frames) + terminate(d, 400L)
        assertEquals(1, events.size)
        assertEquals(126, events[0].closingSpeedKmh)
    }

    @Test fun `the approach peak counts frames the clearance cannot use`() {
        // The fastest reading came on a frame with no lateral measurement. The
        // ride figure reads closing on every frame behind the rider, and the
        // event must give the same number for the same car.
        val d = ClosePassDetector()
        val frames = listOf(
            sentinel(distanceM = 30).copy(speedMs = -14f) to 0L,
            veh(distanceM = 25, lateralPos = 0.4f, speedMs = -8f) to 100L,
            veh(distanceM = 20, lateralPos = 0.4f, speedMs = -8f) to 200L,
            veh(distanceM = 15, lateralPos = 0.4f, speedMs = -8f) to 300L,
            veh(distanceM = 2, lateralPos = 0.2f, speedMs = -1f) to 400L,
        )
        val events = drive(d, frames) + terminate(d, 500L)
        assertEquals(1, events.size)
        assertEquals(50, events[0].closingSpeedKmh)
    }
}

/** Helper: single-vehicle + ts pairs. Forwards with the default
 *  baseConfig (enabled, default thresholds) since cooldown + disabled
 *  tests use the multi-vehicle overload with explicit config. */
private fun ClosePassDetectorTest.drive(
    d: ClosePassDetector,
    frame: List<Pair<Vehicle, Long>>,
    bikeSpeedMs: Float? = 7f,
): List<ClosePassDetector.Event> {
    val cfg = ClosePassDetector.Config(enabled = true)
    val out = mutableListOf<ClosePassDetector.Event>()
    for ((v, ts) in frame) {
        out.addAll(d.decide(listOf(v), bikeSpeedMs, ts, cfg))
    }
    return out
}
