// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.annotation.StringRes
import es.jjrh.bikeradar.EBikeStage
import es.jjrh.bikeradar.HaStatus
import es.jjrh.bikeradar.R

/**
 * The word each surface uses for "is this device delivering right now".
 *
 * One mapping per device, read by the home System card, the Settings
 * quick-status chips, the Settings Connections subtitles and each device's
 * own screen. The System rows navigate, so the same camera was being called
 * "No signal", "Not seen", "Paired" and nothing at all on four screens a
 * rider crosses in two taps.
 *
 * The functions return a resource id rather than a string so they stay
 * outside composition and can be unit-asserted; the caller resolves it.
 *
 * Gender is why radar, camera and eBike each get their own function rather
 * than sharing one: Spanish inflects "Activo" for el radar, "Activa" for la
 * cámara and la eBike. English collapses all three, so nothing in the en
 * strings shows the difference and a shared mapping looks correct.
 */

/** Rear radar. Masculine forms in Spanish. */
@StringRes
fun radarLinkLabel(state: DeviceLinkState): Int = when (state) {
    DeviceLinkState.NOT_PAIRED -> R.string.device_status_not_paired
    DeviceLinkState.LIVE -> R.string.device_status_live
    DeviceLinkState.LIMITED -> R.string.device_status_limited
    DeviceLinkState.CONNECTING -> R.string.device_status_connecting
    DeviceLinkState.NO_SIGNAL -> R.string.device_status_no_signal
}

/**
 * Front camera. Feminine forms in Spanish.
 *
 * [DeviceLinkState.LIMITED] and [DeviceLinkState.CONNECTING] are mapped
 * rather than absorbed by an else: the camera is scored off battery adverts
 * alone today and reaches neither, and wiring a light-link signal into it
 * should be a deliberate edit here rather than an else branch quietly
 * picking up a new state in the wrong gender.
 */
@StringRes
fun dashcamLinkLabel(state: DeviceLinkState): Int = when (state) {
    DeviceLinkState.NOT_PAIRED -> R.string.device_status_cam_not_paired
    DeviceLinkState.LIVE -> R.string.device_status_cam_live
    DeviceLinkState.LIMITED -> R.string.device_status_cam_limited
    DeviceLinkState.CONNECTING -> R.string.device_status_connecting
    DeviceLinkState.NO_SIGNAL -> R.string.device_status_no_signal
}

/**
 * eBike. Feminine forms in Spanish.
 *
 * Not a [DeviceLinkState]: the two ways this link fails before Flow is even
 * involved are ours, not Flow's, and telling a rider to open Flow for a
 * missing Bluetooth permission sends them where nothing can help. So the
 * not-receiving branch names which of the three it is.
 */
@StringRes
fun ebikeStatusLabel(receiving: Boolean, stage: EBikeStage): Int = when {
    receiving -> R.string.device_status_ebike_live
    stage == EBikeStage.NOT_PERMITTED -> R.string.device_status_ebike_no_permission
    stage == EBikeStage.NO_BONDED_BIKE -> R.string.device_status_ebike_not_bonded
    else -> R.string.device_status_waiting_flow
}

/** Home Assistant. */
@StringRes
fun haStatusLabel(status: HaStatus): Int = when (status) {
    HaStatus.NOT_CONFIGURED -> R.string.device_status_ha_not_configured
    HaStatus.CONFIGURED -> R.string.device_status_ha_configured
    HaStatus.READY -> R.string.device_status_ha_ready
    HaStatus.UNREACHABLE -> R.string.device_status_ha_unreachable
}
