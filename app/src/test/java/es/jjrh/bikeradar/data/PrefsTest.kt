// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.CameraLightMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract tests for [Prefs], the single SharedPreferences-backed settings
 * store the whole app reads through [Prefs.snapshot] / [Prefs.flow]. The
 * value here is not the getter/setter plumbing but the logic layered on top:
 * the clamp ranges that keep out-of-range slider values safe, the
 * corrupt-value enum fallbacks that stop a mangled pref from crashing a
 * read, the km/h to m/s derivation the detector consumes, and the
 * identifier redaction in the diagnostic dump.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric isolates the prefs file per test, but an explicit clear
        // keeps the default-value assertions independent of test ordering
        // (matches EBikeOwnershipPrefsTest).
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().apply()
        prefs = Prefs(context)
    }

    @Test
    fun snapshotReportsDocumentedDefaultsOnAFreshStore() {
        val s = prefs.snapshot()
        assertFalse(s.firstRunComplete)
        assertTrue(s.serviceEnabled)
        assertEquals(50, s.alertVolume)
        assertEquals(20, s.alertMaxDistanceM)
        assertEquals(50, s.visualMaxDistanceM)
        assertEquals(1.0f, s.overlayOpacity, 0f)
        assertEquals(30, s.radarLongOfflineThresholdMinutes)
        assertEquals(30, s.radarLongOfflineCapSec)
        assertEquals(0L, s.pausedUntilEpochMs)
        assertFalse(s.devModeUnlocked)
        assertEquals(0L, s.haLastValidatedEpochMs)
        assertEquals(20, s.batteryLowThresholdPct)
        assertFalse(s.batteryShowLabels)
        assertEquals(DashcamOwnership.UNANSWERED, s.dashcamOwnership)
        assertNull(s.dashcamMac)
        assertNull(s.dashcamDisplayName)
        assertFalse(s.dashcamWarnWhenOff)
        assertEquals(HaIntent.UNSET, s.haIntent)
        assertTrue(s.walkAwayAlarmEnabled)
        assertEquals(30, s.walkAwayAlarmThresholdSec)
        assertTrue(s.adaptiveAlertsEnabled)
        assertFalse(s.precogEnabled)
        assertFalse(s.experimentalLateralPanning)
        assertFalse(s.experimentalLateralPanningInvertLR)
        assertFalse(s.closePassLoggingEnabled)
        assertEquals(1.0f, s.closePassEmitMinRangeXM, 0f)
        assertEquals(15, s.closePassRiderSpeedFloorKmh)
        assertEquals(6, s.closePassClosingSpeedFloorMs)
        assertFalse(s.autoLightModeEnabled)
        assertEquals(CameraLightMode.DAY_FLASH, s.cameraLightDayMode)
        assertEquals(CameraLightMode.LOW, s.cameraLightNightMode)
        assertFalse(s.ldiEnabled)
        assertEquals(EBikeOwnership.UNANSWERED, s.eBikeOwnership)
        assertFalse(s.ldiOnboardingResumePoint)
    }

    @Test
    fun writtenValuesPersistAndSurfaceInAFreshInstanceSnapshot() {
        prefs.firstRunComplete = true
        prefs.serviceEnabled = false
        prefs.alertVolume = 80
        prefs.alertMaxDistanceM = 25
        prefs.visualMaxDistanceM = 60
        prefs.overlayOpacity = 0.7f
        prefs.radarLongOfflineThresholdMinutes = 45
        prefs.radarLongOfflineCapSec = 60
        prefs.pausedUntilEpochMs = 999L
        prefs.devModeUnlocked = true
        prefs.haLastValidatedEpochMs = 123L
        prefs.batteryLowThresholdPct = 15
        prefs.batteryShowLabels = true
        prefs.dashcamOwnership = DashcamOwnership.YES
        prefs.dashcamMac = "AA:BB:CC:DD:EE:FF"
        prefs.dashcamDisplayName = "Front cam"
        prefs.dashcamWarnWhenOff = true
        prefs.haIntent = HaIntent.YES
        prefs.walkAwayAlarmEnabled = false
        prefs.walkAwayAlarmThresholdSec = 60
        prefs.adaptiveAlertsEnabled = false
        prefs.precogEnabled = true
        prefs.experimentalLateralPanning = true
        prefs.experimentalLateralPanningInvertLR = true
        prefs.closePassLoggingEnabled = true
        prefs.closePassEmitMinRangeXM = 1.5f
        prefs.closePassRiderSpeedFloorKmh = 20
        prefs.closePassClosingSpeedFloorMs = 8
        prefs.autoLightModeEnabled = true
        prefs.cameraLightDayMode = CameraLightMode.HIGH
        prefs.cameraLightNightMode = CameraLightMode.NIGHT_FLASH
        prefs.ldiEnabled = true
        prefs.eBikeOwnership = EBikeOwnership.NO
        prefs.ldiOnboardingResumePoint = true

        // A new instance reads the same backing file: proves the setters
        // persisted rather than caching in the original object.
        val s = Prefs(context).snapshot()
        assertTrue(s.firstRunComplete)
        assertFalse(s.serviceEnabled)
        assertEquals(80, s.alertVolume)
        assertEquals(25, s.alertMaxDistanceM)
        assertEquals(60, s.visualMaxDistanceM)
        assertEquals(0.7f, s.overlayOpacity, 0f)
        assertEquals(45, s.radarLongOfflineThresholdMinutes)
        assertEquals(60, s.radarLongOfflineCapSec)
        assertEquals(999L, s.pausedUntilEpochMs)
        assertTrue(s.devModeUnlocked)
        assertEquals(123L, s.haLastValidatedEpochMs)
        assertEquals(15, s.batteryLowThresholdPct)
        assertTrue(s.batteryShowLabels)
        assertEquals(DashcamOwnership.YES, s.dashcamOwnership)
        assertEquals("AA:BB:CC:DD:EE:FF", s.dashcamMac)
        assertEquals("Front cam", s.dashcamDisplayName)
        assertTrue(s.dashcamWarnWhenOff)
        assertEquals(HaIntent.YES, s.haIntent)
        assertFalse(s.walkAwayAlarmEnabled)
        assertEquals(60, s.walkAwayAlarmThresholdSec)
        assertFalse(s.adaptiveAlertsEnabled)
        assertTrue(s.precogEnabled)
        assertTrue(s.experimentalLateralPanning)
        assertTrue(s.experimentalLateralPanningInvertLR)
        assertTrue(s.closePassLoggingEnabled)
        assertEquals(1.5f, s.closePassEmitMinRangeXM, 0f)
        assertEquals(20, s.closePassRiderSpeedFloorKmh)
        assertEquals(8, s.closePassClosingSpeedFloorMs)
        assertTrue(s.autoLightModeEnabled)
        assertEquals(CameraLightMode.HIGH, s.cameraLightDayMode)
        assertEquals(CameraLightMode.NIGHT_FLASH, s.cameraLightNightMode)
        assertTrue(s.ldiEnabled)
        assertEquals(EBikeOwnership.NO, s.eBikeOwnership)
        assertTrue(s.ldiOnboardingResumePoint)
    }

    @Test
    fun nonSnapshotPropertiesRoundTrip() {
        // captureLogShareWarningSeen and ldiBondedAddress are real persisted
        // properties that snapshot() does not surface; round-trip them so a
        // regression in their key wiring is still caught.
        assertFalse(prefs.captureLogShareWarningSeen)
        assertNull(prefs.ldiBondedAddress)
        prefs.captureLogShareWarningSeen = true
        prefs.ldiBondedAddress = "11:22:33:44:55:66"
        val fresh = Prefs(context)
        assertTrue(fresh.captureLogShareWarningSeen)
        assertEquals("11:22:33:44:55:66", fresh.ldiBondedAddress)
    }

    @Test
    fun rangedIntegersClampBelowFloorAndAboveCeiling() {
        prefs.radarLongOfflineThresholdMinutes = 1 // floor 5
        assertEquals(5, prefs.radarLongOfflineThresholdMinutes)
        prefs.radarLongOfflineThresholdMinutes = 999 // ceiling 120
        assertEquals(120, prefs.radarLongOfflineThresholdMinutes)

        prefs.radarLongOfflineCapSec = 0
        assertEquals(5, prefs.radarLongOfflineCapSec)
        prefs.radarLongOfflineCapSec = 999
        assertEquals(120, prefs.radarLongOfflineCapSec)

        prefs.walkAwayAlarmThresholdSec = 1 // floor 15
        assertEquals(15, prefs.walkAwayAlarmThresholdSec)
        prefs.walkAwayAlarmThresholdSec = 999 // ceiling 120
        assertEquals(120, prefs.walkAwayAlarmThresholdSec)

        prefs.closePassRiderSpeedFloorKmh = 1 // floor 5
        assertEquals(5, prefs.closePassRiderSpeedFloorKmh)
        prefs.closePassRiderSpeedFloorKmh = 99 // ceiling 30
        assertEquals(30, prefs.closePassRiderSpeedFloorKmh)

        prefs.closePassClosingSpeedFloorMs = 0 // floor 3
        assertEquals(3, prefs.closePassClosingSpeedFloorMs)
        prefs.closePassClosingSpeedFloorMs = 99 // ceiling 15
        assertEquals(15, prefs.closePassClosingSpeedFloorMs)
    }

    @Test
    fun rangedFloatsClampBelowFloorAndAboveCeiling() {
        prefs.overlayOpacity = 0.1f // floor 0.5
        assertEquals(0.5f, prefs.overlayOpacity, 0f)
        prefs.overlayOpacity = 5f // ceiling 1.0
        assertEquals(1.0f, prefs.overlayOpacity, 0f)

        prefs.closePassEmitMinRangeXM = 0.0f // floor 0.3
        assertEquals(0.3f, prefs.closePassEmitMinRangeXM, 0f)
        prefs.closePassEmitMinRangeXM = 9f // ceiling 2.0
        assertEquals(2.0f, prefs.closePassEmitMinRangeXM, 0f)
    }

    @Test
    fun corruptEnumValuesFallBackToDefaults() {
        // Write a value the enum can't parse straight into the backing file,
        // bypassing the typed setters, to drive the runCatching fallback that
        // protects a read from a downgrade- or corruption-mangled pref.
        val raw = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        raw.edit()
            .putString(Prefs.KEY_DASHCAM_OWNERSHIP, "NONSENSE")
            .putString(Prefs.KEY_HA_INTENT, "???")
            .putString(Prefs.KEY_CAMERA_LIGHT_DAY_MODE, "PURPLE")
            .putString(Prefs.KEY_CAMERA_LIGHT_NIGHT_MODE, "")
            .putString(Prefs.KEY_EBIKE_OWNERSHIP, "maybe")
            .commit()
        val fresh = Prefs(context)
        assertEquals(DashcamOwnership.UNANSWERED, fresh.dashcamOwnership)
        assertEquals(HaIntent.UNSET, fresh.haIntent)
        assertEquals(CameraLightMode.DAY_FLASH, fresh.cameraLightDayMode)
        assertEquals(CameraLightMode.LOW, fresh.cameraLightNightMode)
        assertEquals(EBikeOwnership.UNANSWERED, fresh.eBikeOwnership)
    }

    @Test
    fun riderSpeedFloorIsExposedInMetresPerSecond() {
        prefs.closePassRiderSpeedFloorKmh = 18
        assertEquals(18 / 3.6f, prefs.closePassRiderSpeedFloorMs, 0.0001f)
    }

    @Test
    fun isPausedTracksThePausedUntilTimestamp() {
        prefs.pausedUntilEpochMs = System.currentTimeMillis() + 60_000L
        assertTrue(prefs.isPaused)
        prefs.pausedUntilEpochMs = System.currentTimeMillis() - 60_000L
        assertFalse(prefs.isPaused)
    }

    @Test
    fun flowEmitsCurrentSnapshotImmediately() = runTest {
        prefs.alertVolume = 77
        val first = prefs.flow.first()
        assertEquals(77, first.alertVolume)
    }

    @Test
    fun flowReemitsOnChangeButCollapsesDuplicates() = runTest {
        val seen = mutableListOf<PrefsSnapshot>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            prefs.flow.toList(seen)
        }
        runCurrent()
        assertEquals(1, seen.size) // the initial send
        prefs.serviceEnabled = false
        runCurrent()
        assertEquals(2, seen.size)
        // distinctUntilChanged: re-writing the same value emits nothing new.
        prefs.serviceEnabled = false
        runCurrent()
        assertEquals(2, seen.size)
        job.cancel()
    }

    @Test
    fun dumpAllRedactsIdentifiersButKeepsSettings() {
        prefs.dashcamMac = "AA:BB:CC:DD:EE:FF"
        prefs.dashcamDisplayName = "My dashcam"
        prefs.alertVolume = 42
        val dump = prefs.dumpAll()
        assertTrue(dump.contains("dashcam_mac=<redacted>"))
        assertTrue(dump.contains("dashcam_display_name=<redacted>"))
        assertFalse("the raw MAC must never appear in the dump", dump.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(dump.contains("My dashcam"))
        assertTrue(dump.contains("alert_volume=42"))
    }

    @Test
    fun dumpAllMarksUnsetIdentifiersAsUnset() {
        val dump = prefs.dumpAll()
        assertTrue(dump.contains("dashcam_mac=<unset>"))
        assertTrue(dump.contains("dashcam_display_name=<unset>"))
    }

    @Test
    fun redactPresenceDistinguishesUnsetFromPresent() {
        assertEquals("<unset>", Prefs.redactPresence(null))
        assertEquals("<unset>", Prefs.redactPresence(""))
        assertEquals("<unset>", Prefs.redactPresence("   "))
        assertEquals("<redacted>", Prefs.redactPresence("anything"))
    }

    private companion object {
        // Couples to Prefs' private file name; the corrupt-enum test needs to
        // write past the typed setters into the same backing store.
        const val PREFS_FILE = "bike_radar_prefs"
    }
}
