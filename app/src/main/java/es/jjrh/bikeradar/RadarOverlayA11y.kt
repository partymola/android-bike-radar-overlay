// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Builds the spoken accessibility summary for [RadarOverlayView]. Pure
 * function over framework-free state so it can be unit-tested without the
 * Canvas/layoutlib stack that keeps `RadarOverlayViewTest` out of CI; the
 * view just feeds its current state in and assigns the result to
 * `contentDescription` (only when an a11y service is listening).
 *
 * Mirrors what the rider sees: clear road versus active-vehicle count plus
 * the nearest distance, then any dashcam/battery warning. "Active" excludes
 * targets that have overtaken the rider ([Vehicle.isBehind]).
 */
internal fun buildOverlayA11ySummary(
    state: RadarState,
    dashcamStatus: DashcamStatus,
    batteryLow: Boolean,
): String {
    val sb = StringBuilder("Bike radar overlay. ")
    val active = state.vehicles.filter { !it.isBehind }
    if (active.isEmpty()) {
        sb.append("Road clear.")
    } else {
        sb.append(active.size)
        sb.append(if (active.size == 1) " vehicle" else " vehicles")
        active.minByOrNull { it.distanceM }?.let {
            sb.append(", nearest ").append(it.distanceM).append(" metres")
        }
        sb.append(".")
    }
    when (dashcamStatus) {
        DashcamStatus.Dropped -> sb.append(" Dashcam connection lost.")
        DashcamStatus.Missing -> sb.append(" Dashcam not found.")
        DashcamStatus.Searching -> sb.append(" Searching for dashcam.")
        DashcamStatus.Ok -> {}
    }
    if (batteryLow) sb.append(" Low battery.")
    return sb.toString()
}
