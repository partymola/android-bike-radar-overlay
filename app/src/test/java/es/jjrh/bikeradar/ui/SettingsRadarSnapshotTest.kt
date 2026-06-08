// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden for the Alerts screen. Renders the
 * stateless [SettingsRadarContent] leaf with the production default
 * values so every section header - Alerts, Overlay, Adaptive, Battery
 * warnings, Close-pass logging, Danger zone - is on screen exactly
 * once.
 *
 * Close-pass logging stays off so the nested-card slider stack stays
 * collapsed; that keeps the snapshot bounded without losing coverage
 * of the section header itself.
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
            UiTheme {
                SettingsRadarContent(
                    navController = rememberNavController(),
                    haConfigured = false,
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
    }
}
