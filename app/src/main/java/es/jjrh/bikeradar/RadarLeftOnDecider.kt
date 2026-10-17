// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

/** How a radar link that streamed ended: the gap between its last received
 *  frame and Android's disconnect callback, and the callback's status. */
data class LinkLoss(val gapMs: Long, val status: Int)

/** Which walk-away alarm is sounding, and so which notification it posts.
 *  Never persisted or published. */
enum class WalkAwayAlarmKind {
    /** The radar went off and the phone still picks up the front camera. */
    DASHCAM,

    /** The radar was left on and walked away from. */
    RADAR,

    /** The radar was left on, and the phone heard the front camera just
     *  before it went: left on too, or carried switched on. */
    RADAR_AND_DASHCAM,
}

/**
 * Pure decisions for the radar-left-on alarm: the rider walked out of range
 * with the radar still on, with or without the front camera.
 *
 * Two signals from how the link ended ([LinkLoss]). A radar switched off ends
 * its link itself, within about a second of its last frame; one still on and
 * out of range goes silent and the link times out 3 to 4 s later. Measured on
 * one phone and one radar model, so both are default-deny: a walk-away only
 * on a timeout status ([TIMEOUT_STATUSES]), a switch-off only on the statuses
 * seen for one ([REMOTE_END_STATUSES]), and any other status changes nothing.
 * A radar has to show [CALIBRATION_SWITCH_OFFS] clean switch-offs on this
 * phone before the alarm can sound for it; one of those statuses with the
 * other's gap ([Drop.UNCLEAR]) sends the count back to 0
 * (`RadarLeftOnDeciderTest`).
 *
 * Mid-ride link losses look the same as a walk-away, so the drop must also
 * come with the alarm armed ([WalkAwayArmingGate]), no riding seen just before
 * it, and a link that streamed for [MIN_STREAMED_MS] (a radar that connects
 * and drops as the ride starts). A radar battery under [BATTERY_FLOOR_PCT] can
 * end the stream the same way, and does not qualify.
 *
 * Silent by design where the link cannot say: an unlocked or powered eBike
 * drops its own link with the radar's, so its last reading is a fresh
 * "unlocked" and the arming gate holds; a phone whose link takes longer to
 * time out than the stream watchdog's stall window records no gap at all
 * (`StreamReceipts`); a range-only radar never reaches here. Also silent: a
 * rider with no eBike who walks off within 30 s of riding, and a radar at the
 * edge of range that reconnects inside the check and drops again before it has
 * streamed [MIN_STREAMED_MS] (`anEdgeOfRangeReconnectStaysSilent`).
 *
 * It can sound wrongly where a switch-off loses its goodbye and ends as a
 * timeout, and after a stop of over 30 s with no eBike whose drop does not
 * recover within [CHECK_MS]; neither changes the count.
 */
object RadarLeftOnDecider {

    /** On a [TIMEOUT_STATUSES] ending, a gap at or above this reads as the
     *  radar left on. Frames arrive about every 0.1 s and were never more than
     *  1.35 s apart. */
    const val WALKED_AWAY_GAP_MS = 2_000L

    /** On a [REMOTE_END_STATUSES] ending, a gap under this reads as a
     *  switch-off. */
    const val CLEAN_SWITCH_OFF_GAP_MS = 1_000L

    /** Clean switch-offs a radar must show before the alarm can sound. */
    const val CALIBRATION_SWITCH_OFFS = 3

    /** Radar-off time before the alarm sounds, so a link that drops and
     *  comes straight back near the bike never reaches it. */
    const val CHECK_MS = 20_000L

    /** A radar battery below this can die the way a walk-away looks. */
    const val BATTERY_FLOOR_PCT = 10

    /** A link must have streamed this long before its drop can qualify. */
    const val MIN_STREAMED_MS = 15_000L

    /** Link timeouts: 8 (supervision), 34 (link layer) and 147 (connection),
     *  the last being what both measured walk-aways reported. */
    val TIMEOUT_STATUSES = setOf(8, 34, 147)

    /** The radar ending the link itself: 19, and 257 on the same phone after
     *  an OS update. */
    val REMOTE_END_STATUSES = setOf(19, 257)

    enum class Drop { SWITCHED_OFF, UNCLEAR, WALKED_AWAY }

    /** The drop, from what the radar link measured at its disconnect
     *  callback; null when it measured none (no stream, or a teardown the app
     *  chose) or the status is neither a timeout nor the radar ending it. */
    fun classify(loss: LinkLoss?): Drop? = when {
        loss == null -> null
        loss.status in TIMEOUT_STATUSES -> if (loss.gapMs >= WALKED_AWAY_GAP_MS) Drop.WALKED_AWAY else Drop.UNCLEAR
        loss.status in REMOTE_END_STATUSES -> if (loss.gapMs < CLEAN_SWITCH_OFF_GAP_MS) Drop.SWITCHED_OFF else Drop.UNCLEAR
        else -> null
    }

    /** The radar's clean switch-off count after [drop]: one more for a clean
     *  switch-off, back to 0 for an unclear one, unchanged otherwise. */
    fun switchOffsAfter(drop: Drop?, count: Int): Int = when (drop) {
        Drop.SWITCHED_OFF -> minOf(count + 1, CALIBRATION_SWITCH_OFFS)
        Drop.UNCLEAR -> 0
        else -> count
    }

    /** Whether this off-episode can sound the alarm, decided once at the
     *  drop. [dismissedUntilRide]: the rider dismissed this alarm and has not
     *  ridden since. */
    fun qualifies(
        drop: Drop?,
        cleanSwitchOffs: Int,
        armed: Boolean,
        ridingAtDrop: Boolean,
        radarBatteryPct: Int?,
        streamedMs: Long,
        dismissedUntilRide: Boolean,
    ): Boolean = drop == Drop.WALKED_AWAY &&
        cleanSwitchOffs >= CALIBRATION_SWITCH_OFFS &&
        armed &&
        !ridingAtDrop &&
        (radarBatteryPct == null || radarBatteryPct >= BATTERY_FLOOR_PCT) &&
        streamedMs >= MIN_STREAMED_MS &&
        !dismissedUntilRide

    /** Whether to sound now. Once per episode: [fired] and [dismissed] both
     *  stop it, and so does the dashcam alarm having sounded for the same
     *  episode ([otherAlarmFired]). */
    fun shouldFire(
        enabled: Boolean,
        qualified: Boolean,
        nowMs: Long,
        radarOffSinceMs: Long?,
        adapterOffSinceDrop: Boolean,
        fired: Boolean,
        dismissed: Boolean,
        otherAlarmFired: Boolean,
    ): Boolean {
        val off = radarOffSinceMs ?: return false
        return enabled &&
            qualified &&
            !adapterOffSinceDrop &&
            !fired &&
            !dismissed &&
            !otherAlarmFired &&
            nowMs - off >= CHECK_MS
    }
}
