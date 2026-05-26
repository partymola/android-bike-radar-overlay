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
        // assist mode 0x9809 = 2 (Tour)
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
}
