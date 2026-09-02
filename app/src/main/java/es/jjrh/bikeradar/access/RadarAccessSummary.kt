// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

/**
 * What the settings row says about apps the rider has allowed.
 *
 * Pure, so the wording and the counting are testable without a screen. The
 * distinction that matters is between no app at all and an app allowed to
 * change the tail light: those are different levels of trust and the row
 * should not read the same for both.
 */
enum class RadarAccessSummary {
    /** No app has been allowed anything. */
    NONE,

    /** At least one app can see the radar; none can act on the hardware. */
    READING,

    /** At least one app can change the tail light or hide the overlay. */
    CONTROLLING,
    ;

    companion object {
        fun of(grants: List<RadarGrant>): RadarAccessSummary = when {
            grants.any { it.control } -> CONTROLLING
            grants.any { it.read } -> READING
            else -> NONE
        }
    }
}
