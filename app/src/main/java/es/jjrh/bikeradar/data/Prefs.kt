// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Tri-state so the upgrader / onboarding flow can distinguish "hasn't
 *  been asked yet" from "explicitly said no". */
enum class DashcamOwnership { UNANSWERED, YES, NO }

data class PrefsSnapshot(
    val firstRunComplete: Boolean,
    val serviceEnabled: Boolean,
    val alertVolume: Int,
    val alertMaxDistanceM: Int,
    val visualMaxDistanceM: Int,
    val pausedUntilEpochMs: Long,
    val devModeUnlocked: Boolean,
    val haLastValidatedEpochMs: Long,
    val batteryLowThresholdPct: Int,
    val batteryShowLabels: Boolean,
    val dashcamOwnership: DashcamOwnership,
    val dashcamMac: String?,
    val dashcamDisplayName: String?,
    val dashcamWarnWhenOff: Boolean,
    val walkAwayAlarmEnabled: Boolean,
    val walkAwayAlarmThresholdSec: Int,
    val adaptiveAlertsEnabled: Boolean,
    val precogEnabled: Boolean,
    val closePassLoggingEnabled: Boolean,
    val closePassEmitMinRangeXM: Float,
    val closePassRiderSpeedFloorKmh: Int,
    val closePassClosingSpeedFloorMs: Int,
    val nextUxOnboarding: Boolean,
    val nextUxMain: Boolean,
    val nextUxSettings: Boolean,
)

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var firstRunComplete: Boolean
        get() = sp.getBoolean(KEY_FIRST_RUN_COMPLETE, false)
        set(v) { sp.edit().putBoolean(KEY_FIRST_RUN_COMPLETE, v).apply() }

    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_SERVICE_ENABLED, true)
        set(v) { sp.edit().putBoolean(KEY_SERVICE_ENABLED, v).apply() }

    var alertVolume: Int
        get() = sp.getInt(KEY_ALERT_VOLUME, 50)
        set(v) { sp.edit().putInt(KEY_ALERT_VOLUME, v).apply() }

    var alertMaxDistanceM: Int
        get() = sp.getInt(KEY_ALERT_MAX_DISTANCE_M, 20)
        set(v) { sp.edit().putInt(KEY_ALERT_MAX_DISTANCE_M, v).apply() }

    var visualMaxDistanceM: Int
        get() = sp.getInt(KEY_VISUAL_MAX_DISTANCE_M, 50)
        set(v) { sp.edit().putInt(KEY_VISUAL_MAX_DISTANCE_M, v).apply() }

    var pausedUntilEpochMs: Long
        get() = sp.getLong(KEY_PAUSED_UNTIL_EPOCH_MS, 0L)
        set(v) { sp.edit().putLong(KEY_PAUSED_UNTIL_EPOCH_MS, v).apply() }

    var devModeUnlocked: Boolean
        get() = sp.getBoolean(KEY_DEV_MODE_UNLOCKED, false)
        set(v) { sp.edit().putBoolean(KEY_DEV_MODE_UNLOCKED, v).apply() }

    var haLastValidatedEpochMs: Long
        get() = sp.getLong(KEY_HA_LAST_VALIDATED_EPOCH_MS, 0L)
        set(v) { sp.edit().putLong(KEY_HA_LAST_VALIDATED_EPOCH_MS, v).apply() }

    var batteryLowThresholdPct: Int
        get() = sp.getInt(KEY_BATTERY_LOW_THRESHOLD_PCT, 20)
        set(v) { sp.edit().putInt(KEY_BATTERY_LOW_THRESHOLD_PCT, v).apply() }

    var batteryShowLabels: Boolean
        get() = sp.getBoolean(KEY_BATTERY_SHOW_LABELS, false)
        set(v) { sp.edit().putBoolean(KEY_BATTERY_SHOW_LABELS, v).apply() }

    var dashcamOwnership: DashcamOwnership
        get() = runCatching {
            DashcamOwnership.valueOf(
                sp.getString(KEY_DASHCAM_OWNERSHIP, DashcamOwnership.UNANSWERED.name)!!
            )
        }.getOrDefault(DashcamOwnership.UNANSWERED)
        set(v) { sp.edit().putString(KEY_DASHCAM_OWNERSHIP, v.name).apply() }

    var dashcamMac: String?
        get() = sp.getString(KEY_DASHCAM_MAC, null)
        set(v) { sp.edit().putString(KEY_DASHCAM_MAC, v).apply() }

    var dashcamDisplayName: String?
        get() = sp.getString(KEY_DASHCAM_DISPLAY_NAME, null)
        set(v) { sp.edit().putString(KEY_DASHCAM_DISPLAY_NAME, v).apply() }

    var dashcamWarnWhenOff: Boolean
        get() = sp.getBoolean(KEY_DASHCAM_WARN_WHEN_OFF, false)
        set(v) { sp.edit().putBoolean(KEY_DASHCAM_WARN_WHEN_OFF, v).apply() }

    /** Master toggle for the walk-away alarm (radar-off-while-dashcam-
     *  on notification). Default on: asymmetric cost - false positive
     *  is one swipe, false negative is a drained or stolen dashcam. */
    var walkAwayAlarmEnabled: Boolean
        get() = sp.getBoolean(KEY_WALKAWAY_ENABLED, true)
        set(v) { sp.edit().putBoolean(KEY_WALKAWAY_ENABLED, v).apply() }

    /** Threshold in seconds for the walk-away alarm. Min 15 s (any
     *  tighter races the radar reconnect loop), max 120 s (beyond
     *  that the rider is too far from the bike to act). Default 30 s
     *  is a compromise between caution (longer waits cover the
     *  rider's final lock-up fiddle) and urgency (shorter waits
     *  catch the rider before they walk out of earshot). */
    var walkAwayAlarmThresholdSec: Int
        get() = sp.getInt(KEY_WALKAWAY_THRESHOLD_SEC, 30).coerceIn(15, 120)
        set(v) { sp.edit().putInt(KEY_WALKAWAY_THRESHOLD_SEC, v.coerceIn(15, 120)).apply() }

    /** When true, the closing-speed colour bands on the overlay scale
     *  with the rider's own bike speed: a stopped rider sees amber /
     *  red sooner (any approach feels threatening when you can't move),
     *  a cruising rider sees them later (a 40 km/h closer is just
     *  traffic when you're doing 30). Covers the walk-of-shame case
     *  — puncture by the roadside — without a separate feature. */
    var adaptiveAlertsEnabled: Boolean
        get() = sp.getBoolean(KEY_ADAPTIVE_ALERTS, true)
        set(v) { sp.edit().putBoolean(KEY_ADAPTIVE_ALERTS, v).apply() }

    /** Experimental. When true, the overlay renders each vehicle at its
     *  predicted position 1 s from now (extrapolated from the decoder's
     *  closing speed + lateral velocity) rather than its current
     *  position. Turns a position-only view into an intent view:
     *  overtakers swinging wide show that swing a second before it
     *  happens. Default off because the prediction can look jittery
     *  when lateral velocity is noisy. */
    var precogEnabled: Boolean
        get() = sp.getBoolean(KEY_PRECOG, false)
        set(v) { sp.edit().putBoolean(KEY_PRECOG, v).apply() }

    /** Master toggle for close-pass event logging to Home Assistant.
     *  Off by default; opt-in because the feature is only useful if
     *  the user actually wants the dataset and has HA wired up. */
    var closePassLoggingEnabled: Boolean
        get() = sp.getBoolean(KEY_CLOSE_PASS_ENABLED, false)
        set(v) { sp.edit().putBoolean(KEY_CLOSE_PASS_ENABLED, v).apply() }

    /** Advanced: emit an event only if the minimum lateral clearance
     *  dropped below this many metres. Default 1.0 m keeps the
     *  dataset focused on genuinely-unsafe passes; noise rejected
     *  here rather than filtered downstream. */
    var closePassEmitMinRangeXM: Float
        get() = sp.getFloat(KEY_CLOSE_PASS_EMIT_MIN_X_M, 1.0f).coerceIn(0.3f, 2.0f)
        set(v) { sp.edit().putFloat(KEY_CLOSE_PASS_EMIT_MIN_X_M, v.coerceIn(0.3f, 2.0f)).apply() }

    /** Advanced: minimum rider bike speed (km/h) for the detector to
     *  arm. Filters stationary-rider scenarios (red lights, pushing
     *  the bike) where nearby traffic doesn't count as an overtake. */
    var closePassRiderSpeedFloorKmh: Int
        get() = sp.getInt(KEY_CLOSE_PASS_RIDER_FLOOR_KMH, 15).coerceIn(5, 30)
        set(v) { sp.edit().putInt(KEY_CLOSE_PASS_RIDER_FLOOR_KMH, v.coerceIn(5, 30)).apply() }

    /** Advanced: minimum closing speed (m/s) for the detector to arm.
     *  Filters lane-matched cruising and filtering — if the vehicle
     *  isn't genuinely overtaking, it's not a close pass. */
    var closePassClosingSpeedFloorMs: Int
        get() = sp.getInt(KEY_CLOSE_PASS_CLOSING_FLOOR_MS, 6).coerceIn(3, 15)
        set(v) { sp.edit().putInt(KEY_CLOSE_PASS_CLOSING_FLOOR_MS, v.coerceIn(3, 15)).apply() }

    /** Per-screen feature flags for the UX redesign. When true, the
     *  corresponding NavHost route renders the Next composable instead
     *  of the original. The user can flip any of these from the Debug
     *  screen, and each flag (plus its V1 code path) is removed once
     *  the screen is fully graduated. Defaults reflect the current
     *  shipping state: Main + Settings (and Debug, which rides the
     *  Settings flag) ship redesigned; Onboarding still defaults to
     *  the original flow until it has been ride-tested. */
    var nextUxOnboarding: Boolean
        get() = sp.getBoolean(KEY_NEXT_UX_ONBOARDING, false)
        set(v) { sp.edit().putBoolean(KEY_NEXT_UX_ONBOARDING, v).apply() }

    var nextUxMain: Boolean
        get() = sp.getBoolean(KEY_NEXT_UX_MAIN, true)
        set(v) { sp.edit().putBoolean(KEY_NEXT_UX_MAIN, v).apply() }

    var nextUxSettings: Boolean
        get() = sp.getBoolean(KEY_NEXT_UX_SETTINGS, true)
        set(v) { sp.edit().putBoolean(KEY_NEXT_UX_SETTINGS, v).apply() }

    val isPaused: Boolean get() = System.currentTimeMillis() < pausedUntilEpochMs

    fun snapshot(): PrefsSnapshot = PrefsSnapshot(
        firstRunComplete = firstRunComplete,
        serviceEnabled = serviceEnabled,
        alertVolume = alertVolume,
        alertMaxDistanceM = alertMaxDistanceM,
        visualMaxDistanceM = visualMaxDistanceM,
        pausedUntilEpochMs = pausedUntilEpochMs,
        devModeUnlocked = devModeUnlocked,
        haLastValidatedEpochMs = haLastValidatedEpochMs,
        batteryLowThresholdPct = batteryLowThresholdPct,
        batteryShowLabels = batteryShowLabels,
        dashcamOwnership = dashcamOwnership,
        dashcamMac = dashcamMac,
        dashcamDisplayName = dashcamDisplayName,
        dashcamWarnWhenOff = dashcamWarnWhenOff,
        walkAwayAlarmEnabled = walkAwayAlarmEnabled,
        walkAwayAlarmThresholdSec = walkAwayAlarmThresholdSec,
        adaptiveAlertsEnabled = adaptiveAlertsEnabled,
        precogEnabled = precogEnabled,
        closePassLoggingEnabled = closePassLoggingEnabled,
        closePassEmitMinRangeXM = closePassEmitMinRangeXM,
        closePassRiderSpeedFloorKmh = closePassRiderSpeedFloorKmh,
        closePassClosingSpeedFloorMs = closePassClosingSpeedFloorMs,
        nextUxOnboarding = nextUxOnboarding,
        nextUxMain = nextUxMain,
        nextUxSettings = nextUxSettings,
    )

    val flow: Flow<PrefsSnapshot> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(snapshot())
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        send(snapshot())
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun dumpAll(): String = buildString {
        appendLine("first_run_complete=$firstRunComplete")
        appendLine("service_enabled=$serviceEnabled")
        appendLine("alert_volume=$alertVolume")
        appendLine("alert_max_distance_m=$alertMaxDistanceM")
        appendLine("visual_max_distance_m=$visualMaxDistanceM")
        appendLine("paused_until_epoch_ms=$pausedUntilEpochMs")
        appendLine("dev_mode_unlocked=$devModeUnlocked")
        appendLine("ha_last_validated_epoch_ms=$haLastValidatedEpochMs")
        appendLine("battery_low_threshold_pct=$batteryLowThresholdPct")
        appendLine("battery_show_labels=$batteryShowLabels")
        appendLine("dashcam_ownership=$dashcamOwnership")
        appendLine("dashcam_mac=${dashcamMac ?: "<none>"}")
        appendLine("dashcam_display_name=${dashcamDisplayName ?: "<none>"}")
        appendLine("dashcam_warn_when_off=$dashcamWarnWhenOff")
        appendLine("walk_away_alarm_enabled=$walkAwayAlarmEnabled")
        appendLine("walk_away_alarm_threshold_sec=$walkAwayAlarmThresholdSec")
        appendLine("adaptive_alerts_enabled=$adaptiveAlertsEnabled")
        appendLine("precog_enabled=$precogEnabled")
        appendLine("close_pass_logging_enabled=$closePassLoggingEnabled")
        appendLine("close_pass_emit_min_x_m=$closePassEmitMinRangeXM")
        appendLine("close_pass_rider_floor_kmh=$closePassRiderSpeedFloorKmh")
        appendLine("close_pass_closing_floor_ms=$closePassClosingSpeedFloorMs")
        appendLine("next_ux_onboarding=$nextUxOnboarding")
        appendLine("next_ux_main=$nextUxMain")
        appendLine("next_ux_settings=$nextUxSettings")
    }

    companion object {
        private const val FILE = "bike_radar_prefs"
        const val KEY_FIRST_RUN_COMPLETE = "first_run_complete"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_ALERT_VOLUME = "alert_volume"
        const val KEY_ALERT_MAX_DISTANCE_M = "alert_max_distance_m"
        const val KEY_VISUAL_MAX_DISTANCE_M = "visual_max_distance_m"
        const val KEY_PAUSED_UNTIL_EPOCH_MS = "paused_until_epoch_ms"
        const val KEY_DEV_MODE_UNLOCKED = "dev_mode_unlocked"
        const val KEY_HA_LAST_VALIDATED_EPOCH_MS = "ha_last_validated_epoch_ms"
        const val KEY_BATTERY_LOW_THRESHOLD_PCT = "battery_low_threshold_pct"
        const val KEY_BATTERY_SHOW_LABELS = "battery_show_labels"
        const val KEY_DASHCAM_OWNERSHIP = "dashcam_ownership"
        const val KEY_DASHCAM_MAC = "dashcam_mac"
        const val KEY_DASHCAM_DISPLAY_NAME = "dashcam_display_name"
        const val KEY_DASHCAM_WARN_WHEN_OFF = "dashcam_warn_when_off"
        const val KEY_WALKAWAY_ENABLED = "walk_away_alarm_enabled"
        const val KEY_WALKAWAY_THRESHOLD_SEC = "walk_away_alarm_threshold_sec"
        const val KEY_ADAPTIVE_ALERTS = "adaptive_alerts_enabled"
        const val KEY_PRECOG = "precog_enabled"
        const val KEY_CLOSE_PASS_ENABLED = "close_pass_logging_enabled"
        const val KEY_CLOSE_PASS_EMIT_MIN_X_M = "close_pass_emit_min_x_m"
        const val KEY_CLOSE_PASS_RIDER_FLOOR_KMH = "close_pass_rider_floor_kmh"
        const val KEY_CLOSE_PASS_CLOSING_FLOOR_MS = "close_pass_closing_floor_ms"
        const val KEY_NEXT_UX_ONBOARDING = "next_ux_onboarding"
        const val KEY_NEXT_UX_MAIN = "next_ux_main"
        const val KEY_NEXT_UX_SETTINGS = "next_ux_settings"
    }
}
