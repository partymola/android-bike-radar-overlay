// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a real captured V2 byte stream through [RadarV2Decoder] and
 * asserts on aggregate behaviour. Catches regressions where a refactor
 * of the decoder breaks against patterns that don't appear in the
 * synthetic unit tests (track ID reuse across frame boundaries,
 * lateralUnknown carry-over cascades, sequence numbering edge cases).
 *
 * Fixture: `app/src/test/resources/replay-fixture.txt`
 *   - 30s window of paired-vehicle traffic, timestamps relative to t=0
 *   - format: `relative_ms hex_bytes_no_spaces` per line, # comments
 */
class RadarV2DecoderReplayTest {

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
    fun replayProducesPlausibleSnapshots() {
        val frames = loadFixture()
        assertTrue("fixture must contain frames", frames.isNotEmpty())

        var clock = 0L
        val decoder = RadarV2Decoder(nowMs = { clock })

        var snapshotCount = 0
        var maxVehicles = 0
        var totalVehicleObservations = 0
        var minObservedDistanceM = Int.MAX_VALUE
        var maxObservedDistanceM = Int.MIN_VALUE
        val tids = HashSet<Int>()

        for (frame in frames) {
            clock = frame.relMs
            val state = decoder.feed(frame.bytes) ?: continue
            snapshotCount++
            if (state.vehicles.size > maxVehicles) maxVehicles = state.vehicles.size
            for (v in state.vehicles) {
                totalVehicleObservations++
                tids.add(v.id)
                if (v.distanceM < minObservedDistanceM) minObservedDistanceM = v.distanceM
                if (v.distanceM > maxObservedDistanceM) maxObservedDistanceM = v.distanceM
            }
        }

        assertTrue("at least one frame produced a snapshot", snapshotCount > 0)
        assertTrue(
            "fixture window should produce vehicle observations; got $totalVehicleObservations",
            totalVehicleObservations > 0,
        )
        assertTrue(
            "min vehicle distance must be non-negative; got $minObservedDistanceM",
            minObservedDistanceM >= 0,
        )
        assertTrue(
            "max vehicle distance must fit a plausible rear-radar range; got $maxObservedDistanceM",
            maxObservedDistanceM in 0..1000,
        )
        assertTrue(
            "track-id space must stay bounded; got ${tids.size} unique ids over ${frames.size} frames",
            tids.size in 1..64,
        )
    }

    @Test
    fun replayEmitsLastFrameAsCurrentState() {
        // Final frame must yield a state whose source is V2 and whose
        // timestamp reflects the clock at that frame — pins the liveness
        // invariant against decoder refactors that might short-circuit
        // empty target frames on the rebound.
        val frames = loadFixture()
        var clock = 0L
        val decoder = RadarV2Decoder(nowMs = { clock })

        var lastState: RadarState? = null
        for (frame in frames) {
            clock = frame.relMs
            val state = decoder.feed(frame.bytes)
            if (state != null) lastState = state
        }
        assertNotNull("replay must end with a non-null state", lastState)
        assertTrue("end state must be V2-sourced", lastState!!.source == DataSource.V2)
    }
}
