// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/** How the Settings permissions row summarises what the rider has granted. */
enum class PermissionsSummary {
    /** A permission the app needs to function is missing. */
    ACTION_NEEDED,

    /** Everything required is granted, but optional ones are outstanding. */
    PARTIALLY_GRANTED,

    /** Every permission the app asks for is granted. */
    ALL_GRANTED,
}

/**
 * Summarise the permissions row.
 *
 * The row used to pick between "needs action" and "all granted" on the
 * required-only count while printing the total count, so a fresh install with
 * both required permissions granted and the overlay and location ones not read
 * "All granted (2 of 4)" - a sentence contradicted by its own numbers, in a row
 * whose whole job is telling the rider whether anything is outstanding. The
 * overlay is the app's primary surface, so that is not a cosmetic miscount.
 */
object PermissionsSummaryDeriver {
    fun derive(grantedCount: Int, requiredMissing: Int, total: Int): PermissionsSummary = when {
        requiredMissing > 0 -> PermissionsSummary.ACTION_NEEDED
        grantedCount >= total -> PermissionsSummary.ALL_GRANTED
        else -> PermissionsSummary.PARTIALLY_GRANTED
    }
}
