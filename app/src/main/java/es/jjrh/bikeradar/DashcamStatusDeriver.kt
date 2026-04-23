// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure, Android-free derivation of the dashcam warning state.
 *
 * Split out of the service composer so it can be unit-tested on the JVM
 * and so the precedence rules live in exactly one place.
 *
 *  Ok         — user opted out, no device selected, or dashcam is fresh.
 *  Searching  — selected device not seen yet, still inside cold-start grace.
 *  Missing    — selected device never seen this session, past grace.
 *  Dropped    — was seen this session and has gone stale.
 */
object DashcamStatusDeriver {

    /** Presence state for the currently-selected dashcam. */
    data class Config(
        val warnWhenOff: Boolean,
        val selectedSlug: String?,
    )

    fun derive(
        config: Config,
        entries: Map<String, BatteryEntry>,
        nowMs: Long,
        sessionStartMs: Long,
        seenThisSession: Boolean,
        freshMs: Long,
        coldStartMs: Long,
    ): DashcamStatus {
        if (!config.warnWhenOff) return DashcamStatus.Ok
        val slug = config.selectedSlug ?: return DashcamStatus.Ok

        val entry = entries[slug]
        val lastSeenMs = entry?.readAtMs ?: 0L
        val fresh = lastSeenMs > 0L && nowMs - lastSeenMs < freshMs
        if (fresh) return DashcamStatus.Ok

        if (seenThisSession) return DashcamStatus.Dropped
        if (nowMs - sessionStartMs < coldStartMs) return DashcamStatus.Searching
        return DashcamStatus.Missing
    }
}
