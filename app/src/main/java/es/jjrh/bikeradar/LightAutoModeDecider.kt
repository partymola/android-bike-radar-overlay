// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Pure decision for a time-of-day light auto-mode: given the current time and
 * today's sunrise/sunset, decide which phase (day/night) to apply now and
 * when/what to flip to next.
 *
 * Mode-type-agnostic - the caller maps [Phase.DAY] to its day mode and
 * [Phase.NIGHT] to its night mode - so the radar tail light (and, later, the
 * dashcam light) can share it. [isNight] is supplied by the caller (computed
 * via [SunsetCalculator.isNight]) rather than recomputed here, so this decider
 * stays a tiny pure function with no clock dependency and the scheduling
 * boundary logic is unit-testable in isolation.
 */
object LightAutoModeDecider {
    enum class Phase { DAY, NIGHT }

    /**
     * @property initial phase to apply now, or null when an override is active
     *   (the caller should then set nothing).
     * @property flipAtMs epoch-ms of the next solar transition to schedule, or
     *   null if there is none upcoming (or override active).
     * @property flipTo phase to apply at [flipAtMs].
     */
    data class Plan(val initial: Phase?, val flipAtMs: Long?, val flipTo: Phase?)

    fun plan(
        nowMs: Long,
        sunriseMs: Long?,
        sunsetMs: Long?,
        isNight: Boolean,
        overrideActive: Boolean,
    ): Plan {
        if (overrideActive) return Plan(initial = null, flipAtMs = null, flipTo = null)
        val initial = if (isNight) Phase.NIGHT else Phase.DAY
        return when {
            // Daytime with sunset still ahead -> flip to night at sunset.
            !isNight && sunsetMs != null && nowMs < sunsetMs ->
                Plan(initial, sunsetMs, Phase.NIGHT)
            // Night-time but before today's sunrise (i.e. pre-dawn, not post-
            // sunset) -> flip to day at sunrise.
            isNight && sunriseMs != null && nowMs < sunriseMs ->
                Plan(initial, sunriseMs, Phase.DAY)
            else -> Plan(initial, null, null)
        }
    }
}
