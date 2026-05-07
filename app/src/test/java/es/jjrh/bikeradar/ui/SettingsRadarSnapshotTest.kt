// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi golden for the Radar & alerts screen. Renders the
 * stateless [SettingsRadarContent] leaf with the production default
 * values so every section header — Alerts, Overlay, Adaptive, Battery
 * warnings, Close-pass logging, Danger zone — is on screen exactly
 * once.
 *
 * Close-pass logging stays off so the nested-card slider stack stays
 * collapsed; that keeps the snapshot bounded without losing coverage
 * of the section header itself.
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsRadarSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun defaults() {
        paparazzi.snapshot {
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
                    onStopScanningClick = {},
                )
            }
        }
    }
}
