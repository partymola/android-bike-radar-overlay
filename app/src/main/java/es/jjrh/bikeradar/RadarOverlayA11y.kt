// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context

/**
 * Spoken accessibility summary for [RadarOverlayView], split into a pure
 * model + a resource resolver so the user-facing copy is translatable.
 *
 * [buildOverlayA11yModel] is pure over framework-free state (no Context),
 * so it stays JVM-unit-testable without the Canvas/layoutlib stack. The
 * view feeds its current state in, then resolves the model to a spoken
 * string with [overlayA11yDescription] (which needs a Context for the
 * string resources + the "N vehicles" quantity string) and assigns it to
 * `contentDescription` (only when an a11y service is listening).
 *
 * Mirrors what the rider sees: clear road versus active-vehicle count plus
 * the nearest distance, then any dashcam/battery warning. "Active" excludes
 * targets that have overtaken the rider ([Vehicle.isBehind]).
 */
internal data class OverlayA11yModel(
    /** Count of active (not-behind) vehicles; 0 means the road is clear. */
    val activeCount: Int,
    /** Nearest active vehicle's distance in metres, or null when none. */
    val nearestMetres: Int?,
    val dashcamStatus: DashcamStatus,
    val batteryLow: Boolean,
)

internal fun buildOverlayA11yModel(
    state: RadarState,
    dashcamStatus: DashcamStatus,
    batteryLow: Boolean,
): OverlayA11yModel {
    val active = state.vehicles.filter { !it.isBehind }
    return OverlayA11yModel(
        activeCount = active.size,
        nearestMetres = active.minByOrNull { it.distanceM }?.distanceM,
        dashcamStatus = dashcamStatus,
        batteryLow = batteryLow,
    )
}

/** Resolve an [OverlayA11yModel] to the spoken contentDescription string.
 *  The leading separators are added here so the resource strings stay free
 *  of leading/trailing whitespace (which Android resource parsing strips). */
internal fun Context.overlayA11yDescription(model: OverlayA11yModel): String {
    val sb = StringBuilder(getString(R.string.overlay_a11y_prefix)).append(' ')
    if (model.activeCount == 0) {
        sb.append(getString(R.string.overlay_a11y_road_clear))
    } else if (model.nearestMetres != null) {
        sb.append(
            resources.getQuantityString(
                R.plurals.overlay_a11y_vehicles_nearest,
                model.activeCount,
                model.activeCount,
                model.nearestMetres,
            ),
        )
    } else {
        sb.append(
            resources.getQuantityString(
                R.plurals.overlay_a11y_vehicles,
                model.activeCount,
                model.activeCount,
            ),
        )
    }
    when (model.dashcamStatus) {
        DashcamStatus.Dropped -> sb.append(' ').append(getString(R.string.overlay_a11y_dashcam_dropped))
        DashcamStatus.Missing -> sb.append(' ').append(getString(R.string.overlay_a11y_dashcam_missing))
        DashcamStatus.Searching -> sb.append(' ').append(getString(R.string.overlay_a11y_dashcam_searching))
        DashcamStatus.Ok -> {}
    }
    if (model.batteryLow) sb.append(' ').append(getString(R.string.overlay_a11y_low_battery))
    return sb.toString()
}
