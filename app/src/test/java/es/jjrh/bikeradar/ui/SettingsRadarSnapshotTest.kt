// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Alerts screen, rendering the stateless
 * [SettingsRadarContent] leaf with production default values.
 *
 * [defaults] locks the top of the screen (Alerts -> Overlay -> Adaptive ->
 * Connection) at the standard viewport. The close-pass section sits below
 * that fold, so [closePassSectionNoHa] / [closePassSectionWithHa] render at
 * a taller viewport to pin the "Count close passes" toggle - its title, both
 * subtitle branches (standalone vs the "...and sent to Home Assistant"
 * variant), and that it is enabled regardless of HA. Logging stays off so the
 * nested slider stack stays collapsed and the goldens stay bounded.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsRadarSnapshotTest {

    @Test
    fun defaults() {
        captureRoboImage {
            UiTheme { RadarContent(haConfigured = false) }
        }
    }

    @Test
    @Config(qualifiers = "w448dp-h2200dp-xxhdpi")
    fun closePassSectionNoHa() {
        captureRoboImage {
            UiTheme { RadarContent(haConfigured = false) }
        }
    }

    @Test
    @Config(qualifiers = "w448dp-h2200dp-xxhdpi")
    fun closePassSectionWithHa() {
        captureRoboImage {
            UiTheme { RadarContent(haConfigured = true) }
        }
    }

    @Composable
    private fun RadarContent(haConfigured: Boolean) {
        SettingsRadarContent(
            navController = rememberNavController(),
            haConfigured = haConfigured,
            serviceEnabled = true,
            alertVol = 50,
            onAlertVolChange = {},
            onAlertVolFinished = {},
            alertDist = 20,
            onAlertDistChange = {},
            onAlertDistFinished = {},
            visualDist = 50,
            onVisualDistChange = {},
            onVisualDistFinished = {},
            overlayOpacity = 1.0f,
            onOverlayOpacityChange = {},
            onOverlayOpacityFinished = {},
            adaptive = true,
            onAdaptiveChange = {},
            urgentLowSpeed = true,
            onUrgentLowSpeedChange = {},
            batteryThreshold = 20,
            onBatteryThresholdChange = {},
            onBatteryThresholdFinished = {},
            batteryShowLabels = false,
            onBatteryShowLabelsChange = {},
            closePassLogging = false,
            onClosePassLoggingChange = {},
            closePassEmitMinX = 1.0f,
            onClosePassEmitMinXChange = {},
            onClosePassEmitMinXFinished = {},
            closePassRiderFloor = 15,
            onClosePassRiderFloorChange = {},
            onClosePassRiderFloorFinished = {},
            closePassClosingFloor = 6,
            onClosePassClosingFloorChange = {},
            onClosePassClosingFloorFinished = {},
            radarLongOfflineThreshold = 30,
            onRadarLongOfflineThresholdChange = {},
            onRadarLongOfflineThresholdFinished = {},
            radarLongOfflineCap = 30,
            onRadarLongOfflineCapChange = {},
            onRadarLongOfflineCapFinished = {},
            bannerPersistent = false,
            onBannerPersistentChange = {},
            onStopScanningClick = {},
        )
    }
}
