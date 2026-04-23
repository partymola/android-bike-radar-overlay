// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RadarV2Decoder. Packet byte layout (9 bytes per target):
 *   [0] tid uint8
 *   [1] class uint8  (16=CLASS_LOW=BIKE, 36=CLASS_HIGH=TRUCK, else CAR)
 *   [2] rangeY int8   x0.1m, signed: positive = ahead, negative = behind.
 *                     Magnitude × 0.1 = distance, max 12.7 m. (Agent C
 *                     decoder; see decoder-audit-2026-04-22.md.)
 *   [3] bits 0..2: redundant lagged sign flag for b2 (not consulted).
 *       bits 3..7: chirp / sub-frame counter; not decoded.
 *   [4] rangeX int8   x0.1m (positive = right)
 *   [5] length class template (not decoded)
 *   [6] width class template (not decoded)
 *   [7] speedY int8   x0.5 m/s (negative = approaching)
 *   [8] 0x80 sentinel
 *
 * Header bytes prepended to all target packets: 0x02 0x00 (non-status, non-device-status).
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

    @Test fun deviceStatusFrameReturnsNullWhenNoStaleTracks() {
        val state = decoder.feed(byteArrayOf(0x04, 0x00))  // DEVICE_STATUS_BIT
        assertNull("device-status frame with no tracks should return null", state)
    }

    @Test fun statusFramePrunesStaleMovingTrack() {
        // Two positive-s8 frames to establish a moving forward track without
        // tripping the isBehind side-flip (which would reset the speed ref).
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL)))
        now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
        // 10.0 m -> 5.0 m over ~200ms -> ~25 m/s closing -> STALE_MOVING_MS window.
        decoder.feed(packet(target(tid = 1, rangeY = 50, cls = RadarV2Decoder.CLASS_NORMAL)))
        now += RadarV2Decoder.STALE_MOVING_MS + 200
        val state = decoder.feed(byteArrayOf(0x01, 0x00))
        assertTrue("status frame must age out stale moving tracks", state?.isClear == true)
    }

    // ── stale-window logic ───────────────────────────────────────────────────

    @Test fun movingTrackDropsAfterShortWindow() {
        // Two positive-s8 frames (no isBehind side-flip) so the speed ref
        // accumulates and classifies the track as moving.
        decoder.feed(packet(target(tid = 1, rangeY = 100, cls = RadarV2Decoder.CLASS_NORMAL)))
        now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
        // 10.0 m -> 5.0 m over ~200ms -> ~25 m/s closing
        decoder.feed(packet(target(tid = 1, rangeY = 50, cls = RadarV2Decoder.CLASS_NORMAL)))

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

    // ── Agent C distance encoding (signed int8 byte[2]) ──────────────────────

    @Test fun b2PositiveDecodesAsMagnitudeTimes01() {
        val state = decoder.feed(packet(target(tid = 1, rangeY = 10)))
        assertEquals(1, state!!.vehicles.single().distanceM)
        assertFalse(state.vehicles.single().isBehind)
    }

    @Test fun b2NegativeDecodesAsBehindAtMagnitude() {
        // b2=200 (s8=-56) → 5.6 m behind. Needs 2 frames for isBehind commit.
        decoder.feed(packet(target(tid = 1, rangeY = 200)))
        val state = decoder.feed(packet(target(tid = 1, rangeY = 200)))
        val v = state!!.vehicles.single()
        assertEquals(6, v.distanceM)
        assertTrue(v.isBehind)
    }

    @Test fun behindZoneRequiresDebounce() {
        // Single negative-s8 frame after a forward frame must NOT flip isBehind.
        decoder.feed(packet(target(tid = 5, rangeY = 10, speedYhalf = -6)))
        now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
        val state = decoder.feed(packet(target(tid = 5, rangeY = 250, speedYhalf = -6)))
        assertFalse("single behind frame must not flip isBehind", state!!.vehicles.single().isBehind)
    }

    @Test fun behindCommitsAfterTwoFrames() {
        decoder.feed(packet(target(tid = 5, rangeY = 10, speedYhalf = -6)))
        now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
        decoder.feed(packet(target(tid = 5, rangeY = 249, speedYhalf = -6)))
        now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
        val state = decoder.feed(packet(target(tid = 5, rangeY = 245, speedYhalf = -6)))
        val v = state!!.vehicles.single()
        assertTrue(v.isBehind)
        assertEquals(1, v.distanceM)  // |s8(245)| = 11 → 1.1 m, rounds to 1
    }

    @Test fun behindExitIsInstant() {
        decoder.feed(packet(target(tid = 5, rangeY = 10, speedYhalf = -6)))
        now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
        decoder.feed(packet(target(tid = 5, rangeY = 249, speedYhalf = -6)))
        now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
        decoder.feed(packet(target(tid = 5, rangeY = 245, speedYhalf = -6)))  // committed
        now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
        val state = decoder.feed(packet(target(tid = 5, rangeY = 5, speedYhalf = -6)))
        assertFalse(state!!.vehicles.single().isBehind)
    }

    @Test fun maxBehindIs12Point8m() {
        // s8(128) = -128 → 12.8 m, the most-negative value the channel encodes.
        decoder.feed(packet(target(tid = 1, rangeY = 128)))
        val state = decoder.feed(packet(target(tid = 1, rangeY = 128)))
        val v = state!!.vehicles.single()
        assertTrue(v.isBehind)
        assertEquals(13, v.distanceM)
    }

    @Test fun extendedTailgaterStaysClassifiedBehind() {
        // Apr-18 replay regression (tid 167 scenario): sustained negative-s8
        // track must end up isBehind with magnitude under 5 m.
        val sequence = listOf(254, 252, 250, 230, 240, 250, 248, 252, 255)
        var lastState: RadarState? = null
        for (b2 in sequence) {
            now += 100
            lastState = decoder.feed(packet(target(tid = 167, rangeY = b2)))
        }
        val v = lastState!!.vehicles.single()
        assertTrue("sustained behind track must end up isBehind", v.isBehind)
        assertTrue("close-tailgater distance must stay <=5 m", v.distanceM <= 5)
    }

    @Test fun positiveB2ApproachIsMonotonic() {
        val samples = listOf(100, 80, 60, 40, 20, 5)
        var lastDistance: Int? = null
        for (ry in samples) {
            now += RadarV2Decoder.SPEED_DT_MIN_MS + 50
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
     * (-4096..+4095, i.e. ±409.5 m). Positive = behind the bike (typical
     * for a rear radar); negative = ahead of bike (post-overtake).
     * [rangeX] is the 11-bit signed rangeX value (-1024..+1023, ±102.3 m).
     * They are packed into bytes [2..4] little-endian: bits 0..10 = rangeX,
     * bits 11..23 = rangeY.
     *
     * [templateLocked] controls bytes [5] and [6]: true defaults to the observed
     * `(16, 7)` post-lock tuple so targets appear in snapshots; false sets `(0, 0)`
     * for pre-lock gate tests.
     */
    private fun target(
        tid: Int,
        rangeY: Int,
        cls: Int = RadarV2Decoder.CLASS_NORMAL,
        rangeX: Int = 0,
        templateLocked: Boolean = true,
        speedYhalf: Int = 0,
    ): ByteArray {
        require(rangeY in -4096..4095) { "rangeY must be -4096..4095, got $rangeY" }
        require(rangeX in -1024..1023) { "rangeX must be -1024..1023, got $rangeX" }
        val rxBits = rangeX and 0x7FF                 // 11 bits
        val ryBits = rangeY and 0x1FFF                // 13 bits
        val packed = (ryBits shl 11) or rxBits        // 24-bit packed
        val b2 = packed and 0xFF
        val b3 = (packed shr 8) and 0xFF
        val b4 = (packed shr 16) and 0xFF
        val b5 = if (templateLocked) 16 else 0
        val b6 = if (templateLocked) 7 else 0
        return byteArrayOf(
            tid.toByte(),
            cls.toByte(),
            b2.toByte(),
            b3.toByte(),
            b4.toByte(),
            b5.toByte(),
            b6.toByte(),
            speedYhalf.toByte(),
            0x80.toByte(),
        )
    }

    // ── lock gate (byte[5]/byte[6] template) ─────────────────────────────────

    @Test fun unlockedTargetDoesNotAppearInSnapshot() {
        // b5=b6=0 means "no template yet". Suppress from overlay snapshot to
        // avoid drawing phantoms (seen e.g. as tid 0x89 on 2026-04-21, 8 frames
        // all at (0,0), classed LOW near 7m — rendered as a bike that was never
        // really there).
        val state = decoder.feed(packet(target(tid = 1, rangeY = 100, templateLocked = false)))
        assertNotNull("target frame must still emit a snapshot for liveness", state)
        assertTrue("unlocked target must not appear in vehicles list", state!!.isClear)
    }

    @Test fun trackBecomesVisibleOnceTemplateLocks() {
        decoder.feed(packet(target(tid = 1, rangeY = 100, templateLocked = false)))
        val locked = decoder.feed(packet(target(tid = 1, rangeY = 100, templateLocked = true)))
        assertEquals(1, locked!!.vehicles.size)
    }

    @Test fun trackStaysVisibleAfterTemplateReturnsToZero() {
        // Real-world behaviour: firmware sometimes flips (b5,b6) back to (0,0)
        // mid-track. Once we've seen a lock we trust it — no re-hiding.
        decoder.feed(packet(target(tid = 1, rangeY = 100, templateLocked = true)))
        val reUnlocked = decoder.feed(packet(target(tid = 1, rangeY = 100, templateLocked = false)))
        assertEquals(1, reUnlocked!!.vehicles.size)
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
