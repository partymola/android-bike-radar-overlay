// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.annotation.StringRes
import es.jjrh.bikeradar.R

/**
 * Pure, Android-free derivation of the home-screen status card.
 *
 * Inputs are collapsed into a plain data class so the deriver can be
 * unit-tested on the JVM without pulling in RadarStateBus, BluetoothManager,
 * Compose runtime, or the real clock.
 */
enum class MainStatusTone { Good, Warn, Error, Info, Neutral }

/** Icon identifiers; resolved to concrete `ImageVector` at render time so
 *  the deriver stays Compose-free. */
enum class MainStatusIcon {
    PlayCircle,
    PauseCircle,
    BluetoothDisabled,
    CheckCircle,
    Warning,
    Sensors,
}

/**
 * Rendered status card. Carries the resolved display strings so the leaf
 * Composables - and their snapshot tests - need no resources to draw it.
 */
data class MainStatus(
    val icon: MainStatusIcon,
    val tone: MainStatusTone,
    val headline: String,
    val subtitle: String? = null,
)

/**
 * Resource-ID form of [MainStatus], produced by [MainStatusDeriver]. The
 * deriver never touches a `Context` or `Resources`; it picks the state and
 * the string IDs, and the Composable resolves [headlineRes] / [subtitleRes]
 * - substituting [headlineArgs] / [subtitleArgs] - into a [MainStatus] via
 * `stringResource`. Keeping the user-facing copy out of the pure logic is
 * what lets the deriver stay JVM-testable and the strings translatable.
 */
data class MainStatusModel(
    val icon: MainStatusIcon,
    val tone: MainStatusTone,
    @get:StringRes val headlineRes: Int,
    val headlineArgs: List<String> = emptyList(),
    @get:StringRes val subtitleRes: Int? = null,
    val subtitleArgs: List<String> = emptyList(),
)

data class MainStatusInputs(
    val firstRunComplete: Boolean,
    val pausedUntilEpochMs: Long,
    val hasBond: Boolean,
    val radarFresh: Boolean,
    val haErrorRecent: Boolean,
    val dashcamOwned: Boolean,
    val dashcamWarnWhenOff: Boolean,
    val dashcamFresh: Boolean,
    val dashcamDisplayName: String?,
    /** True when the user has the foreground service enabled (default).
     *  When false the rider has explicitly turned off all radar tracking
     *  via Settings; the deriver surfaces a "Service stopped" status with
     *  a Start CTA so the rider can re-enable it from the home screen
     *  without digging through settings. Defaults to true to preserve
     *  existing call-site behaviour. */
    val serviceEnabled: Boolean = true,
    /** True when the OS Bluetooth adapter is enabled. False is a distinct
     *  state from "radar not paired": the radar bond persists across BT
     *  toggles, so the right prompt is "turn BT back on", not "go pair".
     *  Defaults to true so existing call-sites and tests keep their
     *  pre-existing behaviour. */
    val bluetoothEnabled: Boolean = true,
)

object MainStatusDeriver {

    /**
     * Priority order, first match wins:
     *  1. First-run setup
     *  2. Service stopped (user toggled the foreground service off)
     *  3. User-paused
     *  4. Bluetooth disabled (adapter off at the OS level)
     *  5. Radar not paired
     *  6. Radar live but dashcam off  (rider-safety beats HA connectivity)
     *  7. Radar live but HA unreachable
     *  8. Radar live, all good
     *  9. Paired but radar stale
     *
     * DashcamOff is intentionally ranked above HaDown: HA is background
     * telemetry, the dashcam is the rear-view recording the rider needs.
     * If both fire simultaneously the dashcam warning wins.
     *
     * Service-stopped beats Paused/BtOff/NotPaired because if the service
     * isn't running there's no scanning happening at all — telling the
     * rider the radar is "not paired" when the real cause is that they
     * turned the whole thing off would be misdirection.
     *
     * BtOff beats NotPaired because the bond persists across BT toggles;
     * the right prompt is "turn BT back on", not "go pair".
     *
     * [formatTime] formats an epoch-ms instant into the user's wall clock
     * (injected so the deriver needs no clock/locale of its own); its
     * result becomes the [MainStatusModel.headlineArgs] substitution for
     * the "Paused until …" headline.
     */
    fun derive(
        inputs: MainStatusInputs,
        nowMs: Long,
        formatTime: (Long) -> String,
    ): MainStatusModel {
        if (!inputs.firstRunComplete) {
            return MainStatusModel(
                icon = MainStatusIcon.PlayCircle,
                tone = MainStatusTone.Good,
                headlineRes = R.string.main_status_setup_title,
                subtitleRes = R.string.main_status_setup_sub,
            )
        }
        if (!inputs.serviceEnabled) {
            return MainStatusModel(
                icon = MainStatusIcon.PlayCircle,
                tone = MainStatusTone.Neutral,
                headlineRes = R.string.main_status_service_off_title,
                subtitleRes = R.string.main_status_service_off_sub,
            )
        }
        if (nowMs < inputs.pausedUntilEpochMs) {
            return MainStatusModel(
                icon = MainStatusIcon.PauseCircle,
                tone = MainStatusTone.Info,
                headlineRes = R.string.main_status_paused_title,
                headlineArgs = listOf(formatTime(inputs.pausedUntilEpochMs)),
                subtitleRes = R.string.main_status_paused_sub,
            )
        }
        if (!inputs.bluetoothEnabled) {
            return MainStatusModel(
                icon = MainStatusIcon.BluetoothDisabled,
                tone = MainStatusTone.Warn,
                headlineRes = R.string.main_status_bt_off_title,
                subtitleRes = R.string.main_status_bt_off_sub,
            )
        }
        if (!inputs.hasBond) {
            return MainStatusModel(
                icon = MainStatusIcon.BluetoothDisabled,
                tone = MainStatusTone.Error,
                headlineRes = R.string.main_status_not_paired_title,
                subtitleRes = R.string.main_status_not_paired_sub,
            )
        }
        if (inputs.radarFresh) {
            val warnEnabled = inputs.dashcamOwned && inputs.dashcamWarnWhenOff
            if (warnEnabled && !inputs.dashcamFresh) {
                val name = inputs.dashcamDisplayName
                return MainStatusModel(
                    icon = MainStatusIcon.Warning,
                    tone = MainStatusTone.Warn,
                    headlineRes = R.string.main_status_dashcam_off_title,
                    subtitleRes =
                    if (name != null) {
                        R.string.main_status_dashcam_off_sub
                    } else {
                        R.string.main_status_dashcam_off_sub_generic
                    },
                    subtitleArgs = listOfNotNull(name),
                )
            }
            if (inputs.haErrorRecent) {
                return MainStatusModel(
                    icon = MainStatusIcon.CheckCircle,
                    tone = MainStatusTone.Good,
                    headlineRes = R.string.main_status_live_title,
                    subtitleRes = R.string.main_status_live_ha_down_sub,
                )
            }
            val subtitleRes =
                if (inputs.dashcamOwned && inputs.dashcamFresh) {
                    R.string.main_status_live_dashcam_on_sub
                } else {
                    null
                }
            return MainStatusModel(
                icon = MainStatusIcon.CheckCircle,
                tone = MainStatusTone.Good,
                headlineRes = R.string.main_status_live_title,
                subtitleRes = subtitleRes,
            )
        }
        return MainStatusModel(
            icon = MainStatusIcon.Sensors,
            tone = MainStatusTone.Neutral,
            headlineRes = R.string.main_status_waiting_title,
            subtitleRes = R.string.main_status_waiting_sub,
        )
    }
}
