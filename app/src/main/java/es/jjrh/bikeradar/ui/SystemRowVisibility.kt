// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

/**
 * Pure visibility/state predicates for the home-screen System card, extracted
 * from [SystemCard] so the data-driven "what shows when" contracts are unit-
 * asserted instead of pinned only by Roborazzi golden PNGs. The Composable maps
 * these states to colours and strings; the decisions live here, shared by the
 * rear-radar and front-dashcam rows (previously two identical inline `when`s).
 */

/**
 * Three-state device vocabulary for a System-card row, from the UX converger:
 *  - [NOT_PAIRED] the device is not owned/bonded or its transport is off (grey,
 *    hollow ring, dimmed).
 *  - [LIVE] a recent reading is arriving (green, solid).
 *  - [NO_SIGNAL] paired but no fresh reading (amber).
 */
enum class DeviceLinkState { NOT_PAIRED, LIVE, NO_SIGNAL }

/**
 * Classify a rear-radar / front-dashcam row.
 *
 * @param linked the device is owned/bonded AND its transport is up - radar:
 *   Bluetooth on and bonded; dashcam: owned and paired.
 * @param fresh a reading arrived inside the row's freshness window.
 */
fun deviceLinkState(linked: Boolean, fresh: Boolean): DeviceLinkState = when {
    !linked -> DeviceLinkState.NOT_PAIRED
    fresh -> DeviceLinkState.LIVE
    else -> DeviceLinkState.NO_SIGNAL
}

/** A not-yet-paired row renders dimmed (the only state that mutes its text). */
val DeviceLinkState.muted: Boolean get() = this == DeviceLinkState.NOT_PAIRED

/** A not-yet-paired row shows a hollow status dot rather than a solid one. */
val DeviceLinkState.hollow: Boolean get() = this == DeviceLinkState.NOT_PAIRED

/**
 * The eBike battery chip shows the live state-of-charge only while the
 * proprietary stream is being received. A stale SoC carried over from a prior
 * session is hidden rather than shown as if current. Returns the SoC to display,
 * or null to hide the chip.
 */
fun ebikeBatteryChipSoc(receiving: Boolean, soc: Int?): Int? = if (receiving) soc else null
