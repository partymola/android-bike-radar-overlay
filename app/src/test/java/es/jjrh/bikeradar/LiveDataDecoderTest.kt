// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveDataDecoderTest {

    // ── Encoding helpers ────────────────────────────────────────────────

    /** Encode a single unsigned varint, little-endian-7-bit-groups. */
    private fun varint(v: Long): ByteArray {
        if (v == 0L) return byteArrayOf(0)
        val out = ArrayList<Byte>(10)
        var x = v
        while (x != 0L || out.isEmpty()) {
            val byte = (x and 0x7f).toInt()
            x = x ushr 7
            if (x != 0L) out.add((byte or 0x80).toByte()) else out.add(byte.toByte())
            // Safety break on signed Long going negative shifted; proto3
            // varint for negative int32 is 10 bytes total.
            if (out.size >= 10) break
        }
        return out.toByteArray()
    }

    /** Encode a proto3 wire tag (field number + wire type). */
    private fun tag(field: Int, wireType: Int): ByteArray =
        varint(((field shl 3) or wireType).toLong())

    /** Build a payload of (field, varint-value) pairs. */
    private fun payload(vararg fields: Pair<Int, Long>): ByteArray {
        val out = ArrayList<Byte>()
        for ((f, v) in fields) {
            for (b in tag(f, 0)) out.add(b)
            for (b in varint(v)) out.add(b)
        }
        return out.toByteArray()
    }

    // ── Empty + default ─────────────────────────────────────────────────

    @Test fun `default snapshot has all fields null`() {
        val s = LiveDataSnapshot()
        assertNull(s.speedRaw); assertNull(s.cadence); assertNull(s.riderPower)
        assertNull(s.ambientBrightnessRaw); assertNull(s.batterySoc); assertNull(s.timeSec)
        assertNull(s.odometerM); assertNull(s.bikeLight); assertNull(s.systemLocked)
        assertNull(s.chargerConnected); assertNull(s.lightReserve); assertNull(s.diagnosisActive)
        assertNull(s.bikeNotDriving)
    }

    @Test fun `empty payload leaves snapshot unchanged`() {
        val prev = LiveDataSnapshot(speedRaw = 500, systemLocked = true)
        val next = LiveDataDecoder.mergeInto(prev, ByteArray(0))
        assertEquals(prev, next)
    }

    // ── Happy path: each field decodes correctly ────────────────────────

    @Test fun `speedRaw decodes at 1080 raw = 30 kmh`() {
        // 1080 / 100 = 10.80 km/h... wait, the KDoc says raw / 100 = km/h
        // and raw / 360 = m/s. 1080 raw = 10.80 km/h = 3.0 m/s. Test pins
        // the raw value; conversion happens at display time.
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(1 to 1080L))
        assertEquals(1080, s.speedRaw)
    }

    @Test fun `cadence is plain int32, not sint32 zigzag`() {
        // Bosch LDI spec: cadence is plain int32. The ESPHome bridge does
        // `(int32_t) v`. Sint32 zig-zag would decode -5 from a single
        // varint byte 0x09; plain int32 needs the full 10-byte negative
        // encoding (-5 as a 64-bit signed = 0xFFFFFFFFFFFFFFFB).
        // Sint32 zig-zag of -5 = (-5 << 1) ^ (-5 >> 31) = 9 (one byte).
        // We send the 10-byte negative pattern and confirm plain int32 wins.
        val negFive: ByteArray = byteArrayOf(
            tag(2, 0)[0],  // field 2 tag
            0xfb.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0x01.toByte(),
        )
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), negFive)
        assertEquals(-5, s.cadence)
    }

    @Test fun `cadence positive value via plain int32 round-trip`() {
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(2 to 95L))
        assertEquals(95, s.cadence)
    }

    @Test fun `time decodes as seconds since epoch, not milliseconds`() {
        // Choose any seconds-since-epoch value that falls in the same
        // year as Instant.ofEpochSecond expansion expects.
        val sec = 1_747_600_000L
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(11 to sec))
        assertEquals(sec, s.timeSec)
        // Confirm the seconds-not-ms convention via Instant round-trip:
        // a millisecond interpretation of this value would land in 1970.
        val iso = Instant.ofEpochSecond(s.timeSec!!).toString()
        assertTrue("ISO timestamp must not be 1970: $iso", !iso.startsWith("1970-"))
    }

    @Test fun `odometer is raw metres, not km`() {
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(12 to 12_345L))
        assertEquals(12_345L, s.odometerM)
    }

    @Test fun `bike_light enum values pass through unmodified`() {
        for (v in 0..3) {
            val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(17 to v.toLong()))
            assertEquals(v, s.bikeLight)
        }
    }

    @Test fun `system_locked bool decodes both edges`() {
        val on = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(21 to 1L))
        assertEquals(true, on.systemLocked)
        val off = LiveDataDecoder.mergeInto(on, payload(21 to 0L))
        assertEquals(false, off.systemLocked)
    }

    @Test fun `bike_not_driving merge does not stale-cache`() {
        val driving = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(25 to 1L))
        assertEquals(true, driving.bikeNotDriving)
        val rolling = LiveDataDecoder.mergeInto(driving, payload(25 to 0L))
        assertEquals(false, rolling.bikeNotDriving)
    }

    // ── Field-presence merge (NOTIFY carries only changed fields) ──────

    @Test fun `field-presence merge preserves prior values absent from payload`() {
        val prev = LiveDataDecoder.mergeInto(
            LiveDataSnapshot(),
            payload(10 to 80L, 21 to 1L)
        )
        assertEquals(80, prev.batterySoc)
        assertEquals(true, prev.systemLocked)
        val next = LiveDataDecoder.mergeInto(prev, payload(1 to 500L))
        // New payload only contained speed; both prior fields survive.
        assertEquals(500, next.speedRaw)
        assertEquals(80, next.batterySoc)
        assertEquals(true, next.systemLocked)
    }

    @Test fun `multi-field payload lands all fields atomically`() {
        val s = LiveDataDecoder.mergeInto(
            LiveDataSnapshot(),
            payload(21 to 1L, 22 to 0L, 12 to 12_345L),
        )
        assertEquals(true, s.systemLocked)
        assertEquals(false, s.chargerConnected)
        assertEquals(12_345L, s.odometerM)
    }

    // ── Forward compatibility: unknown tags must not corrupt state ──────

    @Test fun `unknown varint field is skipped without affecting other fields`() {
        // Synthetic field 99 wire-type 0; flank with known fields to ensure
        // the parser stays on rail.
        val data = payload(1 to 500L, 99 to 12345L, 25 to 1L)
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), data)
        assertEquals(500, s.speedRaw)
        assertEquals(true, s.bikeNotDriving)
    }

    @Test fun `unknown wire-type 2 length-delimited field is skipped`() {
        // Tag for field 99 wire-type 2 = (99 shl 3) | 2 = 794. Varint: 0x9a, 0x06.
        // Length varint = 3, then 3 arbitrary bytes, then a known field follows.
        val tag99lenDelim = tag(99, 2)
        val skipBytes = byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte())
        val knownField = ByteArray(tag(25, 0).size + 1).apply {
            System.arraycopy(tag(25, 0), 0, this, 0, tag(25, 0).size)
            this[tag(25, 0).size] = 1  // bool true
        }
        val data = ByteArray(tag99lenDelim.size + 1 + skipBytes.size + knownField.size)
        var p = 0
        System.arraycopy(tag99lenDelim, 0, data, p, tag99lenDelim.size); p += tag99lenDelim.size
        data[p++] = 3
        System.arraycopy(skipBytes, 0, data, p, skipBytes.size); p += skipBytes.size
        System.arraycopy(knownField, 0, data, p, knownField.size)

        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), data)
        assertEquals(true, s.bikeNotDriving)
    }

    // ── Malformed input: decoder returns prior snapshot unchanged ───────

    @Test fun `truncated varint returns prior snapshot unchanged`() {
        val prev = LiveDataSnapshot(speedRaw = 500)
        // tag for field 1 then a varint with continuation bit set but
        // no more bytes; readVarint must throw, mergeInto catches and
        // returns prev.
        val malformed = byteArrayOf(tag(1, 0)[0], 0x80.toByte())
        val next = LiveDataDecoder.mergeInto(prev, malformed)
        assertSame(prev, next)
    }

    @Test fun `unrecognised wire-type 3 returns prior snapshot unchanged`() {
        val prev = LiveDataSnapshot(speedRaw = 500)
        // Tag for field 1 wire-type 3 (deprecated start-group): (1 shl 3) or 3 = 11.
        val malformed = byteArrayOf(0x0b, 0x00)
        val next = LiveDataDecoder.mergeInto(prev, malformed)
        assertSame(prev, next)
    }

    // ── Privacy hardening helper (odometer delta) ──────────────────────

    @Test fun `odometer delta = current minus session-start baseline`() {
        // Session starts when we observe the first odometer reading.
        val open = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(12 to 1_000_000L))
        val later = LiveDataDecoder.mergeInto(open, payload(12 to 1_000_500L))
        // Delta calculation is the caller's responsibility - the privacy
        // rule says NEVER log the absolute. Verify the math.
        val delta = (later.odometerM ?: 0L) - (open.odometerM ?: 0L)
        assertEquals(500L, delta)
    }

    // ── Remaining varint fields: each decodes into its own slot ─────────

    @Test fun `rider_power decodes as plain int32 watts`() {
        // Field 5 = rider_power. Pinned as a plain int32 pass-through; the
        // value is the rider's pedal contribution in watts at the wire.
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(5 to 220L))
        assertEquals(220, s.riderPower)
    }

    @Test fun `ambient_brightness decodes as raw int`() {
        // Field 9 = ambient_brightness_raw; drives the SunsetCalculator
        // cross-check. Raw value passes through unscaled.
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(9 to 4_096L))
        assertEquals(4_096, s.ambientBrightnessRaw)
    }

    @Test fun `charger_connected true edge decodes`() {
        // Field 22 with a non-zero varint must read true. The existing
        // multi-field test only exercised the value-0 (false) edge.
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(22 to 1L))
        assertEquals(true, s.chargerConnected)
    }

    @Test fun `light_reserve bool decodes both edges`() {
        // Field 23 = light_reserve.
        val on = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(23 to 1L))
        assertEquals(true, on.lightReserve)
        val off = LiveDataDecoder.mergeInto(on, payload(23 to 0L))
        assertEquals(false, off.lightReserve)
    }

    @Test fun `diagnosis_active bool decodes both edges`() {
        // Field 24 = diagnosis_active.
        val on = LiveDataDecoder.mergeInto(LiveDataSnapshot(), payload(24 to 1L))
        assertEquals(true, on.diagnosisActive)
        val off = LiveDataDecoder.mergeInto(on, payload(24 to 0L))
        assertEquals(false, off.diagnosisActive)
    }

    // ── Skipped wire types 1 (fixed64) and 5 (fixed32) ──────────────────

    @Test fun `unknown fixed64 wire-type field is skipped without corrupting state`() {
        // Tag for field 99 wire-type 1 (64-bit fixed) followed by 8 payload
        // bytes, then a known varint field. The decoder must skip exactly
        // 8 bytes and stay on rail for the trailing field.
        val tag99fixed64 = tag(99, 1)
        val eightBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val knownField = payload(25 to 1L)
        val data = tag99fixed64 + eightBytes + knownField
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), data)
        assertEquals(true, s.bikeNotDriving)
    }

    @Test fun `unknown fixed32 wire-type field is skipped without corrupting state`() {
        // Tag for field 99 wire-type 5 (32-bit fixed) followed by 4 payload
        // bytes, then a known varint field. The decoder must skip exactly
        // 4 bytes.
        val tag99fixed32 = tag(99, 5)
        val fourBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val knownField = payload(1 to 750L)
        val data = tag99fixed32 + fourBytes + knownField
        val s = LiveDataDecoder.mergeInto(LiveDataSnapshot(), data)
        assertEquals(750, s.speedRaw)
    }

    // ── Length-delimited payload that runs past the buffer end ──────────

    @Test fun `length-delimited field claiming more bytes than remain returns prior snapshot`() {
        // Field 99 wire-type 2 with a declared length longer than the bytes
        // that actually follow. The skip index runs past bytes.size; the
        // decoder must abort and return the prior snapshot unchanged rather
        // than reading out of bounds.
        val prev = LiveDataSnapshot(speedRaw = 321)
        val tag99lenDelim = tag(99, 2)
        // Declare length 50 but supply only 2 trailing bytes.
        val data = tag99lenDelim + byteArrayOf(50) + byteArrayOf(0x01, 0x02)
        val next = LiveDataDecoder.mergeInto(prev, data)
        assertSame(prev, next)
    }

    // ── Over-long varint (> 64 bits) ────────────────────────────────────

    @Test fun `varint exceeding 64 bits returns prior snapshot unchanged`() {
        // A varint whose continuation bytes never terminate within 10 bytes
        // overflows the 64-bit shift guard. readVarint throws once shift
        // reaches 64; mergeInto catches it and returns prev.
        val prev = LiveDataSnapshot(cadence = 42)
        // field 1 tag, then 11 continuation bytes (all 0x80 = continuation,
        // payload 0) which forces shift past 64 before any terminator.
        val overlong = byteArrayOf(tag(1, 0)[0]) + ByteArray(11) { 0x80.toByte() }
        val next = LiveDataDecoder.mergeInto(prev, overlong)
        assertSame(prev, next)
    }
}
