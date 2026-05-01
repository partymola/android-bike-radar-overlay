// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

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

data class MainStatus(
    val icon: MainStatusIcon,
    val tone: MainStatusTone,
    val headline: String,
    val subtitle: String? = null,
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
     */
    fun derive(
        inputs: MainStatusInputs,
        nowMs: Long,
        formatTime: (Long) -> String,
    ): MainStatus {
        if (!inputs.firstRunComplete) {
            return MainStatus(
                icon = MainStatusIcon.PlayCircle,
                tone = MainStatusTone.Good,
                headline = "Let's set up your radar",
                subtitle = "Tap Settings to begin",
            )
        }
        if (!inputs.serviceEnabled) {
            return MainStatus(
                icon = MainStatusIcon.PlayCircle,
                tone = MainStatusTone.Neutral,
                headline = "Service stopped",
                subtitle = "Tap Start to begin",
            )
        }
        if (nowMs < inputs.pausedUntilEpochMs) {
            return MainStatus(
                icon = MainStatusIcon.PauseCircle,
                tone = MainStatusTone.Info,
                headline = "Paused until ${formatTime(inputs.pausedUntilEpochMs)}",
                subtitle = "Alerts are silenced",
            )
        }
        if (!inputs.bluetoothEnabled) {
            return MainStatus(
                icon = MainStatusIcon.BluetoothDisabled,
                tone = MainStatusTone.Warn,
                headline = "Bluetooth is off",
                subtitle = "Radar is offline",
            )
        }
        if (!inputs.hasBond) {
            return MainStatus(
                icon = MainStatusIcon.BluetoothDisabled,
                tone = MainStatusTone.Error,
                headline = "Radar not paired",
                subtitle = "Pair in Settings",
            )
        }
        if (inputs.radarFresh) {
            val warnEnabled = inputs.dashcamOwned && inputs.dashcamWarnWhenOff
            if (warnEnabled && !inputs.dashcamFresh) {
                return MainStatus(
                    icon = MainStatusIcon.Warning,
                    tone = MainStatusTone.Warn,
                    headline = "Radar live, dashcam off",
                    subtitle = "Turn on your ${inputs.dashcamDisplayName ?: "dashcam"}",
                )
            }
            if (inputs.haErrorRecent) {
                return MainStatus(
                    icon = MainStatusIcon.CheckCircle,
                    tone = MainStatusTone.Good,
                    headline = "Radar live",
                    subtitle = "Home Assistant unreachable",
                )
            }
            val subtitle = if (inputs.dashcamOwned && inputs.dashcamFresh) "Dashcam on" else null
            return MainStatus(
                icon = MainStatusIcon.CheckCircle,
                tone = MainStatusTone.Good,
                headline = "Radar live",
                subtitle = subtitle,
            )
        }
        return MainStatus(
            icon = MainStatusIcon.Sensors,
            tone = MainStatusTone.Neutral,
            headline = "Waiting for radar",
            subtitle = "Turn on your radar",
        )
    }
}
