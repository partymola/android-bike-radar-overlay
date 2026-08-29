// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Stateful decoder for legacy-stream notifications on `6a4e3203`.
 *
 * Implements the packet layout specified in the protocol repo, which is the
 * authority for it. Two things differ from that repo's own reference decoder
 * and both are safety decisions rather than style, so do not "restore" them:
 *
 *  - The reference writes `speedMs = null`; [Vehicle.speedMs] here is a
 *    non-null Float, so this writes [NO_CLOSING_SPEED] instead. That value is
 *    NOT a claim that the vehicle is stationary. It is the fail-closed
 *    sentinel: every gate that could act on closing speed asks for a NEGATIVE
 *    speed past a positive floor, so a zero can never arm one. Writing a
 *    plausible-looking guess here, or deriving one from successive distances,
 *    would arm the urgent cue off a number the radar never sent.
 *  - [Vehicle.lateralUnknown] is set on every track. The stream carries no
 *    lateral channel at all, and `lateralPos = 0f` means "same lane, dead
 *    centre" to every consumer that reads it. Without the flag, close-pass
 *    detection would score each track as a nil-clearance pass.
 *
 * What this stream CAN drive: the distance-scored awareness tiers, the
 * all-clear, and the overlay's range rendering. What it cannot: the urgent
 * cue, close-pass detection, and anything lateral. `RadarV1SafetyTest` pins
 * both halves of that, and it is the test to read before widening this.
 *
 * Maintains a set of active tracks keyed by track id; each threat packet
 * refreshes the tracks it mentions, and any track unseen for [STALE_MS] is
 * dropped. Not thread-safe; call from a single coroutine.
 *
 * Packet layout (see the protocol repo's legacy-stream section):
 *   1 byte, low nibble = 0x2:         heartbeat (liveness only)
 *   6 bytes, byte[0] == 0x06:         sector amplitude (not decoded)
 *   1 + 3N bytes (N = 1..6):          [seq][vid, dist, flag]*N
 *     vid == 0x00                     "no vehicle" placeholder; skip
 *     vid == 0xFD                     header/status marker; skip
 *     vid  < 0x80                     bit 7 = vehicle-present flag; skip
 *     otherwise track id = vid & 0x7F
 *     dist = uint8 metres (0xFF = "far/uncertain" sentinel; skip)
 *     flag = uint8 state flag (values 0 or 1; meaning unconfirmed, not a
 *            velocity - public write-ups calling it approach speed are wrong)
 */
class RadarV1Decoder(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val staleMs: Long = STALE_MS,
) {
    private data class Track(val vehicle: Vehicle, val lastSeen: Long)

    private val tracks = HashMap<Int, Track>()

    /**
     * Feed one notification payload. Returns a new [RadarState] when the
     * visible track set changed, else null.
     *
     * A heartbeat returns null unless it aged a track out, which is correct
     * for the state bus but means the CALLER must treat every payload as
     * liveness for its watchdog rather than only the ones that return a
     * state. A silent stream and a stream of pure heartbeats are different
     * conditions and only the caller can tell them apart.
     */
    fun feed(payload: ByteArray): RadarState? {
        val now = nowMs()
        val changed = when {
            payload.size == 1 -> pruneStale(now)
            payload.size == 6 && payload[0] == 0x06.toByte() -> pruneStale(now)
            isThreatPacket(payload) -> ingestThreat(payload, now)
            else -> pruneStale(now)
        }
        return if (changed) snapshot(now) else null
    }

    /**
     * The spec's full detection rule, including the low-nibble type tag that
     * the reference decoder omits.
     *
     * The length test alone accepts any packet family whose length happens to
     * be 1+3N, and the hardware this path serves is precisely the hardware
     * nobody has a capture of. A misparsed packet becomes a phantom vehicle at
     * whatever range its bytes imply, and the born-close ghost filter cannot
     * catch it here because legacy tracks carry no birth metadata. The nibble
     * is one comparison and it is what the protocol notes actually specify.
     */
    private fun isThreatPacket(payload: ByteArray): Boolean = payload.size >= 4 &&
        (payload.size - 1) % 3 == 0 &&
        (payload[0].toInt() and 0x0F) == 0x02

    /** The current track set without feeding a payload, so a caller can
     *  refresh liveness on a heartbeat that changed nothing. */
    fun currentState(): RadarState = snapshot(nowMs())

    private fun ingestThreat(payload: ByteArray, now: Long): Boolean {
        var changed = pruneStale(now)
        payload.drop(1).chunked(3).forEach { triple ->
            if (triple.size < 3) return@forEach
            val vid = triple[0].toInt() and 0xFF
            val dist = triple[1].toInt() and 0xFF
            // triple[2] is the flag byte; it is not a velocity, so it is not
            // surfaced. See the class KDoc.
            if (vid == 0x00 || vid == 0xFD || vid < 0x80) return@forEach
            if (dist == 0xFF) return@forEach
            val id = vid and 0x7F
            val existing = tracks[id]?.vehicle
            tracks[id] = Track(
                vehicle = Vehicle(
                    id = id,
                    distanceM = dist,
                    speedMs = NO_CLOSING_SPEED,
                    size = existing?.size ?: VehicleSize.CAR,
                    lateralUnknown = true,
                ),
                lastSeen = now,
            )
            changed = true
        }
        return changed
    }

    private fun pruneStale(now: Long): Boolean {
        val before = tracks.size
        tracks.values.removeAll { now - it.lastSeen > staleMs }
        return tracks.size != before
    }

    private fun snapshot(now: Long): RadarState = RadarState(
        vehicles = tracks.values.map { it.vehicle }.sortedBy { it.distanceM },
        timestamp = now,
        source = DataSource.V1,
    )

    /** Force-drop all tracks, e.g. on disconnect. */
    fun reset() {
        tracks.clear()
    }

    companion object {
        /** Drop a track unseen for longer than this. The threat cadence is
         *  sub-second per track in heavy traffic; 2 s smooths sparse traffic
         *  without holding vanished vehicles. */
        const val STALE_MS = 2000L

        /** The stream carries no velocity. Zero is the fail-closed sentinel,
         *  not a measurement - see the class KDoc. */
        const val NO_CLOSING_SPEED = 0f
    }
}
