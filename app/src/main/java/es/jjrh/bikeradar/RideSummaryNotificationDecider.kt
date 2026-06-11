// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Decides when the post-ride summary notification fires and when the
 * per-ride stats begin a new ride. Pure JVM; the service evaluates it
 * from the walk-away tick.
 *
 * Model:
 *  - **Ride end** is "the radar has been off for [POST_DWELL_MS]". The
 *    radar powers down minutes after the bike stops moving, so a
 *    sustained off-episode is the most reliable radar-only end-of-ride
 *    signal; the dwell absorbs mid-ride drops, which reconnect well
 *    inside it. One post per off-episode - a later reconnect + new
 *    off-episode replaces the notification with updated numbers.
 *  - **Meaningful-ride gate** ([isMeaningful]) keeps bench connects and
 *    doorstep blips silent: a ride must have accumulated real exposure,
 *    distance, or at least one close pass before a summary is worth a
 *    notification.
 *  - **Ride start** is a reconnect after an off-gap of at least the
 *    caller-supplied threshold (the service passes the Settings "idle
 *    radar after" boundary - the same gap the reconnect loop already
 *    treats as "the bike is parked"). Shorter gaps (shop stop, cafe)
 *    continue the same ride and its stats.
 *
 * This is a post-ride informational surface by design: silent channel,
 * no in-ride component. The notification is also bridged to a paired
 * watch by the platform for free.
 */
object RideSummaryNotificationDecider {

    /** Radar-off dwell before the summary posts. Long enough that a
     *  mid-ride BLE drop (reconnect loop caps at single-digit seconds,
     *  long-offline relaxation only starts at 30 min) never fires it;
     *  short enough that the summary is on the phone by the time the
     *  rider has parked and pulled out their phone. */
    const val POST_DWELL_MS = 180_000L

    /** Exposure floor: rides shorter than this with no other signal are
     *  treated as bench tests / doorstep blips. */
    const val MIN_EXPOSURE_SECONDS = 300L

    /** Distance floor that qualifies a ride on its own (short rides with
     *  the radar streaming the whole way are real rides). */
    const val MIN_DISTANCE_KM = 1.0f

    fun isMeaningful(snap: RideStatsSnapshot): Boolean = snap.exposureSeconds >= MIN_EXPOSURE_SECONDS ||
        snap.distanceRiddenKm >= MIN_DISTANCE_KM ||
        snap.closePassCount > 0

    fun shouldPost(
        radarOffSinceMs: Long?,
        nowMs: Long,
        alreadyPosted: Boolean,
        snap: RideStatsSnapshot,
    ): Boolean = radarOffSinceMs != null &&
        !alreadyPosted &&
        nowMs - radarOffSinceMs >= POST_DWELL_MS &&
        isMeaningful(snap)

    /** A reconnect after at least [longOffThresholdMs] of radar silence
     *  starts a new ride (fresh stats). */
    fun shouldStartNewRide(offDurationMs: Long, longOffThresholdMs: Long): Boolean = offDurationMs >= longOffThresholdMs
}
