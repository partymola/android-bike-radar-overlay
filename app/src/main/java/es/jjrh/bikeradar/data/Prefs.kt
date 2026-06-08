// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.content.Context
import android.content.SharedPreferences
import es.jjrh.bikeradar.CameraLightMode
import es.jjrh.bikeradar.RadarLightMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Tri-state so the upgrader / onboarding flow can distinguish "hasn't
 *  been asked yet" from "explicitly said no". */
enum class DashcamOwnership { UNANSWERED, YES, NO }

/** Tri-state ownership for the Bosch eBike live-data feature. Mirrors
 *  [DashcamOwnership] so the same UNANSWERED / YES / NO semantics apply:
 *  the onboarding step shows the chooser when UNANSWERED, walks through
 *  pairing on YES, skips on NO. NO is not permanent; the Settings ->
 *  eBike screen offers a promotion path back to YES. */
enum class EBikeOwnership { UNANSWERED, YES, NO }

/** Tri-state mirror of [DashcamOwnership] for the Home Assistant step.
 *  UNSET = onboarding hasn't asked the user; YES = user opted in (fields
 *  visible); NO = user opted out (skip card visible). The HA step is
 *  optional, so existing installs default to UNSET and the onboarding
 *  treats saved creds as implicit YES without writing the flag. */
enum class HaIntent { UNSET, YES, NO }

