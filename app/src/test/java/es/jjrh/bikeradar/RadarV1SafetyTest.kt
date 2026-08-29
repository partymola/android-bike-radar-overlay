// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety argument for letting the legacy stream reach the alert path.
 *
 * That stream carries range and nothing else: no closing speed, no lateral
 * position, no rider speed. [RadarV1Decoder] therefore writes two zeroes, and
 * a zero is indistinguishable from a real measurement to every consumer
 * downstream. These tests are the reason that is safe, and they pin BOTH
 * halves: the cues that must still work on range alone, and the cues that must
 * never fire without the data they are defined on.
 *
 * If one of these goes red, the honest fix is almost never to change the test.
 * It is that something downstream started reading a zero as a measurement.
 *
 * Expected values are literals rather than references to production
 * constants, so a constant moving the wrong way fails here instead of
 * agreeing with itself.
 */
class RadarV1SafetyTest {

    /** `[seq][vid, dist, flag]` with the present bit set on each vid. */
    private fun threat(vararg idDist: Pair<Int, Int>): ByteArray {
        val out = mutableListOf<Byte>(0x02)
        idDist.forEach { (id, dist) ->
            out += (0x80 or id).toByte()
            out += dist.toByte()
            out += 0x00
        }
        return out.toByteArray()
    }

    private fun decoderAt(t: () -> Long) = RadarV1Decoder(nowMs = t)

    // ── what the stream CAN drive ──────────────────────────────────────────

    @Test
    fun decodesRangeAndTrackIdFromAThreatPacket() {
        val state = decoderAt { 1_000L }.feed(threat(3 to 42, 5 to 17))
        assertNotNull("a threat packet must produce a state", state)
        assertEquals(DataSource.V1, state!!.source)
        // Sorted nearest-first, which is what every tier consumer assumes.
        assertEquals(listOf(17, 42), state.vehicles.map { it.distanceM })
        assertEquals(listOf(5, 3), state.vehicles.map { it.id })
    }

    @Test
    fun aHeartbeatAgesTracksOutSoTheLaneGoesClear() {
        var now = 1_000L
        val d = decoderAt { now }
        assertNotNull(d.feed(threat(1 to 20)))
        now += 2_001L
        val state = d.feed(byteArrayOf(0x12))
        assertNotNull("ageing a track out is a visible change", state)
        assertTrue("the lane must read clear once the track ages out", state!!.isClear)
    }

    // ── what it must NOT be able to drive ──────────────────────────────────

    @Test
    fun everyTrackIsMarkedLateralUnknown() {
        // Close-pass detection skips these frames outright. Without the flag,
        // lateralPos 0f reads as a nil-clearance pass on every single track.
        val state = decoderAt { 1_000L }.feed(threat(1 to 12, 2 to 30))!!
        assertTrue(
            "every legacy track must declare its lateral unknown",
            state.vehicles.all { it.lateralUnknown },
        )
    }

    @Test
    fun closingSpeedIsZeroSoNoClosingGateCanArm() {
        val state = decoderAt { 1_000L }.feed(threat(1 to 5))!!
        val v = state.vehicles.single()
        assertEquals(0f, v.speedMs, 0f)
        // The gates are all of the form `speedMs <= -floor` for a POSITIVE
        // floor, so zero fails them. Asserted at the literal boundaries the
        // urgent cue and close-pass detection actually use.
        assertTrue("must not clear a 6 m/s closing floor", v.speedMs > -6f)
        assertTrue("must not clear a 10 m/s closing floor", v.speedMs > -10f)
    }

    private val closePassCfg = ClosePassDetector.Config(
        enabled = true,
        riderSpeedFloorMs = 0f,
        closingSpeedFloorMs = 6f,
        emitMinRangeXM = 1.0f,
    )

    /** Drive a full approach, 40 m down to 2 m. On a V2 stream with real
     *  lateral data this shape is a textbook close pass. */
    private fun runApproach(bikeSpeedMs: Float?): Int {
        val detector = ClosePassDetector()
        var now = 1_000L
        val d = decoderAt { now }
        var emitted = 0
        for (dist in 40 downTo 2) {
            now += 100L
            val state = d.feed(threat(1 to dist)) ?: continue
            emitted += detector.decide(state.vehicles, bikeSpeedMs, now, closePassCfg).size
        }
        return emitted
    }

    @Test
    fun closePassDetectionNeverArmsOnLegacyTracks() {
        // The real case: a legacy state carries no rider speed, and decide()
        // returns immediately on a null.
        assertEquals("the legacy stream must never emit a close pass", 0, runApproach(null))
    }

    @Test
    fun closePassStaysClosedEvenIfRiderSpeedIsSuppliedFromElsewhere() {
        // The load-bearing one. The test above passes for a reason that could
        // evaporate: if rider speed ever arrives from another source (a phone
        // GPS, an eBike), decide() would no longer short-circuit and the only
        // things left holding the line are the closing-speed floor and the
        // lateral-unknown skip. Prove those hold on their own.
        assertEquals(
            "close-pass must stay closed on range-only data even with a rider speed",
            0,
            runApproach(8f),
        )
    }

    @Test
    fun aLegacyStateCarriesNoRiderSpeed() {
        // The rider-speed gate reads RadarState.bikeSpeedMs. The stream has no
        // device-status frame, so it must stay null rather than default to a
        // number a gate could clear.
        val state = decoderAt { 1_000L }.feed(threat(1 to 10))!!
        assertNull(state.bikeSpeedMs)
    }

