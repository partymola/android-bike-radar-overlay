// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Decides when to fire the radar critical-battery audible cue. Pure
 * function; the caller (the BikeRadarService overlay loop) owns the
 * [Decision.lastCueMs] state and threads it back in on the next tick.
 *
 * Why it exists: the rear radar is the rider's only rear-traffic
 * awareness, so a CRITICAL radar battery warrants an in-ride audible
 * warning - the one battery exception to the otherwise post-ride-only rule
 * for bike-state cues. A low-but-not-critical battery keeps using
 * the silent visual glyph (driven by `batteryLowThresholdPct`); this cue is
 * only for the critical level.
 *
 * Behaviour: fire once when the radar battery first drops below
 * [criticalPct] while connected and the reading is fresh, then repeat no
 * more often than every [cadenceMs]. The latch resets (so the next drop
 * re-fires immediately) whenever the battery is at/above the threshold, the
 * reading is stale, or there is no reading at all - which also covers the
 * radar disconnecting.
 *
 * Radar-only by contract: the caller passes the RADAR's battery, never the
 * front camera/light's. The dashcam battery stays a post-ride concern.
 */
object CriticalBatteryDecider {

    data class Decision(val fire: Boolean, val lastCueMs: Long?)

    fun decide(
        pct: Int?,
        fresh: Boolean,
        nowMs: Long,
        criticalPct: Int,
        cadenceMs: Long,
        lastCueMs: Long?,
    ): Decision {
        val critical = pct != null && fresh && pct < criticalPct
        if (!critical) return Decision(fire = false, lastCueMs = null)
        val due = lastCueMs == null || nowMs - lastCueMs >= cadenceMs
        return if (due) Decision(fire = true, lastCueMs = nowMs)
        else Decision(fire = false, lastCueMs = lastCueMs)
    }

    /**
     * L8 pre-flight-cue gate. A device is eligible for the low-battery
     * heads-up unless it is the rear radar AND already in the critical band
     * (`pct < criticalPct`) - that case is covered by the repeating
     * radar-critical cue, so skipping it here avoids a double cue. The radar
     * in the low-but-not-critical band, and any non-radar device (e.g. the
     * dashcam) at any low level, stay eligible.
     */
    fun preflightEligible(slug: String, pct: Int, radarSlug: String?, criticalPct: Int): Boolean =
        !(slug == radarSlug && pct < criticalPct)
}
