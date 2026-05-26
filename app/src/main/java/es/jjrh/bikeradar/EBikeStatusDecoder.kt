// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Decoder for the Bosch smart-system **proprietary status stream** (the
 * channel the Flow app uses), NOT the official eBike eb21 protobuf. We receive
 * it by subscribing, read-only, to [Uuids.CHAR_EBIKE_STATUS] over the
 * phone-central link Flow already holds; the bike pushes datapoint frames that
 * fan out to every subscribed GATT client. We never write to the bike's
 * command channel.
 *
 * Wire format:
 * a notification value is a concatenation of TLV records
 * ```
 * record = <marker:1> <len:1> <body[len]>
 * ```
 * `marker 0x30` carries a datapoint; `marker 0x10` is Flow's
 * handshake/meta (skipped). A `0x30` body is:
 * ```
 * <objId: 2 bytes big-endian> <protobuf-ish fields>
 *   field 1 (tag 0x08) varint = the scalar value
 *   field 2 (tag 0x10) varint = presence flag (1)
 * ```
 * Per proto3 semantics a zero value is omitted, so a record with the presence
 * flag but no field-1 (e.g. `30 04 98 2d 10 01`) decodes to value 0 (e.g.
 * wheel stopped). Object IDs not in [Obj] are ignored (component-info and
 * block-read records use the same framing with `a2..` ids).
 *
 * Mapping reuses [LiveDataSnapshot] (the eb21-shaped model) so the existing
 * downstream pipeline (ride-edge, climb, capture log, overlay) is unchanged.
 */
object EBikeStatusDecoder {

    /** Known scalar object IDs (2-byte big-endian). */
    private object Obj {
        const val SPEED = 0x982D // 1/100 km/h (matches LiveDataSnapshot.speedRaw)
        const val CADENCE = 0x985A // rpm (scaling vs spec field TBD on a ride; stored raw)
        const val RIDER_POWER = 0x985B // watts
        const val BATTERY_SOC = 0x8088 // percent 0-100
        const val ODOMETER = 0x9818 // metres (total)
        // Identified but unmapped (no LiveDataSnapshot field): 0x985D motor
        // power, 0x9809 assist mode, 0x80E2 wheel circumference. Lock/light
        // object IDs not yet pinned - need a ride that toggles them.
    }

    /**
     * Merge one notification payload into [prev], returning the updated
     * snapshot. On malformed framing (truncated record/varint) returns [prev]
     * unchanged so the caller keeps the last known-good snapshot rather than
     * committing partial state.
     */
    fun mergeInto(prev: LiveDataSnapshot, bytes: ByteArray): LiveDataSnapshot {
        var next = prev
        var i = 0
        try {
            while (i + 1 < bytes.size) {
                val marker = bytes[i].toInt() and 0xff
                val len = bytes[i + 1].toInt() and 0xff
                val bodyStart = i + 2
                if (bodyStart + len > bytes.size) break // truncated record
                if (marker == 0x30 && len >= 2) {
                    val objId = ((bytes[bodyStart].toInt() and 0xff) shl 8) or
                        (bytes[bodyStart + 1].toInt() and 0xff)
                    val value = scalarValue(bytes, bodyStart + 2, bodyStart + len)
                    next = apply(next, objId, value)
                }
                i = bodyStart + len
            }
        } catch (_: Exception) {
            return prev
        }
        return next
    }

    /**
     * Read the field-1 (tag 0x08) varint within a record body `[start, end)`.
     * Returns 0 when field 1 is absent (proto3 omits a zero value, leaving only
     * the presence flag).
     */
    private fun scalarValue(b: ByteArray, start: Int, end: Int): Long {
        if (start < end && (b[start].toInt() and 0xff) == 0x08) {
            return readVarint(b, start + 1, end).first
        }
        return 0L
    }

    private fun apply(s: LiveDataSnapshot, objId: Int, value: Long): LiveDataSnapshot = when (objId) {
        Obj.SPEED -> s.copy(speedRaw = value.toInt())
        Obj.CADENCE -> s.copy(cadence = value.toInt())
        Obj.RIDER_POWER -> s.copy(riderPower = value.toInt())
        Obj.BATTERY_SOC -> s.copy(batterySoc = value.toInt())
        Obj.ODOMETER -> s.copy(odometerM = value)
        else -> s
    }

    /** Little-endian base-128 varint, MSB = continuation. Throws on overrun. */
    private fun readVarint(b: ByteArray, start: Int, end: Int): Pair<Long, Int> {
        var v = 0L
        var shift = 0
        var i = start
        while (true) {
            require(i < end) { "varint truncated" }
            require(shift < 64) { "varint > 64 bits" }
            val byte = b[i].toInt() and 0xff
            v = v or ((byte and 0x7f).toLong() shl shift)
            i++
            if (byte and 0x80 == 0) return v to i
            shift += 7
        }
    }
}