    // ── through the real decider, which is what the claim is about ─────────

    /**
     * Drive a legacy approach through the real [AlertDecider] and collect
     * every cue.
     *
     * The tests above pin what the DECODER emits. That is not the same claim:
     * "the awareness tiers still work and the urgent cue cannot fire" is a
     * statement about the decider, and asserting `speedMs > -6f` would stay
     * green if the decider's own gate were ever loosened to `speedMs <= 0`.
     */
    private fun cuesForLegacyApproach(
        bikeSpeedMs: Float?,
        bikeNotDriving: Boolean? = null,
    ): List<AlertDecider.Event> {
        val decider = AlertDecider()
        val cues = mutableListOf<AlertDecider.Event>()
        var now = 1_000L
        val d = decoderAt { now }
        for (dist in 40 downTo 2) {
            // Two packets per range, so tracks clear the decider's
            // sustain-frames debounce the way a real stream would.
            repeat(2) {
                now += 120L
                val state = d.feed(threat(1 to dist)) ?: return@repeat
                cues += decider.decide(
                    vehicles = state.vehicles,
                    alertMaxM = 20,
                    nowMs = now,
                    bikeSpeedMs = bikeSpeedMs,
                    bikeNotDriving = bikeNotDriving,
                )
            }
        }
        return cues
    }

    @Test
    fun awarenessBeepsDoFireOnLegacyData() {
        // The feature's whole point: a rider on this hardware was shown and
        // told nothing at all. Without this, someone copying the close-pass
        // `lateralUnknown` skip into the tier path would silence every legacy
        // rider with the entire suite still green.
        val cues = cuesForLegacyApproach(bikeSpeedMs = 8f)
        assertTrue(
            "a closing legacy track must raise at least one awareness beep, got: $cues",
            cues.any { it is AlertDecider.Event.Beep },
        )
    }

    @Test
    fun theUrgentCueNeverFiresOnLegacyDataEvenWhenTheRiderIsStationary() {
        // The stationary rider is the case that ARMS the imminent-impact
        // override, so it is the one where only the closing-speed floor is
        // left holding the line. Rider speed is supplied from elsewhere, as an
        // eBike would, so the decider cannot simply bail for want of a speed.
        val cues = cuesForLegacyApproach(bikeSpeedMs = 0f, bikeNotDriving = true)
        assertTrue(
            "range-only data must never raise the urgent cue, got: $cues",
            cues.none { it is AlertDecider.Event.UrgentApproach },
        )
    }

    // ── packet-level rejections, so junk cannot become a phantom vehicle ───

    @Test
    fun sentinelAndPlaceholderRecordsAreSkipped() {
        val d = decoderAt { 1_000L }
        // vid 0x00 placeholder, vid 0xFD status marker, vid without the
        // present bit, and a 0xFF "far/uncertain" distance.
        val payload = byteArrayOf(
            0x02,
            0x00, 10, 0x00,
            0xFD.toByte(), 10, 0x00,
            0x7F, 10, 0x00,
            0x81.toByte(), 0xFF.toByte(), 0x00,
        )
        assertNull("no real vehicle in that packet, so no state change", d.feed(payload))
    }

    @Test
    fun aMalformedLengthDoesNotThrow() {
        val d = decoderAt { 1_000L }
        // 5 bytes: not a heartbeat, not 1+3N, not a sector packet.
        assertNull(d.feed(byteArrayOf(0x02, 0x81.toByte(), 10, 0x00, 0x00)))
    }

    // ── the ride record, where a zero would be published as a measurement ──

    /** Run a legacy approach through the accumulator and snapshot it. */
    private fun legacyRideStats(): RideStatsSnapshot {
        var mono = 10_000L
        val stats = RideStatsAccumulator(nowMsProvider = { 1_700_000_000_000L }, monoMsProvider = { mono })
        var now = 1_000L
        val d = decoderAt { now }
        for (dist in 30 downTo 4) {
            now += 100L
            mono += 100L
            d.feed(threat(1 to dist))?.let { stats.observeFrame(it) }
        }
        return stats.snapshot()
    }

    @Test
    fun aLegacyRideStillGetsAVehicleCount() {
        // The whole-source skip, pointed the wrong way. Every legacy track is
        // lateralUnknown, so a skip that keys on that flag alone drops every
        // vehicle of every frame - and the rider ends a real commute holding a
        // ride record of zeroes that reads as a quiet ride rather than as data
        // the radar could not supply.
        assertEquals("one tracked vehicle over the approach", 1, legacyRideStats().overtakesTotal)
    }

    @Test
    fun aLegacyRideRecordsNoLateralClearance() {
        // The other direction, and the one that publishes a falsehood: with no
        // lateral channel every lateralPos is 0f, so an unguarded extremum
        // writes a 0.0 m clearance into ride history and into Home Assistant -
        // a rider's tightest-pass record inventing a vehicle that shaved them.
        // Null is the honest value for "never measured".
        assertNull(legacyRideStats().minLateralClearanceM)
    }

    @Test
    fun aLegacyRideRecordsNoPeakClosingSpeed() {
        // Same class: the decoder writes 0 m/s, and the peak-closing extremum
        // only takes strictly-negative speeds, so nothing is recorded. Pinned
        // because a mutant relaxing that comparison to `<= 0` would publish a
        // measured "peak closing 0 km/h" for a ride full of overtakes.
        assertNull(legacyRideStats().peakClosingKmh)
    }
}
