// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.CameraLightMode
import es.jjrh.bikeradar.data.DashcamOwnership
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
        precogEnabled = false,
        closePassLoggingEnabled = false,
        closePassEmitMinRangeXM = 1.0f,
        closePassRiderSpeedFloorKmh = 15,
        closePassClosingSpeedFloorMs = 6,
        autoLightModeEnabled = false,
        cameraLightDayMode = CameraLightMode.DAY_FLASH,
        cameraLightNightMode = CameraLightMode.LOW,
    )
}