data class PrefsSnapshot(
    val firstRunComplete: Boolean,
    val serviceEnabled: Boolean,
    val alertVolume: Int,
    val alertMaxDistanceM: Int,
    val visualMaxDistanceM: Int,
    val overlayOpacity: Float,
    val radarLongOfflineThresholdMinutes: Int,
    val radarLongOfflineCapSec: Int,
    val pausedUntilEpochMs: Long,
    val devModeUnlocked: Boolean,
    val haLastValidatedEpochMs: Long,
    val batteryLowThresholdPct: Int,
    val batteryShowLabels: Boolean,
    val dashcamOwnership: DashcamOwnership,
    val dashcamMac: String?,
    val dashcamDisplayName: String?,
    val dashcamWarnWhenOff: Boolean,
    val haIntent: HaIntent,
    val walkAwayAlarmEnabled: Boolean,
    val walkAwayAlarmThresholdSec: Int,
    val adaptiveAlertsEnabled: Boolean,
    val precogEnabled: Boolean,
    val experimentalLateralPanning: Boolean,
    val experimentalLateralPanningInvertLR: Boolean,
    val closePassLoggingEnabled: Boolean,
    val closePassEmitMinRangeXM: Float,
    val closePassRiderSpeedFloorKmh: Int,
    val closePassClosingSpeedFloorMs: Int,
    val autoLightModeEnabled: Boolean,
    val cameraLightDayMode: CameraLightMode,
    val cameraLightNightMode: CameraLightMode,
    val radarLightAutoModeEnabled: Boolean,
    val radarLightDayMode: RadarLightMode,
    val radarLightNightMode: RadarLightMode,
    val radarMac: String?,
    val radarDisplayName: String?,
    val eBikeDataEnabled: Boolean,
    val eBikeOwnership: EBikeOwnership,
    val eBikeUnknownObjectLogEnabled: Boolean,
    val radarSettingsProbeEnabled: Boolean,
    val captureLoggingEnabled: Boolean,
)

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var firstRunComplete: Boolean
        get() = sp.getBoolean(KEY_FIRST_RUN_COMPLETE, false)
        set(v) {
            sp.edit().putBoolean(KEY_FIRST_RUN_COMPLETE, v).apply()
        }

    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_SERVICE_ENABLED, true)
        set(v) {
            sp.edit().putBoolean(KEY_SERVICE_ENABLED, v).apply()
        }

    var alertVolume: Int
        get() = sp.getInt(KEY_ALERT_VOLUME, 50)
        set(v) {
            sp.edit().putInt(KEY_ALERT_VOLUME, v).apply()
        }

    var alertMaxDistanceM: Int
        get() = sp.getInt(KEY_ALERT_MAX_DISTANCE_M, 20)
        set(v) {
            sp.edit().putInt(KEY_ALERT_MAX_DISTANCE_M, v).apply()
        }

    var visualMaxDistanceM: Int
        get() = sp.getInt(KEY_VISUAL_MAX_DISTANCE_M, 50)
        set(v) {
            sp.edit().putInt(KEY_VISUAL_MAX_DISTANCE_M, v).apply()
        }

    /** Fill multiplier for the on-screen overlay (0.5..1.0). Acts on top
     *  of the per-paint alphas in [RadarOverlayView], so 1.0 leaves the
     *  overlay at its pre-feature look and lower values dim it on top of
     *  that. Floor of 0.5 keeps close-pass alerts legible against bright
     *  underlying maps. */
    var overlayOpacity: Float
        get() = sp.getFloat(KEY_OVERLAY_OPACITY, 1.0f).coerceIn(0.5f, 1.0f)
        set(v) {
            sp.edit().putFloat(KEY_OVERLAY_OPACITY, v.coerceIn(0.5f, 1.0f)).apply()
        }

    /** After the radar has been offline this many minutes, the reconnect
     *  loop relaxes its backoff cap to [radarLongOfflineCapSec]. Lets
     *  the BLE stack idle during overnight parking instead of hammering
     *  GATT opens at the steady-state 8 s ceiling. */
    var radarLongOfflineThresholdMinutes: Int
        get() = sp.getInt(KEY_RADAR_LONG_OFFLINE_THRESHOLD_MIN, 30).coerceIn(5, 120)
        set(v) {
            sp.edit().putInt(KEY_RADAR_LONG_OFFLINE_THRESHOLD_MIN, v.coerceIn(5, 120)).apply()
        }

    /** Backoff cap once the radar has been offline past
     *  [radarLongOfflineThresholdMinutes]. Higher = longer idle and a
     *  slower reconnect when the radar comes back. */
    var radarLongOfflineCapSec: Int
        get() = sp.getInt(KEY_RADAR_LONG_OFFLINE_CAP_SEC, 30).coerceIn(5, 120)
        set(v) {
            sp.edit().putInt(KEY_RADAR_LONG_OFFLINE_CAP_SEC, v.coerceIn(5, 120)).apply()
        }

    var pausedUntilEpochMs: Long
        get() = sp.getLong(KEY_PAUSED_UNTIL_EPOCH_MS, 0L)
        set(v) {
            sp.edit().putLong(KEY_PAUSED_UNTIL_EPOCH_MS, v).apply()
        }

    var devModeUnlocked: Boolean
        get() = sp.getBoolean(KEY_DEV_MODE_UNLOCKED, false)
        set(v) {
            sp.edit().putBoolean(KEY_DEV_MODE_UNLOCKED, v).apply()
        }

    var haLastValidatedEpochMs: Long
        get() = sp.getLong(KEY_HA_LAST_VALIDATED_EPOCH_MS, 0L)
        set(v) {
            sp.edit().putLong(KEY_HA_LAST_VALIDATED_EPOCH_MS, v).apply()
        }

    var batteryLowThresholdPct: Int
        get() = sp.getInt(KEY_BATTERY_LOW_THRESHOLD_PCT, 20)
        set(v) {
            sp.edit().putInt(KEY_BATTERY_LOW_THRESHOLD_PCT, v).apply()
        }

    var batteryShowLabels: Boolean
        get() = sp.getBoolean(KEY_BATTERY_SHOW_LABELS, false)
        set(v) {
            sp.edit().putBoolean(KEY_BATTERY_SHOW_LABELS, v).apply()
        }

    var dashcamOwnership: DashcamOwnership
        get() = runCatching {
            DashcamOwnership.valueOf(
                sp.getString(KEY_DASHCAM_OWNERSHIP, DashcamOwnership.UNANSWERED.name)!!,
            )
        }.getOrDefault(DashcamOwnership.UNANSWERED)
        set(v) {
            sp.edit().putString(KEY_DASHCAM_OWNERSHIP, v.name).apply()
        }

    var haIntent: HaIntent
        get() = runCatching {
            HaIntent.valueOf(sp.getString(KEY_HA_INTENT, HaIntent.UNSET.name)!!)
        }.getOrDefault(HaIntent.UNSET)
        set(v) {
            sp.edit().putString(KEY_HA_INTENT, v.name).apply()
        }

    var dashcamMac: String?
        get() = sp.getString(KEY_DASHCAM_MAC, null)
        set(v) {
            sp.edit().putString(KEY_DASHCAM_MAC, v).apply()
        }

    var dashcamDisplayName: String?
        get() = sp.getString(KEY_DASHCAM_DISPLAY_NAME, null)
        set(v) {
            sp.edit().putString(KEY_DASHCAM_DISPLAY_NAME, v).apply()
        }

    var dashcamWarnWhenOff: Boolean
        get() = sp.getBoolean(KEY_DASHCAM_WARN_WHEN_OFF, false)
        set(v) {
            sp.edit().putBoolean(KEY_DASHCAM_WARN_WHEN_OFF, v).apply()
        }

    /** Keep the dead-radar overlay banner up until the radar reconnects, instead
     *  of retiring it after the short cap. Only affects riders with NO Bosch
     *  eBike (eBike riders' banner is already gated on the lock state); for them
     *  the banner is the sole dead-radar signal, so a safety-first rider can opt
     *  to never hide it mid-ride. Default off - an unbounded overlay is otherwise
     *  an uninstall driver. See [RadarLinkVisualDecider]. */
    var reconnectBannerPersistent: Boolean
        get() = sp.getBoolean(KEY_RECONNECT_BANNER_PERSISTENT, false)
        set(v) {
            sp.edit().putBoolean(KEY_RECONNECT_BANNER_PERSISTENT, v).apply()
        }

    /** Wrist-haptic reminder when you walk off without locking the eBike (the
     *  case the walk-away alarm stays silent for). Default on - a theft-prevention
     *  nudge with an asymmetric cost (a dismissable buzz vs a stolen bike).
     *  eBike-only. See [ForgotToLockDecider]. */
    var forgotToLockAlertEnabled: Boolean
        get() = sp.getBoolean(KEY_FORGOT_TO_LOCK_ALERT, true)
        set(v) {
            sp.edit().putBoolean(KEY_FORGOT_TO_LOCK_ALERT, v).apply()
        }

    /** Sticky bit set the first time the user dismisses the capture-log
     *  share warning dialog. Used so the warning only shows once. */
    var captureLogShareWarningSeen: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_LOG_SHARE_WARNING_SEEN, false)
        set(v) {
            sp.edit().putBoolean(KEY_CAPTURE_LOG_SHARE_WARNING_SEEN, v).apply()
        }

    /** Master toggle for the walk-away alarm (radar-off-while-dashcam-
     *  on notification). Default on: asymmetric cost - false positive
     *  is one swipe, false negative is a drained or stolen dashcam. */
    var walkAwayAlarmEnabled: Boolean
        get() = sp.getBoolean(KEY_WALKAWAY_ENABLED, true)
        set(v) {
            sp.edit().putBoolean(KEY_WALKAWAY_ENABLED, v).apply()
        }

    /** Threshold in seconds for the walk-away alarm. Min 15 s (any
     *  tighter races the radar reconnect loop), max 120 s (beyond
     *  that the rider is too far from the bike to act). Default 30 s
     *  is a compromise between caution (longer waits cover the
     *  rider's final lock-up fiddle) and urgency (shorter waits
     *  catch the rider before they walk out of earshot). */
    var walkAwayAlarmThresholdSec: Int
        get() = sp.getInt(KEY_WALKAWAY_THRESHOLD_SEC, 30).coerceIn(15, 120)
        set(v) {
            sp.edit().putInt(KEY_WALKAWAY_THRESHOLD_SEC, v.coerceIn(15, 120)).apply()
        }

    /** When true, the closing-speed colour bands on the overlay scale
     *  with the rider's own bike speed: a stopped rider sees amber /
     *  red sooner (any approach feels threatening when you can't move),
     *  a cruising rider sees them later (a 40 km/h closer is just
     *  traffic when you're doing 30). Covers the walk-of-shame case
     *  — puncture by the roadside — without a separate feature. */
    var adaptiveAlertsEnabled: Boolean
        get() = sp.getBoolean(KEY_ADAPTIVE_ALERTS, true)
        set(v) {
            sp.edit().putBoolean(KEY_ADAPTIVE_ALERTS, v).apply()
        }

    /** Experimental. When true, the overlay renders each vehicle at its
     *  predicted position 1 s from now (extrapolated from the decoder's
     *  closing speed + lateral velocity) rather than its current
     *  position. Turns a position-only view into an intent view:
     *  overtakers swinging wide show that swing a second before it
     *  happens. Default off because the prediction can look jittery
     *  when lateral velocity is noisy. */
    var precogEnabled: Boolean
        get() = sp.getBoolean(KEY_PRECOG, false)
        set(v) {
            sp.edit().putBoolean(KEY_PRECOG, v).apply()
        }

    /** Experimental: hard-pan Beep + UrgentApproach to the threat's side.
     *  Works on stereo headphones (BT/BLE/wired/USB/hearing aid) and on the
     *  phone's two built-in speakers in landscape (rotation-aware); portrait
     *  and unknown routes stay centred. Default off. */
    var experimentalLateralPanning: Boolean
        get() = sp.getBoolean(KEY_LATERAL_PANNING, false)
        set(v) {
            sp.edit().putBoolean(KEY_LATERAL_PANNING, v).apply()
        }

    /** Safety valve for [experimentalLateralPanning]: swap left/right at
     *  the final gain step. Covers the rare cases where the rider's
     *  headphones report channels inverted (factory-mislabelled buds,
     *  or a remembered earbud-on-wrong-ear), or a device-class quirk in
     *  AudioTrack stereo routing. Off by default; only meaningful when
     *  [experimentalLateralPanning] is on. */
    var experimentalLateralPanningInvertLR: Boolean
        get() = sp.getBoolean(KEY_LATERAL_PANNING_INVERT, false)
        set(v) {
            sp.edit().putBoolean(KEY_LATERAL_PANNING_INVERT, v).apply()
        }

    /** Master toggle for close-pass event logging to Home Assistant.
     *  Off by default; opt-in because the feature is only useful if
     *  the user actually wants the dataset and has HA wired up. */
    var closePassLoggingEnabled: Boolean
        get() = sp.getBoolean(KEY_CLOSE_PASS_ENABLED, false)
        set(v) {
            sp.edit().putBoolean(KEY_CLOSE_PASS_ENABLED, v).apply()
        }

    /** Advanced: emit an event only if the minimum lateral clearance
     *  dropped below this many metres. Default 1.0 m keeps the
     *  dataset focused on genuinely-unsafe passes; noise rejected
     *  here rather than filtered downstream. */
    var closePassEmitMinRangeXM: Float
        get() = sp.getFloat(KEY_CLOSE_PASS_EMIT_MIN_X_M, 1.0f).coerceIn(0.3f, 2.0f)
        set(v) {
            sp.edit().putFloat(KEY_CLOSE_PASS_EMIT_MIN_X_M, v.coerceIn(0.3f, 2.0f)).apply()
        }

    /** Advanced: minimum rider bike speed (km/h) for the detector to
     *  arm. Filters stationary-rider scenarios (red lights, pushing
     *  the bike) where nearby traffic doesn't count as an overtake.
     *  Storage stays in km/h - that's the unit the rider thinks in
     *  and the Settings slider exposes - while the engine consumes
     *  m/s via [closePassRiderSpeedFloorMs]. */
    var closePassRiderSpeedFloorKmh: Int
        get() = sp.getInt(KEY_CLOSE_PASS_RIDER_FLOOR_KMH, 15).coerceIn(5, 30)
        set(v) {
            sp.edit().putInt(KEY_CLOSE_PASS_RIDER_FLOOR_KMH, v.coerceIn(5, 30)).apply()
        }

    /** Same value as [closePassRiderSpeedFloorKmh], converted to m/s
     *  for the detector's m/s-canonical API. Read-only - the slider
     *  writes km/h. Float so the gate compares at full 0.25-m/s
     *  resolution. */
    val closePassRiderSpeedFloorMs: Float
        get() = closePassRiderSpeedFloorKmh / 3.6f

    /** Advanced: minimum closing speed (m/s) for the detector to arm.
     *  Filters lane-matched cruising and filtering — if the vehicle
     *  isn't genuinely overtaking, it's not a close pass. */
    var closePassClosingSpeedFloorMs: Int
        get() = sp.getInt(KEY_CLOSE_PASS_CLOSING_FLOOR_MS, 6).coerceIn(3, 15)
        set(v) {
            sp.edit().putInt(KEY_CLOSE_PASS_CLOSING_FLOOR_MS, v.coerceIn(3, 15)).apply()
        }

    /** Master toggle for front camera/light auto-mode. Default off: opt-in feature.
     *  When off, no BLE writes are sent to the light regardless of other settings. */
    var autoLightModeEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_LIGHT_MODE, false)
        set(v) {
            sp.edit().putBoolean(KEY_AUTO_LIGHT_MODE, v).apply()
        }

    /** Light mode applied at front camera/light connect time (before local sunset). */
    var cameraLightDayMode: CameraLightMode
        get() = runCatching {
            CameraLightMode.valueOf(sp.getString(KEY_CAMERA_LIGHT_DAY_MODE, CameraLightMode.DAY_FLASH.name)!!)
        }.getOrDefault(CameraLightMode.DAY_FLASH)
        set(v) {
            sp.edit().putString(KEY_CAMERA_LIGHT_DAY_MODE, v.name).apply()
        }

    /** Light mode applied at local sunset. */
    var cameraLightNightMode: CameraLightMode
        get() = runCatching {
            CameraLightMode.valueOf(sp.getString(KEY_CAMERA_LIGHT_NIGHT_MODE, CameraLightMode.LOW.name)!!)
        }.getOrDefault(CameraLightMode.LOW)
        set(v) {
            sp.edit().putString(KEY_CAMERA_LIGHT_NIGHT_MODE, v.name).apply()
        }

    /** Auto-switch the rear radar's tail light by time of day, mirroring the
     *  dashcam light. Independent of [autoLightModeEnabled] so a rider can run
     *  either light's auto-mode without the other. Off by default (opt-in). */
    var radarLightAutoModeEnabled: Boolean
        get() = sp.getBoolean(KEY_RADAR_LIGHT_AUTO_MODE, false)
        set(v) {
            sp.edit().putBoolean(KEY_RADAR_LIGHT_AUTO_MODE, v).apply()
        }

    /** Radar tail-light mode applied at radar connect (before local sunset). */
    var radarLightDayMode: RadarLightMode
        get() = runCatching {
            RadarLightMode.valueOf(sp.getString(KEY_RADAR_LIGHT_DAY_MODE, RadarLightMode.DAY_FLASH.name)!!)
        }.getOrDefault(RadarLightMode.DAY_FLASH)
        set(v) {
            sp.edit().putString(KEY_RADAR_LIGHT_DAY_MODE, v.name).apply()
        }

    /** Radar tail-light mode applied at local sunset. */
    var radarLightNightMode: RadarLightMode
        get() = runCatching {
            RadarLightMode.valueOf(sp.getString(KEY_RADAR_LIGHT_NIGHT_MODE, RadarLightMode.NIGHT_FLASH.name)!!)
        }.getOrDefault(RadarLightMode.NIGHT_FLASH)
        set(v) {
            sp.edit().putString(KEY_RADAR_LIGHT_NIGHT_MODE, v.name).apply()
        }

    /** MAC of the radar the rider explicitly pinned for this bike, or null to
     *  use name-match. See [es.jjrh.bikeradar.RadarSelection]. */
    var radarMac: String?
        get() = sp.getString(KEY_RADAR_MAC, null)
        set(v) {
            sp.edit().putString(KEY_RADAR_MAC, v).apply()
        }

    /** Display name of the pinned radar (for the Settings device card). */
    var radarDisplayName: String?
        get() = sp.getString(KEY_RADAR_DISPLAY_NAME, null)
        set(v) {
            sp.edit().putString(KEY_RADAR_DISPLAY_NAME, v).apply()
        }

    /** Enable the Bosch eBike live-data reader. Off by default. When off, the
     *  read-only status reader is never started and every downstream consumer
     *  (AlertDecider stationary override, walk-away disarm gate) sees a null
     *  `LiveDataSnapshot` and falls back to the radar's own bike-speed reading.
     *  Requires a Bosch Smart System eBike and the Bosch Flow app running;
     *  there is nothing to pair in this app - the reader listens on the link
     *  Flow already holds.
     *
     *  The getter performs a one-shot migration from the previous storage key
     *  (`ldi_enabled`, from when the feature was named after the official LDI
     *  protocol) so existing users keep their setting across the upgrade. The
     *  migration also clears two now-deleted legacy keys
     *  ([LEGACY_KEY_LDI_BONDED_ADDRESS], [LEGACY_KEY_LDI_ONBOARDING_RESUME]). */
    var eBikeDataEnabled: Boolean
        get() {
            if (sp.contains(LEGACY_KEY_LDI_ENABLED) && !sp.contains(KEY_EBIKE_DATA_ENABLED)) {
                val legacy = sp.getBoolean(LEGACY_KEY_LDI_ENABLED, false)
                sp.edit()
                    .putBoolean(KEY_EBIKE_DATA_ENABLED, legacy)
                    .remove(LEGACY_KEY_LDI_ENABLED)
                    .remove(LEGACY_KEY_LDI_BONDED_ADDRESS)
                    .remove(LEGACY_KEY_LDI_ONBOARDING_RESUME)
                    .apply()
                return legacy
            }
            return sp.getBoolean(KEY_EBIKE_DATA_ENABLED, false)
        }
        set(v) {
            sp.edit().putBoolean(KEY_EBIKE_DATA_ENABLED, v).apply()
        }

    /** Rider's answer to the "do you have a Bosch Smart System eBike?"
     *  question. UNANSWERED = onboarding's eBike step hasn't run yet,
     *  show the chooser. YES = rider has one and pairing is the next
     *  step. NO = rider doesn't (or doesn't want this feature). NO is
     *  not permanent; Settings -> eBike offers a promotion back to YES. */
    var eBikeOwnership: EBikeOwnership
        get() = runCatching {
            EBikeOwnership.valueOf(
                sp.getString(KEY_EBIKE_OWNERSHIP, EBikeOwnership.UNANSWERED.name)!!,
            )
        }.getOrDefault(EBikeOwnership.UNANSWERED)
        set(v) {
            sp.edit().putString(KEY_EBIKE_OWNERSHIP, v.name).apply()
        }

    /** Debug: log every marker-`0x30` record on the proprietary eBike status
     *  stream whose object ID is not yet mapped in [EBikeStatusDecoder]. Off
     *  by default. Lives on the Debug screen because its only purpose is the
     *  one-off pinning exercise for the still-unmapped flags (lock, light,
     *  charger, light-reserve, diagnosis, wheel-at-rest, ambient brightness,
     *  time): turn on, capture a session that toggles each state, diff the
     *  resulting `ebike_unk obj=0x.... val=...` lines either side of each
     *  transition, then turn off again. */
    var eBikeUnknownObjectLogEnabled: Boolean
        get() = sp.getBoolean(KEY_EBIKE_UNKNOWN_OBJ_LOG, false)
        set(v) {
            sp.edit().putBoolean(KEY_EBIKE_UNKNOWN_OBJ_LOG, v).apply()
        }

    /** Debug-only: after the V2 handshake, subscribe the rear radar's
     *  control-service notify characteristics (6a4e2f14 / 6a4e2f12) and log
     *  every frame as `radar_2f14` / `radar_2f12` capture lines. Off by
     *  default. Its only purpose is the one-off bench exercise to pin the
     *  radar tail-light mode-state encoding: the radar hosts the same control
     *  service (6a4e2f00) as the front camera, but its light-mode command is
     *  unverified. Turn on, cycle the radar light modes via the button or the
     *  Varia app, diff the logged frames against each mode, then turn off.
     *  Subscribed only post-handshake so it cannot interfere with the V2
     *  unlock, and gated so production rides never touch these CCCDs. */
    var radarSettingsProbeEnabled: Boolean
        get() = sp.getBoolean(KEY_RADAR_SETTINGS_PROBE, false)
        set(v) {
            sp.edit().putBoolean(KEY_RADAR_SETTINGS_PROBE, v).apply()
        }

    /** Master switch for the per-ride capture log. Off by default: the log
     *  records the exact timing of every radar packet, BLE notify and eBike
     *  snapshot to app-private storage - ride-tracking-grade data the app
     *  should not write unprompted. Enable it on the Debug screen to produce a
     *  log for bug reports or analysis; when off, [CaptureLogManager.open]
     *  is a no-op and no file is created. */
    var captureLoggingEnabled: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_LOGGING, false)
        set(v) {
            sp.edit().putBoolean(KEY_CAPTURE_LOGGING, v).apply()
        }

    val isPaused: Boolean get() = System.currentTimeMillis() < pausedUntilEpochMs

    fun snapshot(): PrefsSnapshot = PrefsSnapshot(
        firstRunComplete = firstRunComplete,
        serviceEnabled = serviceEnabled,
        alertVolume = alertVolume,
        alertMaxDistanceM = alertMaxDistanceM,
        visualMaxDistanceM = visualMaxDistanceM,
        overlayOpacity = overlayOpacity,
        radarLongOfflineThresholdMinutes = radarLongOfflineThresholdMinutes,
        radarLongOfflineCapSec = radarLongOfflineCapSec,
        pausedUntilEpochMs = pausedUntilEpochMs,
        devModeUnlocked = devModeUnlocked,
        haLastValidatedEpochMs = haLastValidatedEpochMs,
        batteryLowThresholdPct = batteryLowThresholdPct,
        batteryShowLabels = batteryShowLabels,
        dashcamOwnership = dashcamOwnership,
        dashcamMac = dashcamMac,
        dashcamDisplayName = dashcamDisplayName,
        dashcamWarnWhenOff = dashcamWarnWhenOff,
        haIntent = haIntent,
        walkAwayAlarmEnabled = walkAwayAlarmEnabled,
        walkAwayAlarmThresholdSec = walkAwayAlarmThresholdSec,
        adaptiveAlertsEnabled = adaptiveAlertsEnabled,
        precogEnabled = precogEnabled,
        experimentalLateralPanning = experimentalLateralPanning,
        experimentalLateralPanningInvertLR = experimentalLateralPanningInvertLR,
        closePassLoggingEnabled = closePassLoggingEnabled,
        closePassEmitMinRangeXM = closePassEmitMinRangeXM,
        closePassRiderSpeedFloorKmh = closePassRiderSpeedFloorKmh,
        closePassClosingSpeedFloorMs = closePassClosingSpeedFloorMs,
        autoLightModeEnabled = autoLightModeEnabled,
        cameraLightDayMode = cameraLightDayMode,
        cameraLightNightMode = cameraLightNightMode,
        radarLightAutoModeEnabled = radarLightAutoModeEnabled,
        radarLightDayMode = radarLightDayMode,
        radarLightNightMode = radarLightNightMode,
        radarMac = radarMac,
        radarDisplayName = radarDisplayName,
        eBikeDataEnabled = eBikeDataEnabled,
        eBikeOwnership = eBikeOwnership,
        eBikeUnknownObjectLogEnabled = eBikeUnknownObjectLogEnabled,
        radarSettingsProbeEnabled = radarSettingsProbeEnabled,
        captureLoggingEnabled = captureLoggingEnabled,
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
        appendLine("# Some identifying fields are redacted (<redacted>);")
        appendLine("# others (timestamps, thresholds) are not. Review before pasting publicly.")
        appendLine("first_run_complete=$firstRunComplete")
        appendLine("service_enabled=$serviceEnabled")
        appendLine("alert_volume=$alertVolume")
        appendLine("alert_max_distance_m=$alertMaxDistanceM")
        appendLine("visual_max_distance_m=$visualMaxDistanceM")
        appendLine("overlay_opacity=$overlayOpacity")
        appendLine("radar_long_offline_threshold_min=$radarLongOfflineThresholdMinutes")
        appendLine("radar_long_offline_cap_sec=$radarLongOfflineCapSec")
        appendLine("paused_until_epoch_ms=$pausedUntilEpochMs")
        appendLine("dev_mode_unlocked=$devModeUnlocked")
        appendLine("ha_last_validated_epoch_ms=$haLastValidatedEpochMs")
        appendLine("battery_low_threshold_pct=$batteryLowThresholdPct")
        appendLine("battery_show_labels=$batteryShowLabels")
        appendLine("dashcam_ownership=$dashcamOwnership")
        // Redacted: dashcam MAC + display name are user-identifying; bundle is meant for public issue trackers.
        appendLine("dashcam_mac=${redactPresence(dashcamMac)}")
        appendLine("dashcam_display_name=${redactPresence(dashcamDisplayName)}")
        appendLine("dashcam_warn_when_off=$dashcamWarnWhenOff")
        appendLine("ha_intent=$haIntent")
        appendLine("walk_away_alarm_enabled=$walkAwayAlarmEnabled")
        appendLine("walk_away_alarm_threshold_sec=$walkAwayAlarmThresholdSec")
        appendLine("adaptive_alerts_enabled=$adaptiveAlertsEnabled")
        appendLine("precog_enabled=$precogEnabled")
        appendLine("experimental_lateral_panning=$experimentalLateralPanning")
        appendLine("experimental_lateral_panning_invert_lr=$experimentalLateralPanningInvertLR")
        appendLine("close_pass_logging_enabled=$closePassLoggingEnabled")
        appendLine("close_pass_emit_min_x_m=$closePassEmitMinRangeXM")
        appendLine("close_pass_rider_floor_kmh=$closePassRiderSpeedFloorKmh")
        appendLine("close_pass_closing_floor_ms=$closePassClosingSpeedFloorMs")
        appendLine("auto_light_mode_enabled=$autoLightModeEnabled")
        appendLine("camera_light_day_mode=$cameraLightDayMode")
        appendLine("camera_light_night_mode=$cameraLightNightMode")
        appendLine("radar_light_auto_mode_enabled=$radarLightAutoModeEnabled")
        appendLine("radar_light_day_mode=$radarLightDayMode")
        appendLine("radar_light_night_mode=$radarLightNightMode")
        appendLine("ebike_data_enabled=$eBikeDataEnabled")
        appendLine("ebike_ownership=$eBikeOwnership")
        appendLine("ebike_unknown_object_log_enabled=$eBikeUnknownObjectLogEnabled")
        appendLine("radar_settings_probe_enabled=$radarSettingsProbeEnabled")
        appendLine("capture_logging_enabled=$captureLoggingEnabled")
    }

    companion object {
        private const val FILE = "bike_radar_prefs"
        const val KEY_FIRST_RUN_COMPLETE = "first_run_complete"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_ALERT_VOLUME = "alert_volume"
        const val KEY_ALERT_MAX_DISTANCE_M = "alert_max_distance_m"
        const val KEY_VISUAL_MAX_DISTANCE_M = "visual_max_distance_m"
        const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        const val KEY_RADAR_LONG_OFFLINE_THRESHOLD_MIN = "radar_long_offline_threshold_min"
        const val KEY_RADAR_LONG_OFFLINE_CAP_SEC = "radar_long_offline_cap_sec"
        const val KEY_PAUSED_UNTIL_EPOCH_MS = "paused_until_epoch_ms"
        const val KEY_DEV_MODE_UNLOCKED = "dev_mode_unlocked"
        const val KEY_HA_LAST_VALIDATED_EPOCH_MS = "ha_last_validated_epoch_ms"
        const val KEY_BATTERY_LOW_THRESHOLD_PCT = "battery_low_threshold_pct"
        const val KEY_BATTERY_SHOW_LABELS = "battery_show_labels"
        const val KEY_DASHCAM_OWNERSHIP = "dashcam_ownership"
        const val KEY_DASHCAM_MAC = "dashcam_mac"
        const val KEY_DASHCAM_DISPLAY_NAME = "dashcam_display_name"
        const val KEY_DASHCAM_WARN_WHEN_OFF = "dashcam_warn_when_off"
        const val KEY_HA_INTENT = "ha_intent"
        const val KEY_WALKAWAY_ENABLED = "walk_away_alarm_enabled"
        const val KEY_WALKAWAY_THRESHOLD_SEC = "walk_away_alarm_threshold_sec"
        const val KEY_ADAPTIVE_ALERTS = "adaptive_alerts_enabled"
        const val KEY_PRECOG = "precog_enabled"
        const val KEY_LATERAL_PANNING = "experimental_lateral_panning"
        const val KEY_LATERAL_PANNING_INVERT = "experimental_lateral_panning_invert_lr"
        const val KEY_CLOSE_PASS_ENABLED = "close_pass_logging_enabled"
        const val KEY_CLOSE_PASS_EMIT_MIN_X_M = "close_pass_emit_min_x_m"
        const val KEY_CLOSE_PASS_RIDER_FLOOR_KMH = "close_pass_rider_floor_kmh"
        const val KEY_CLOSE_PASS_CLOSING_FLOOR_MS = "close_pass_closing_floor_ms"
        const val KEY_CAPTURE_LOG_SHARE_WARNING_SEEN = "capture_log_share_warning_seen"
        const val KEY_RECONNECT_BANNER_PERSISTENT = "reconnect_banner_persistent"
        const val KEY_FORGOT_TO_LOCK_ALERT = "forgot_to_lock_alert_enabled"
        const val KEY_AUTO_LIGHT_MODE = "auto_light_mode_enabled"
        const val KEY_CAMERA_LIGHT_DAY_MODE = "camera_light_day_mode"
        const val KEY_CAMERA_LIGHT_NIGHT_MODE = "camera_light_night_mode"
        const val KEY_RADAR_LIGHT_AUTO_MODE = "radar_light_auto_mode_enabled"
        const val KEY_RADAR_LIGHT_DAY_MODE = "radar_light_day_mode"
        const val KEY_RADAR_LIGHT_NIGHT_MODE = "radar_light_night_mode"
        const val KEY_RADAR_MAC = "radar_mac"
        const val KEY_RADAR_DISPLAY_NAME = "radar_display_name"
        const val KEY_EBIKE_DATA_ENABLED = "ebike_data_enabled"
        const val KEY_EBIKE_OWNERSHIP = "ebike_ownership"
        const val KEY_EBIKE_UNKNOWN_OBJ_LOG = "ebike_unknown_object_log_enabled"
        const val KEY_RADAR_SETTINGS_PROBE = "radar_settings_probe_enabled"
        const val KEY_CAPTURE_LOGGING = "capture_logging_enabled"

        // Legacy storage keys from when the feature was named after the
        // official Bosch LDI protocol. Read-only; cleared on first use of the
        // new key by the [eBikeDataEnabled] migration getter. Do not introduce
        // new readers / writers.
        private const val LEGACY_KEY_LDI_ENABLED = "ldi_enabled"
        private const val LEGACY_KEY_LDI_BONDED_ADDRESS = "ldi_bonded_address"
        private const val LEGACY_KEY_LDI_ONBOARDING_RESUME = "ldi_onboarding_resume_point"

        /**
         * Replace a sensitive identifier with a presence-only marker for use
         * in [dumpAll] (the diagnostic bundle gets pasted into public issue
         * trackers). Returns `<unset>` for null / blank values, `<redacted>`
         * for anything else. `<redacted>` is the standard term for "we had
         * a value here and chose not to show it" and won't be mistaken for
         * a literal stored value.
         */
        internal fun redactPresence(value: String?): String = if (value.isNullOrBlank()) "<unset>" else "<redacted>"
    }
}
