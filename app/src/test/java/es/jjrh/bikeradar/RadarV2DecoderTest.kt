// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RadarV2Decoder]. Packet byte layout (9 bytes per target):
 *   [0]    tid uint8
 *   [1]    class uint8  (16=CLASS_LOW=BIKE, 36=CLASS_HIGH=TRUCK, else CAR)
 *   [2..4] 24-bit little-endian packed range field:
 *            bits 0..10  = rangeX (11-bit signed, x0.1 m)
 *            bits 11..23 = rangeY (13-bit signed, x0.1 m)
 *          Positive rangeY = behind the bike (rear radar typical case);
 *          negative = post-overtake (target now ahead of the rider).
 *   [5]    length class template
 *   [6]    width class template
 *   [7]    speedY int8   x0.5 m/s (negative = approaching)
 *   [8]    speedX int8   x0.5 m/s; raw 0x80 = sentinel "no lateral velocity"
 *
 * Header bytes prepended to all target packets: 0x02 0x00 (non-status,
 * non-device-status). See PROTOCOL.md in the bike-radar-docs sibling repo
 * for the full spec.
 */
class RadarV2DecoderTest {

    private var now = 1_000L
    private val decoder = RadarV2Decoder(nowMs = { now })

    // ── basic snapshot ───────────────────────────────────────────────────────

