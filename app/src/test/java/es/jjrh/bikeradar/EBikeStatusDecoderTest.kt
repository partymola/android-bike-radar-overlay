// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for the Bosch proprietary status-stream decoder. Frames marked
 * "(capture)" are verbatim notification payloads observed on the wire; the
 * rest are constructed per the documented `30 <len> <objId> 08 <varint>`
 * format.
 */
class EBikeStatusDecoderTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val empty = LiveDataSnapshot()

    // ── speed (object 0x982D, 1/100 km/h -> speedRaw) ──────────────────────

    @Test
    fun decodesSpeedFromCaptureFrame() {
        // (capture) wheel spun up. c3 08 = 1091 = 10.91 km/h.
        val s = EBikeStatusDecoder.mergeInto(empty, hex("3007982d08c3081001"))
        assertEquals(1091, s.speedRaw)
    }

    @Test
    fun speedDecaysAcrossCaptureFrames() {
        // (capture) coasting down: 1077 -> 653 -> 365.
        assertEquals(1077, EBikeStatusDecoder.mergeInto(empty, hex("3007982d08b5081001")).speedRaw)
        assertEquals(653, EBikeStatusDecoder.mergeInto(empty, hex("3007982d088d051001")).speedRaw)
        assertEquals(365, EBikeStatusDecoder.mergeInto(empty, hex("3007982d08ed021001")).speedRaw)
    }

    @Test
    fun absentValueMeansZero() {
        // (capture) wheel stopped: presence flag, no field-1 (proto3 omits the
        // zero), so speed = 0.
        val s = EBikeStatusDecoder.mergeInto(empty, hex("3004982d1001"))
        assertEquals(0, s.speedRaw)
    }

    // ── multiple records in one notification; unknown ids skipped ──────────

    @Test
    fun parsesConcatenatedRecordsAndSkipsUnknownIds() {
        // (capture) 0x9808 (a 2nd speed source, unmapped) then 0x982D speed.
        val s = EBikeStatusDecoder.mergeInto(empty, hex("3007980808ba0810013007982d08b5081001"))
        assertEquals(1077, s.speedRaw) // only the mapped 982D lands
    }

    @Test
    fun skipsHandshakeMarkerRecords() {
        // 0x10 meta/handshake record, then a 0x30 speed record.
        val s = EBikeStatusDecoder.mergeInto(empty, hex("100201033007982d08c3081001"))
        assertEquals(1091, s.speedRaw)
    }

    // ── other mapped fields (constructed per format) ───────────────────────

    @Test
    fun decodesBatteryOdometerCadenceRiderPower() {
        // battery 0x8088 = 72 %
        assertEquals(72, EBikeStatusDecoder.mergeInto(empty, hex("300480880848")).batterySoc)
        // odometer 0x9818 = 299596 m (varint of 299596 = cc a4 12)
        assertEquals(299596L, EBikeStatusDecoder.mergeInto(empty, hex("3006981808cca412")).odometerM)
        // cadence 0x985A = 80 (raw)
        assertEquals(80, EBikeStatusDecoder.mergeInto(empty, hex("3004985a0850")).cadence)
        // rider power 0x985B = 240 W (varint f0 01)
        assertEquals(240, EBikeStatusDecoder.mergeInto(empty, hex("3005985b08f001")).riderPower)
    }

    @Test
    fun decodesMotorPowerAssistModeWheelCircumference() {
        // motor power 0x985D = 250 W (varint fa 01)
        assertEquals(250, EBikeStatusDecoder.mergeInto(empty, hex("3005985d08fa01")).motorPower)
        // assist mode 0x9809 = 2 (slot index, not a fixed level - see LiveDataSnapshot.assistMode)
        assertEquals(2, EBikeStatusDecoder.mergeInto(empty, hex("300498090802")).assistMode)
        // wheel circumference 0x80E2 = 2200 mm (varint 98 11)
        assertEquals(2200, EBikeStatusDecoder.mergeInto(empty, hex("300580e2089811")).wheelCircumferenceMm)
    }

    @Test
    fun assistModeOffAbsentVarintIsZero() {
        // Assist off: presence flag, no field-1 (proto3 omits zero) -> 0.
        val s = EBikeStatusDecoder.mergeInto(empty, hex("300498091001"))
        assertEquals(0, s.assistMode)
    }

    // ── bench-pinned fields (light / lock / wheel-at-rest) ─────────────────

    @Test
    fun decodesBikeLightOnOff() {
        // 0x981c: binary on the proprietary stream. 1=on, 0=off (absent varint).
        assertEquals(1, EBikeStatusDecoder.mergeInto(empty, hex("3004981c0801")).bikeLight)
        assertEquals(0, EBikeStatusDecoder.mergeInto(empty, hex("3004981c1001")).bikeLight)
    }

    @Test
    fun decodesSystemLockedAnyNonZeroIsLocked() {
        // 0x808e enum: 2=locked-or-asleep -> true, 0=active -> false.
        assertEquals(true, EBikeStatusDecoder.mergeInto(empty, hex("3004808e0802")).systemLocked)
        assertEquals(false, EBikeStatusDecoder.mergeInto(empty, hex("3004808e1001")).systemLocked)
    }

    @Test
    fun decodesBikeNotDriving() {
        // 0x981a: 1=at rest -> true, 0=moving (absent varint) -> false.
        assertEquals(true, EBikeStatusDecoder.mergeInto(empty, hex("3004981a0801")).bikeNotDriving)
        assertEquals(false, EBikeStatusDecoder.mergeInto(empty, hex("3004981a1001")).bikeNotDriving)
    }

    @Test
    fun benchPinnedFieldsAreKnownAndNotReportedAsUnknown() {
        // After pinning, 0x981c/0x808e/0x981a must be filtered from the
        // unknown-object debug path (they're mapped now), unlike 0x9808.
        val bytes = hex("3004981c0801" + "3004808e0802" + "3004981a0801")
        assertEquals(0, EBikeStatusDecoder.extractUnknownObjectIds(bytes).size)
    }

    // ── merge preserves previously-seen fields ─────────────────────────────

    @Test
    fun mergePreservesPriorFields() {
        val withSpeed = EBikeStatusDecoder.mergeInto(empty, hex("3007982d08c3081001"))
        val withBattery = EBikeStatusDecoder.mergeInto(withSpeed, hex("300480880848"))
        assertEquals(1091, withBattery.speedRaw) // preserved
        assertEquals(72, withBattery.batterySoc) // added
    }

    @Test
    fun mergePreservesPriorMotorAssistAndWheel() {
        // Same preservation contract as the speed+battery case, exercising
        // the newer scalar branches so a future refactor that drops the
        // copy()-merge pattern on these fields trips a test.
        val withMotor = EBikeStatusDecoder.mergeInto(empty, hex("3005985d08fa01"))
        val withAssist = EBikeStatusDecoder.mergeInto(withMotor, hex("300498090802"))
        val withWheel = EBikeStatusDecoder.mergeInto(withAssist, hex("300580e2089811"))
        assertEquals(250, withWheel.motorPower) // preserved
        assertEquals(2, withWheel.assistMode) // preserved
        assertEquals(2200, withWheel.wheelCircumferenceMm) // added
    }

    // ── robustness ─────────────────────────────────────────────────────────

    @Test
    fun truncatedRecordReturnsPrevUnchanged() {
        val prior = EBikeStatusDecoder.mergeInto(empty, hex("3007982d08c3081001"))
        // len says 7 but only 3 body bytes present -> bail, keep prior.
        val out = EBikeStatusDecoder.mergeInto(prior, hex("3007982d08"))
        assertSame(prior, out)
    }

    // ── unknown-object extraction (debug "log unknown IDs" path) ───────────

    @Test
    fun extractUnknownObjectIdsReturnsEmptyWhenAllRecordsAreMapped() {
        // All-mapped frame: speed + battery. Nothing to log.
        val bytes = hex("3007982d08c3081001" + "300480880848")
        val unknowns = EBikeStatusDecoder.extractUnknownObjectIds(bytes)
        assertEquals(0, unknowns.size)
    }

    @Test
    fun extractUnknownObjectIdsReportsUnmappedRecordsOnly() {
        // 0x9808 (a 2nd speed source, unmapped) sandwiched between mapped
        // speed and battery records. Only the unmapped one is reported, and
        // it carries its scalar value.
        val bytes = hex(
            "3007982d08c3081001" + // mapped: speed 1091
                "3007980808ba081001" + // unmapped: 0x9808 = 1082
                "300480880848", // mapped: battery 72
        )
        val unknowns = EBikeStatusDecoder.extractUnknownObjectIds(bytes)
        assertEquals(1, unknowns.size)
        assertEquals(0x9808, unknowns[0].first)
        assertEquals(1082L, unknowns[0].second)
    }

    @Test
    fun extractUnknownObjectIdsSkipsHandshakeMarkerRecords() {
        // Marker 0x10 records (Flow handshake/meta) must not appear as
        // unknown - they're a different framing class, not an unmapped
        // object ID.
        val bytes = hex("100201033007982d08c3081001")
        val unknowns = EBikeStatusDecoder.extractUnknownObjectIds(bytes)
        assertEquals(0, unknowns.size)
    }

    @Test
    fun extractUnknownObjectIdsReturnsEmptyOnMalformedFrame() {
        // Same fail-safe contract as mergeInto: truncated record bails out
        // and no unknowns are reported. Prevents logging a record whose
        // value we couldn't trust.
        val bytes = hex("3007980808")
        val unknowns = EBikeStatusDecoder.extractUnknownObjectIds(bytes)
        assertEquals(0, unknowns.size)
    }

    @Test
    fun emptyPayloadIsNoOp() {
        val out = EBikeStatusDecoder.mergeInto(empty, ByteArray(0))
        assertNull(out.speedRaw)
        assertSame(empty, out)
    }

    @Test
    fun malformedVarintMidNotificationDiscardsTheWholeNotification() {
        // Notification with one valid record (speed = 1091) followed by a
        // record whose varint has the continuation bit set but no follow-up
        // byte. The decoder's outer try/catch catches the parse failure and
        // returns the PRIOR snapshot unchanged - it does not commit the
        // leading valid record's mid-stream state ("all or nothing", per the
        // KDoc). Trailing valid records are lost too; conservative pin so a
        // refactor toward partial-state commit is a deliberate change.
        val prior = LiveDataSnapshot(speedRaw = 999)
        val bytes = hex(
            "3007982d08c3081001" + // valid: speed = 1091
                "3004982d088f", // malformed: varint continuation bit set, no follow-up
        )
        val out = EBikeStatusDecoder.mergeInto(prior, bytes)
        assertEquals(999, out.speedRaw) // prior preserved, NOT 1091
        assertSame(prior, out)
    }

    // ── branch coverage: short 0x30 records and varint overflow ────────────

    @Test
    fun mergeInto0x30RecordWithLenOneIsSkipped() {
        // covers EBikeStatusDecoder.kt:68 - marker==0x30 but len<2 (len==1),
        // so the record body is too short to hold a 2-byte objId. The decode
        // arm is skipped and the loop advances past the record, leaving the
        // snapshot unchanged. `30 01 ff`: marker 0x30, len 1, one stray byte.
        val prior = LiveDataSnapshot(speedRaw = 999)
        val out = EBikeStatusDecoder.mergeInto(prior, hex("3001ff"))
        assertSame(prior, out) // unchanged: the len==1 record contributes nothing
    }

    @Test
    fun extractUnknownObjectIdsSkips0x30RecordWithLenOne() {
        // covers EBikeStatusDecoder.kt:109 - the len<2 skip arm on the
        // unknown-id path. A `30 01 ff` record is too short for an objId, so
        // nothing is reported even though the marker is 0x30.
        val unknowns = EBikeStatusDecoder.extractUnknownObjectIds(hex("3001ff"))
        assertEquals(0, unknowns.size)
    }

    @Test
    fun extractUnknownObjectIdsAppendsTwoUnmappedRecords() {
        // covers EBikeStatusDecoder.kt:114 - the second-append branch where
        // `out` is already non-null. Two unmapped object IDs in one frame:
        // 0x9808 (field-1 varint 0x14 = 20) and 0x9900 (field-1 varint
        // 0x0a = 10). The first append allocates the list; the second hits
        // the `out != null` path.
        val bytes = hex(
            "300498080814" + // unmapped 0x9808, value 20 (0x14)
                "30049900080a", // unmapped 0x9900, value 10 (0x0a)
        )
        val unknowns = EBikeStatusDecoder.extractUnknownObjectIds(bytes)
        assertEquals(2, unknowns.size)
        assertEquals(0x9808, unknowns[0].first)
        assertEquals(20L, unknowns[0].second)
        assertEquals(0x9900, unknowns[1].first)
        assertEquals(10L, unknowns[1].second)
    }

    @Test
    fun scalarValueWithNoFieldBodyDefaultsToZero() {
        // covers EBikeStatusDecoder.kt:139 - the `start < end` false arm.
        // A 0x30 record of len==2 carries only the 2-byte objId and no field
        // body, so scalarValue is called with start==end and returns 0.
        // `30 02 98 2d`: speed objId, no varint -> speedRaw = 0.
        val s = EBikeStatusDecoder.mergeInto(empty, hex("3002982d"))
        assertEquals(0, s.speedRaw)
    }

    @Test
    fun varintExceeding64BitsDiscardsTheNotification() {
        // Verifies a >64-bit varint is discarded: a field-1 varint of 11
        // continuation bytes (all 0x80) drives shift past 64 bits; readVarint
        // throws, mergeInto's outer try/catch catches it and returns the prior
        // snapshot. Record: marker 0x30, len 0x0e (14), objId 0x982d, tag 0x08,
        // then 11×0x80.
        // NOTE: this does NOT isolate the >64-bit overflow guard
        // (EBikeStatusDecoder.kt:168). The same try/catch (mergeInto.kt:76) also
        // catches the truncation guard (L167 `require(i < end)`), so removing
        // L168 alone is not provably caught here in isolation - the truncation
        // case would still throw on a varint that ran off the record end.
        val prior = LiveDataSnapshot(speedRaw = 999)
        val out = EBikeStatusDecoder.mergeInto(prior, hex("300e982d088080808080808080808080"))
        assertSame(prior, out) // overflow guard trips -> prior preserved, NOT mutated
    }
}
