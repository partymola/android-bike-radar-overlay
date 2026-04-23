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
)

object MainStatusDeriver {

    /**
     * Priority order, first match wins:
     *  1. First-run setup
     *  2. User-paused
     *  3. Radar not paired
     *  4. Radar live but dashcam off  (rider-safety beats HA connectivity)
     *  5. Radar live but HA unreachable
     *  6. Radar live, all good
     *  7. Paired but radar stale
     *
     * DashcamOff is intentionally ranked above HaDown: HA is background
     * telemetry, the dashcam is the rear-view recording the rider needs.
     * If both fire simultaneously the dashcam warning wins.
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
        if (nowMs < inputs.pausedUntilEpochMs) {
            return MainStatus(
                icon = MainStatusIcon.PauseCircle,
                tone = MainStatusTone.Info,
                headline = "Paused until ${formatTime(inputs.pausedUntilEpochMs)}",
                subtitle = "Alerts are silenced",
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