    @Test fun singleTargetReturnsSnapshot() {
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL)))
        assertNotNull("target frame should emit a snapshot", state)
        assertEquals(1, state!!.vehicles.size)
        assertEquals(DataSource.V2, state.source)
    }

    @Test fun emptyTargetFrameReturnsSnapshotForLiveness() {
        // Even an empty target frame (zero targets, non-status header) must emit
        // a snapshot so RadarState.timestamp reflects V2 liveness and the
        // data-source label shows V2 rather than "none".
        val state = decoder.feed(emptyPacket())
        assertNotNull("empty target frame must emit a snapshot for liveness", state)
    }

    // ── status frames ────────────────────────────────────────────────────────

    @Test fun statusFrameReturnsNullWhenNoStaleTracks() {
        val state = decoder.feed(byteArrayOf(0x01, 0x00))  // STATUS_FRAME_BIT
        assertNull("status frame with no tracks should return null", state)
    }

    @Test fun deviceStatusFrameAlwaysEmitsSnapshot() {
        // Device-status frames carry the rider's own bike speed in the final
        // byte; always emit a snapshot so bikeSpeedKmh propagates downstream
        // even when no targets are present.
        val state = decoder.feed(byteArrayOf(0x04, 0x00, 0x00))  // DEVICE_STATUS_BIT, speed=0
        assertNotNull("device-status frame must always emit a snapshot", state)
        assertEquals(0, state!!.bikeSpeedKmh)
    }

    @Test fun statusFramePrunesStaleMovingTrack() {
        // Approaching at -25 m/s (speedYhalf=-50) classifies the track as moving.
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL, speedYhalf = -50)))
        now += RadarV2Decoder.STALE_MOVING_MS + 200
        val state = decoder.feed(byteArrayOf(0x01, 0x00))
        assertTrue("status frame must age out stale moving tracks", state?.isClear == true)
    }

    // ── stale-window logic ───────────────────────────────────────────────────

    @Test fun movingTrackDropsAfterShortWindow() {
        // Approaching at -25 m/s (speedYhalf=-50) classifies the track as moving.
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL, speedYhalf = -50)))

        now += RadarV2Decoder.STALE_MOVING_MS + 200
        val dropped = decoder.feed(emptyPacket())
        assertTrue("moving track should drop after STALE_MOVING_MS", dropped?.isClear == true)
    }

    @Test fun parkedTrackSurvivesLongDropout() {
        decoder.feed(packet(target(tid = 7, rangeY = 80, cls = RadarV2Decoder.CLASS_NORMAL)))
        // No frames reference this target for 4 s (Doppler dropout at traffic light).
        // Empty target frame (non-status header + 0 targets) always emits a snapshot
        // for liveness, but the parked vehicle must still be in the snapshot.
        now += 4_000
        val midpoint = decoder.feed(emptyPacket())
        assertNotNull("empty frame always emits snapshot", midpoint)
        assertEquals(1, decoder.tracksForTest().size)

        now += 1_500
        val dropped = decoder.feed(emptyPacket())
        assertTrue("parked track should drop after STALE_PARKED_MS", dropped?.isClear == true)
    }

    // ── vehicle properties ───────────────────────────────────────────────────

    @Test fun rangeYMapsToDistanceM() {
        // rangeY raw=50 -> 5.0 m, rounds to 5
        val state = decoder.feed(packet(target(tid = 1, rangeY = 50, cls = RadarV2Decoder.CLASS_NORMAL)))
        assertEquals(5, state!!.vehicles.single().distanceM)
    }

    @Test fun positiveRangeXIsPositiveLateral() {
        // rangeX raw=+15 -> +1.5 m, lateral = 1.5/3.0 = 0.5
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL, rangeX = 15)))
        val lat = state!!.vehicles.single().lateralPos
        assertTrue("positive rangeX should give positive lateralPos", lat > 0f)
        assertEquals(0.5f, lat, 0.01f)
    }

    @Test fun negativeRangeXIsNegativeLateral() {
        // rangeX raw=-10 -> -1.0 m, lateral = -1.0/3.0 = -0.333
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL, rangeX = -10)))
        val lat = state!!.vehicles.single().lateralPos
        assertTrue("negative rangeX must give negative lateralPos", lat < 0f)
    }

    @Test fun lateralPositionClampsAtPlusOne() {
        // rangeX raw=+40 -> 4.0 m > LATERAL_FULL_M(3.0) -> clamp to +1.0
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL, rangeX = 40)))
        assertEquals(1f, state!!.vehicles.single().lateralPos, 0.0001f)
    }

    @Test fun lateralPositionClampsAtMinusOne() {
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL, rangeX = -40)))
        assertEquals(-1f, state!!.vehicles.single().lateralPos, 0.0001f)
    }

    @Test fun classLowClassifiesAsBike() {
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_LOW)))
        assertEquals(VehicleSize.BIKE, state!!.vehicles.single().size)
    }

    @Test fun classLowStableClassifiesAsBike() {
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_LOW_STABLE)))
        assertEquals(VehicleSize.BIKE, state!!.vehicles.single().size)
    }

    @Test fun classHighClassifiesAsTruck() {
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_HIGH)))
        assertEquals(VehicleSize.TRUCK, state!!.vehicles.single().size)
    }

    @Test fun classNormalClassifiesAsCar() {
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL)))
        assertEquals(VehicleSize.CAR, state!!.vehicles.single().size)
    }

    // ── distance / sign encoding ─────────────────────────────────────────────

    @Test fun positiveRangeYIsBehindTheBike() {
        // Positive rangeY = target behind the bike (rear radar typical case).
        val state = decoder.feed(packet(target(tid = 1, rangeY = 10)))
        assertEquals(1, state!!.vehicles.single().distanceM)
        assertFalse("positive rangeY must not be flagged isBehind (post-overtake)",
            state.vehicles.single().isBehind)
    }

    @Test fun negativeRangeYIsAheadAfterOvertake() {
        // Negative rangeY = target has passed the bike and is now in front.
        // isBehind flips on a single frame (no debounce).
        val state = decoder.feed(packet(target(tid = 1, rangeY = -56)))
        val v = state!!.vehicles.single()
        assertEquals(6, v.distanceM)
        assertTrue("negative rangeY must flag isBehind on first frame", v.isBehind)
    }

    @Test fun behindFlagFlipsInstantlyAfterOvertake() {
        // Approaching from behind, then post-overtake: a single negative-rangeY
        // frame flips isBehind without any debounce.
        decoder.feed(packet(target(tid = 5, rangeY = 10, speedYhalf = -6)))
        now += 200
        val state = decoder.feed(packet(target(tid = 5, rangeY = -25, speedYhalf = -6)))
        assertTrue("isBehind flips on the first negative-rangeY frame",
            state!!.vehicles.single().isBehind)
    }

    @Test fun behindFlagClearsInstantlyOnPositiveRangeY() {
        // After committing isBehind, a single positive-rangeY frame clears it.
        decoder.feed(packet(target(tid = 5, rangeY = -25, speedYhalf = -6)))
        now += 200
        val state = decoder.feed(packet(target(tid = 5, rangeY = 5, speedYhalf = -6)))
        assertFalse(state!!.vehicles.single().isBehind)
    }

    @Test fun rangeYAtChannelEdgeDecodesAsBehind() {
        // The most-negative value in a 13-bit signed field is -4096 → 409.6 m
        // ahead. Pick -128 (12.8 m ahead) so distance fits a small int comfortably.
        val state = decoder.feed(packet(target(tid = 1, rangeY = -128)))
        val v = state!!.vehicles.single()
        assertTrue(v.isBehind)
        assertEquals(13, v.distanceM)
    }

    @Test fun extendedTailgaterStaysBehindBike() {
        // Sustained close-range behind-bike track (close tailgater) must stay
        // not-isBehind (i.e. NOT post-overtake) and within 5 m.
        val sequence = listOf(40, 30, 20, 25, 30, 20, 25, 35, 40)
        var lastState: RadarState? = null
        for (ry in sequence) {
            now += 100
            lastState = decoder.feed(packet(target(tid = 167, rangeY = ry)))
        }
        val v = lastState!!.vehicles.single()
        assertFalse("close-following positive-rangeY track must not be isBehind", v.isBehind)
        assertTrue("close-tailgater distance must stay <=5 m", v.distanceM <= 5)
    }

    @Test fun positiveRangeYApproachIsMonotonic() {
        val samples = listOf(100, 80, 60, 40, 20, 5)
        var lastDistance: Int? = null
        for (ry in samples) {
            now += 200
            val state = decoder.feed(packet(target(tid = 9, rangeY = ry)))
            val d = state!!.vehicles.single().distanceM
            val prev = lastDistance
            if (prev != null) {
                assertTrue("distance must decrease monotonically (was $prev, now $d)",
                    d <= prev + 1)
            }
            lastDistance = d
        }
        assertTrue("final approach should be close", (lastDistance ?: 999) <= 1)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun emptyPacket(): ByteArray = byteArrayOf(0x02, 0x00)

    private fun packet(vararg targets: ByteArray): ByteArray =
        byteArrayOf(0x02, 0x00) + targets.fold(byteArrayOf()) { a, b -> a + b }

    /**
     * Builds a 9-byte target struct matching RadarV2Decoder's byte layout.
     * [rangeY] is the 13-bit signed rangeY value in 0.1 m units
     * (-4096..+4095, i.e. +-409.5 m). Positive = behind the bike (typical
     * for a rear radar); negative = ahead of bike (post-overtake).
     * [rangeX] is the 11-bit signed rangeX value (-1024..+1023, +-102.3 m).
     * They are packed into bytes [2..4] little-endian: bits 0..10 = rangeX,
     * bits 11..23 = rangeY.
     *
     * Bytes [5] and [6] default to the observed (16, 7) class-template tuple.
     * [speedXraw] is the raw byte[8] uint8 (0..255). Default 0x80 = the
     * radar's "no lateral velocity" sentinel.
     */
    private fun target(
        tid: Int,
        rangeY: Int,
        cls: Int = RadarV2Decoder.CLASS_NORMAL,
        rangeX: Int = 0,
        speedYhalf: Int = 0,
        speedXraw: Int = 0x80,
    ): ByteArray {
        require(rangeY in -4096..4095) { "rangeY must be -4096..4095, got $rangeY" }
        require(rangeX in -1024..1023) { "rangeX must be -1024..1023, got $rangeX" }
        require(speedXraw in 0..0xFF) { "speedXraw must be 0..0xFF, got $speedXraw" }
        val rxBits = rangeX and 0x7FF                 // 11 bits
        val ryBits = rangeY and 0x1FFF                // 13 bits
        val packed = (ryBits shl 11) or rxBits        // 24-bit packed
        val b2 = packed and 0xFF
        val b3 = (packed shr 8) and 0xFF
        val b4 = (packed shr 16) and 0xFF
        return byteArrayOf(
            tid.toByte(),
            cls.toByte(),
            b2.toByte(),
            b3.toByte(),
            b4.toByte(),
            16,
            7,
            speedYhalf.toByte(),
            speedXraw.toByte(),
        )
    }

    // ── lateral speed (byte[8]) ──────────────────────────────────────────────

    @Test fun positiveSpeedXIsRightward() {
        // raw=0x14 (+20) -> +10 m/s rightward.
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, speedXraw = 0x14)))
        assertEquals(10, state!!.vehicles.single().speedXMs)
    }

    @Test fun negativeSpeedXIsLeftward() {
        // raw=0xEC (-20 as signed int8) -> -10 m/s leftward.
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, speedXraw = 0xEC)))
        assertEquals(-10, state!!.vehicles.single().speedXMs)
    }

    @Test fun lateralVelocitySentinelDecodesAsNull() {
        // raw=0x80 sentinel means "no lateral velocity available".
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, speedXraw = 0x80)))
        assertNull("0x80 sentinel must yield null speedXMs",
            state!!.vehicles.single().speedXMs)
    }

    // ── device-status frame (rider bike speed) ───────────────────────────────

    @Test fun deviceStatusFrameUpdatesBikeSpeedKmh() {
        // raw=0x50 (80) -> 80 * 0.25 = 20 km/h.
        val state = decoder.feed(byteArrayOf(0x04, 0x00, 0x50))
        assertNotNull(state)
        assertEquals(20, state!!.bikeSpeedKmh)
    }

    @Test fun bikeSpeedKmhPersistsAcrossTargetFrames() {
        decoder.feed(byteArrayOf(0x04, 0x00, 0x50))   // 20 km/h
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100)))
        assertEquals("subsequent target snapshots carry the last bike speed",
            20, state!!.bikeSpeedKmh)
    }

    @Test fun resetClearsBikeSpeedKmh() {
        decoder.feed(byteArrayOf(0x04, 0x00, 0x50))   // 20 km/h
        decoder.reset()
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100)))
        assertNull("reset() must clear bikeSpeedKmh", state!!.bikeSpeedKmh)
    }

    // ── class debounce (asymmetric) ──────────────────────────────────────────

    @Test fun sizeUpgradeAppliesImmediately() {
        // BIKE -> CAR on the next frame; overlay must reflect it at once.
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_LOW)))
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL)))
        assertEquals(VehicleSize.CAR, state!!.vehicles.single().size)
    }

    @Test fun sizeDowngradeHoldsForSeveralFrames() {
        // CAR -> BIKE: do not downgrade on first frame. Should stay CAR until
        // DOWNGRADE_FRAMES consecutive frames at the smaller size.
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL)))
        for (i in 1 until RadarV2Decoder.DOWNGRADE_FRAMES) {
            val s = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_LOW)))
            assertEquals("frame $i must still show CAR", VehicleSize.CAR, s!!.vehicles.single().size)
        }
        // Kth consecutive LOW frame commits the downgrade.
        val finalState = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_LOW)))
        assertEquals(VehicleSize.BIKE, finalState!!.vehicles.single().size)
    }

    @Test fun downgradeCounterResetsOnUpgrade() {
        // HIGH -> NORMAL (downgrade proposal) -> HIGH (cancels proposal) ->
        // back to HIGH is stable; a single later NORMAL should not commit.
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_HIGH)))
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL)))
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_HIGH)))
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL)))
        assertEquals("must still show TRUCK after interleaved flip", VehicleSize.TRUCK, state!!.vehicles.single().size)
    }
}

private fun RadarV2Decoder.tracksForTest(): Map<Int, Any> {
    val f = RadarV2Decoder::class.java.getDeclaredField("tracks")
    f.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return f.get(this) as Map<Int, Any>
}
