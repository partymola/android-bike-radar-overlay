// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.annotation.StringRes
import es.jjrh.bikeradar.MissingPermission
import es.jjrh.bikeradar.R

/**
 * Pure, Android-free derivation of the home-screen status card.
 *
 * Inputs are collapsed into a plain data class so the deriver can be
 * unit-tested on the JVM without pulling in RadarStateBus, BluetoothManager,
 * Compose runtime, or the real clock.
 */
enum class MainStatusTone { Good, Warn, Error, Info, Neutral }

/** No icon is drawn: with the tone, this picks the home card's dot colour
 *  (`dotForStatus`) and whether the Bluetooth banner shows, so the deriver
 *  stays Compose-free. */
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
    val pausedUntilEpochMs: Long,
    val hasBond: Boolean,
    val radarFresh: Boolean,
    /** The radar is delivering, but from a source that cannot raise the urgent
     *  warning or log a close pass. Distinct from [radarFresh] because the
     *  hero must not show "all good" green over a degraded link. */
    val radarLimited: Boolean = false,
    val haErrorRecent: Boolean,
    val dashcamOwned: Boolean,
    val dashcamWarnWhenOff: Boolean,
    val dashcamFresh: Boolean,
    val dashcamDisplayName: String?,
    /** True when the user has the foreground service enabled (default).
     *  When false the rider has explicitly turned off all radar tracking
     *  via Settings; the deriver surfaces a "Scanning stopped" status with
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
    /** True when the radar has been down long enough for the parked
     *  declaration to be offered, which changes the headline from an
     *  instruction to a question. Defaults false so every existing call site
     *  and test keeps the plain waiting copy. */
    val rideEndOfferable: Boolean = false,
    /** The permission the card asks for, or null
     *  ([es.jjrh.bikeradar.Permissions.missingForService]). */
    val missingPermission: MissingPermission? = null,
    /** The rider's walk-away alarm switch. It can sound only with a dashcam,
     *  and only it needs a notification to be silenced. */
    val walkAwayAlarmOn: Boolean = false,
    /** [es.jjrh.bikeradar.RadarLinkStatus.isConnecting]: the same input the
     *  System row reads, so the two cannot disagree about one radar. */
    val radarConnecting: Boolean = false,
    /** The service's connecting stretch
     *  ([es.jjrh.bikeradar.RadarLinkState.connectingSinceMs]) has run
     *  [CONNECTING_ADVICE_AFTER_MS] or more, so a card shown while
     *  [radarConnecting] may tell the rider to turn the radar off and on. */
    val connectingAdviceDue: Boolean = false,
    /** The radar screen has a paired device to offer as the radar
     *  ([radarHatchDevices]), so Pair opens it rather than Android's Bluetooth
     *  list, and the not-paired card says so. */
    val radarPickOffered: Boolean = false,
    /** The pinned device's name when [es.jjrh.bikeradar.RadarSelection.pinOffNamedRadars]
     *  finds one: only it links, and it may be a watch pinned by mistake. */
    val pinnedOffRadarName: String? = null,
)

/** How long connecting without data runs before the card advises turning the
 *  radar off and on (`MainScreenEndRideWiringTest.thePowerCycleAdviceWaitsOutAnOrdinaryConnect`).
 *  Counted from the first link open of a failing stretch, pauses between
 *  attempts included, so the connect and discovery before that open, and an
 *  ordinary drop-to-reconnect, never count against it. */
internal const val CONNECTING_ADVICE_AFTER_MS = 15_000L

object MainStatusDeriver {

