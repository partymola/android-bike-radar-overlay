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

    /** Both were left on: the camera was heard just before the radar went. */
    RADAR_AND_DASHCAM,
}

/**
 * Pure decisions for the radar-left-on alarm: the rider walked out of range
 * with the radar still on, with or without the front camera.
 *
 * The one signal is how long before Android's disconnect callback the radar's
 * stream stopped ([Drop]). A radar switched off ends its stream within about
 * a second of the callback; one still on and out of range goes silent and the
 * link times out 3 to 4 s later. Measured on one phone and one radar model,
 * which is why a radar has to show [CALIBRATION_SWITCH_OFFS] clean
 * switch-offs before the alarm can sound for it: a phone or radar whose
 * switch-offs look like walk-aways then stays silent rather than sounding at
 * every ride end. A gap between [CLEAN_SWITCH_OFF_GAP_MS] and
 * [WALKED_AWAY_GAP_MS] counts as neither, so a pair in that band stays
 * silent too (`RadarLeftOnDeciderTest`).
 *
 * Mid-ride link losses look the same as a walk-away, so the drop must also
 * come with the alarm armed ([WalkAwayArmingGate]) and no riding seen just
 * before it. A radar battery under [BATTERY_FLOOR_PCT] can end the stream
 * the same way, and does not qualify. Nor does an ending the phone produced
 * ([PHONE_SIDE_STATUSES]), which counts toward calibration neither.
 */
object RadarLeftOnDecider {

    /** A gap at or above this reads as the radar left on. Frames arrive
     *  about every 0.1 s and were never more than 1.35 s apart. */
    const val WALKED_AWAY_GAP_MS = 2_000L

    /** A gap under this reads as a switch-off. */
    const val CLEAN_SWITCH_OFF_GAP_MS = 1_000L

    /** Clean switch-offs a radar must show before the alarm can sound. */
    const val CALIBRATION_SWITCH_OFFS = 3

    /** Radar-off time before the alarm sounds, so a link that drops and
     *  comes straight back near the bike never reaches it. */
    const val CHECK_MS = 20_000L

    /** A radar battery below this can die the way a walk-away looks. */
    const val BATTERY_FLOOR_PCT = 10

    /** Disconnect statuses the phone itself produces (0 success, 22 the local
     *  host ending the link), as when Bluetooth is switched off or the bond
     *  is removed. Such an ending says nothing about the radar. */
    val PHONE_SIDE_STATUSES = setOf(0, 22)

    enum class Drop { SWITCHED_OFF, UNCLEAR, WALKED_AWAY }

    /** The drop, from what the radar link measured at its disconnect
     *  callback, or null when it measured none (no stream on that link, or a
     *  teardown the app chose) or the phone ended the link. */
    fun classify(loss: LinkLoss?): Drop? = when {
        loss == null || loss.status in PHONE_SIDE_STATUSES -> null
        loss.gapMs < CLEAN_SWITCH_OFF_GAP_MS -> Drop.SWITCHED_OFF
        loss.gapMs >= WALKED_AWAY_GAP_MS -> Drop.WALKED_AWAY
        else -> Drop.UNCLEAR
    }

    /** The radar's clean switch-off count after [drop]. */
    fun switchOffsAfter(drop: Drop?, count: Int): Int = if (drop == Drop.SWITCHED_OFF) minOf(count + 1, CALIBRATION_SWITCH_OFFS) else count

    /** Whether this off-episode can sound the alarm, decided once at the drop. */
    fun qualifies(
        drop: Drop?,
        cleanSwitchOffs: Int,
        armed: Boolean,
        ridingAtDrop: Boolean,
        radarBatteryPct: Int?,
    ): Boolean = drop == Drop.WALKED_AWAY &&
        cleanSwitchOffs >= CALIBRATION_SWITCH_OFFS &&
        armed &&
        !ridingAtDrop &&
        (radarBatteryPct == null || radarBatteryPct >= BATTERY_FLOOR_PCT)

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
