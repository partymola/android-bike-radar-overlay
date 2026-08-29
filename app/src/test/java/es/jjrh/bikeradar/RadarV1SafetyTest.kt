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
}
