// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Transient dashcam presence state as seen by the overlay.
 *
 * Rendered at the overlay's bottom-centre warning slot. Red (Dropped) is
 * reserved for the genuinely-surprising mid-ride case where the dashcam
 * was live and went dark; Missing uses amber as a persistent "forgot to
 * turn it on" nag. Searching is the short cold-start grace window before
 * we know which of the two applies.
 */
sealed interface DashcamStatus {
    data object Ok : DashcamStatus
    data object Searching : DashcamStatus
    data object Missing : DashcamStatus
    data object Dropped : DashcamStatus
}