    /**
     * Priority order, first match wins:
     *  1. Scanning stopped (user toggled the foreground service off)
     *  2. Nearby devices missing
     *  3. User-paused
     *  4. Bluetooth disabled (adapter off at the OS level)
     *  5. Radar not paired
     *  6. Radar live, distance only
     *  7. Radar live but dashcam off  (rider-safety beats HA connectivity)
     *  8. Notifications missing, in place of 9, 10 or 14
     *  9. Radar live but HA unreachable
     *  10. Radar live, all good
     *  11. Radar stale after a ride: "Finished your ride?"
     *  12. Pin on a device other than the named radars: "Using the right radar?"
     *  13. Link up, or closed under 5 s ago, no data yet: connecting
     *  14. Radar stale: waiting
     *
     * Notifications takes only the cards that carry no warning or button of
     * their own. The service runs without it, so it must not hide the radar's
     * own problems, and above all not the parked question, whose button is
     * the only way to stop the drop cue repeating
     * (`MainStatusDeriverTest.missingNotificationsTakesOnlyTheQuietCards`).
     *
     * The parked question beats connecting: a radar stuck failing its setup
     * after a ride is both, and the question carries the only control that
     * stops the drop cue repeating
     * (`MainStatusDeriverTest.theParkedQuestionBeatsConnecting`).
     *
     * The pin question sits below the live cards and the parked question, and
     * above connecting and Notifications (`MainStatusDeriverTest`'s
     * `theParkedQuestionAndALiveRadarBeatAPinOffTheNamedRadars`,
     * `aPinOffTheNamedRadarsBeatsConnectingAndItsAdvice`,
     * `aPinOffTheNamedRadarsBeatsTheNotificationsCard`).
     *
     * DashcamOff is intentionally ranked above HaDown: HA is background
     * telemetry, the dashcam is the rear-view recording the rider needs.
     * If both fire simultaneously the dashcam warning wins.
     *
     * Scanning-stopped beats Paused/BtOff/NotPaired because if the service
     * isn't running there's no scanning happening at all — telling the
     * rider the radar is "not paired" when the real cause is that they
     * turned the whole thing off would be misdirection.
     *
     * Nearby devices missing beats everything after it for the same reason:
     * the app will not start the service without it, and with Bluetooth
     * connect denied the bonded list reads empty, so "not paired" would send
     * the rider to Android's Bluetooth list where the radar already is.
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
        if (!inputs.serviceEnabled) {
            return MainStatusModel(
                icon = MainStatusIcon.PlayCircle,
                tone = MainStatusTone.Neutral,
                headlineRes = R.string.main_status_service_off_title,
                subtitleRes = R.string.main_status_service_off_sub,
            )
        }
        if (inputs.missingPermission == MissingPermission.NEARBY_DEVICES) {
            // Red, as on Settings -> Permissions: no alerts at all.
            return MainStatusModel(
                icon = MainStatusIcon.Warning,
                tone = MainStatusTone.Error,
                headlineRes = R.string.main_status_permissions_title,
                subtitleRes = R.string.main_status_permissions_nearby_sub,
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
                subtitleRes =
                if (inputs.radarPickOffered) R.string.main_status_not_paired_pick_sub else R.string.main_status_not_paired_sub,
            )
        }
        if (inputs.radarFresh && inputs.radarLimited) {
            // Ahead of the dashcam warning on purpose: this one is about the
            // safety-critical device, and it is the reason the rider is not
            // getting the cue they think they are. The row beside it says
            // "Limited"; the hero must not simultaneously say all is well.
            return MainStatusModel(
                icon = MainStatusIcon.Warning,
                tone = MainStatusTone.Warn,
                headlineRes = R.string.main_status_limited_title,
                subtitleRes = R.string.main_status_limited_sub,
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
            notificationsCard(inputs)?.let { return it }
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
        // Same state, different question. Once the radar has been down long
        // enough to offer the parked declaration, "Turn on your radar" is one
        // of two instructions on a card whose other one invites the rider to
        // say the ride is over. Asking instead lets the button be the answer.
        // Icon and tone are deliberately unchanged: nothing about the radar
        // has changed, only what the app can offer, and painting a probably
        // normal end of ride amber would put the loudest colour on the card at
        // the moment the app is least sure anything is wrong.
        if (inputs.rideEndOfferable) {
            return MainStatusModel(
                icon = MainStatusIcon.Sensors,
                tone = MainStatusTone.Neutral,
                headlineRes = R.string.main_status_ride_over_title,
                subtitleRes = R.string.main_status_ride_over_sub,
            )
        }
        // A question, not a claim: the pin may be a radar the app does not know
        // by name, switched off. Neutral like waiting, which it replaces. Ahead
        // of connecting, whose advice would point at the wrong device.
        inputs.pinnedOffRadarName?.let { name ->
            return MainStatusModel(
                icon = MainStatusIcon.Sensors,
                tone = MainStatusTone.Neutral,
                headlineRes = R.string.main_status_wrong_pin_title,
                // Connecting means the device is on, so no "turn it on" then.
                subtitleRes = when {
                    inputs.radarConnecting && inputs.connectingAdviceDue -> R.string.main_status_wrong_pin_advice_sub
                    inputs.radarConnecting -> R.string.main_status_wrong_pin_connecting_sub
                    else -> R.string.main_status_wrong_pin_sub
                },
                subtitleArgs = listOf(name),
            )
        }
        // Amber, like the row beside it: not cover yet, and possibly a radar
        // that will never get past its setup. The advice waits until that is
        // likely.
        if (inputs.radarConnecting) {
            return MainStatusModel(
                icon = MainStatusIcon.Sensors,
                tone = MainStatusTone.Warn,
                headlineRes = R.string.main_status_connecting_title,
                subtitleRes = if (inputs.connectingAdviceDue) R.string.main_status_connecting_sub else null,
            )
        }
        notificationsCard(inputs)?.let { return it }
        return MainStatusModel(
            icon = MainStatusIcon.Sensors,
            tone = MainStatusTone.Neutral,
            headlineRes = R.string.main_status_waiting_title,
            subtitleRes = R.string.main_status_waiting_sub,
        )
    }

    /** Amber, not red: the radar still beeps without it. What goes is listed
     *  on [MissingPermission]. The subtitle names Pause, which every rider
     *  loses, since it is offered only from the notification, and the
     *  walk-away alarm only for a rider who can hear one
     *  (`MainStatusDeriverTest.theNotificationsCardNamesTheAlarmOnlyWhenOneCanSound`). */
    private fun notificationsCard(inputs: MainStatusInputs): MainStatusModel? = if (inputs.missingPermission == MissingPermission.NOTIFICATIONS) {
        val alarmCanSound = inputs.dashcamOwned && inputs.walkAwayAlarmOn
        MainStatusModel(
            icon = MainStatusIcon.Warning,
            tone = MainStatusTone.Warn,
            headlineRes = R.string.main_status_permissions_title,
            subtitleRes = if (alarmCanSound) {
                R.string.main_status_permissions_notifications_alarm_sub
            } else {
                R.string.main_status_permissions_notifications_sub
            },
        )
    } else {
        null
    }
}
