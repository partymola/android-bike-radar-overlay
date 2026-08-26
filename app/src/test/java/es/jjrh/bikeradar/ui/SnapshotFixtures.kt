// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import es.jjrh.bikeradar.CameraLightMode
import es.jjrh.bikeradar.RadarLightMode
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.HaIntent
import es.jjrh.bikeradar.data.PrefsSnapshot

/**
 * Test-only fixtures shared across snapshot tests. Centralised so that
 * additions to [PrefsSnapshot] only need a one-line update here.
 */
internal object SnapshotFixtures {

    /** Mirrors the production [es.jjrh.bikeradar.data.Prefs] defaults. */
    fun defaultPrefsSnapshot(): PrefsSnapshot = PrefsSnapshot(
        firstRunComplete = true,
        serviceEnabled = true,
        alertVolume = 50,
        alertMaxDistanceM = 20,
        visualMaxDistanceM = 50,
        overlayOpacity = 1.0f,
        radarLongOfflineThresholdMinutes = 30,
        radarLongOfflineCapSec = 30,
        pausedUntilEpochMs = 0L,
        devModeUnlocked = false,
        haLastValidatedEpochMs = 0L,
        batteryLowThresholdPct = 20,
        batteryShowLabels = false,
        dashcamOwnership = DashcamOwnership.UNANSWERED,
        dashcamMac = null,
        dashcamDisplayName = null,
        dashcamWarnWhenOff = false,
        haIntent = HaIntent.UNSET,
        walkAwayAlarmEnabled = true,
        walkAwayAlarmThresholdSec = 30,
        adaptiveAlertsEnabled = true,
        urgentLowSpeedEnabled = true,
        precogEnabled = false,
        turnAwareAlertsEnabled = true,
        closePassLoggingEnabled = false,
        closePassEmitMinRangeXM = 1.0f,
        closePassRiderSpeedFloorKmh = 15,
        closePassClosingSpeedFloorMs = 6,
        autoLightModeEnabled = false,
        cameraLightDayMode = CameraLightMode.DAY_FLASH,
        cameraLightNightMode = CameraLightMode.LOW,
        radarLightAutoModeEnabled = false,
        radarLightDayMode = RadarLightMode.DAY_FLASH,
        radarLightNightMode = RadarLightMode.NIGHT_FLASH,
        radarMac = null,
        radarDisplayName = null,
        radarLateralOffsetCm = 0,
        radarFirmwareRev = null,
        eBikeDataEnabled = false,
        eBikeOwnership = EBikeOwnership.UNANSWERED,
        eBikeUnknownObjectLogEnabled = false,
        radarSettingsProbeEnabled = false,
        captureLoggingEnabled = false,
        urgentPassClearanceM = 1.5f,
    )
}

/**
 * Theme wrapper for screenshot tests: identical to [UiTheme] but renders
 * in inspection mode, so infinite animations (e.g. the status-dot pulse
 * halo) draw a stable single-frame state instead of an arbitrary
 * animation phase that varies with test-JVM scheduling.
 */
@Composable
fun SnapshotTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInspectionMode provides true) {
        UiTheme(content)
    }
}
