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
 * Device vocabulary for a System-card row:
 *  - [NOT_PAIRED] the device is not owned/bonded or its transport is off (grey,
 *    hollow ring, dimmed).
 *  - [LIVE] a recent reading is arriving (green, solid).
 *  - [LIMITED] readings are arriving, but from a source that cannot supply
 *    everything the app advertises (amber).
 *  - [CONNECTING] the app is actively working the link and has not got data
 *    yet (amber).
 *  - [NO_SIGNAL] paired, not connecting, no fresh reading (amber).
 */
enum class DeviceLinkState { NOT_PAIRED, LIVE, LIMITED, CONNECTING, NO_SIGNAL }

/**
 * Classify a rear-radar / front-dashcam row.
 *
 * [LIMITED] and [CONNECTING] exist because the row was telling the rider two
 * false things, on the one surface whose whole job is to be trusted at a
 * glance.
 *
 * Without [CONNECTING], a radar the app connects to and fails the setup
 * sequence against reads "No signal" - and the report that comes back is
 * "never in range" about a radar being talked to every second or so. That is
 * the same lie [RadarConnStatusDeriver] was written to remove from the Settings
 * card; this row simply never consumed it, so the two surfaces disagreed.
 *
 * Without [LIMITED], a radar on the range-only legacy stream reads a green
 * "Live" identical to a healthy one, while the urgent warning and close-pass
 * detection are held shut. Green is the app's "you are covered" signal, and it
 * must not appear over a link that cannot raise the urgent cue.
 *
 * @param linked the device is owned/bonded AND its transport is up - radar:
 *   Bluetooth on and bonded; dashcam: owned and paired.
 * @param fresh a reading arrived inside the row's freshness window.
 * @param limited the source is delivering, but cannot supply the full feature
 *   set. Only meaningful alongside [fresh].
 * @param connecting the app currently holds or is establishing a link but has
 *   no fresh reading yet.
 */
fun deviceLinkState(
    linked: Boolean,
    fresh: Boolean,
    limited: Boolean = false,
    connecting: Boolean = false,
): DeviceLinkState = when {
    !linked -> DeviceLinkState.NOT_PAIRED
    fresh && limited -> DeviceLinkState.LIMITED
    fresh -> DeviceLinkState.LIVE
    connecting -> DeviceLinkState.CONNECTING
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

/** Which System-card row was tapped. */
enum class SystemRowTarget { RADAR, DASHCAM, EBIKE, HA }

/**
 * Where a System-card row navigates.
 *
 * A pure function rather than a `navigate("...")` inline in the Composable, so
 * the destinations are asserted by a unit test instead of only by tapping the
 * app: a wrong route here sends a rider to the wrong screen and nothing
 * compiles differently. The strings must match the routes registered in
 * `MainActivity`, which is what [SystemRowRouteTest] checks by reading them
 * back out of that file.
 *
 * The radar row goes to the DEVICE screen, not the alert-tuning one: a rider
 * tapping a row that says "Not in range" wants the pairing and connection
 * state, not the beep thresholds.
 */
fun systemRowRoute(target: SystemRowTarget): String = when (target) {
    SystemRowTarget.RADAR -> "settings/radar-device"
    SystemRowTarget.DASHCAM -> "settings/dashcam"
    SystemRowTarget.EBIKE -> "settings/ebike"
    SystemRowTarget.HA -> "settings/ha"
}
