// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the Dashcam settings screen using the
 * stateless [SettingsDashcamContent] leaf so the test does not depend
 * on Prefs, the battery bus, or `NotificationManager`.
 *
 * Variants cover the three ownership states the screen renders: NO
 * (top toggle only), YES with no device picked (toggle + Pick card),
 * and YES with a device picked (full set: device card, Behaviour
 * toggle, Walk-away alarm + DnD row + threshold slider).
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsDashcamSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun ownershipNo() {
        paparazzi.snapshot {
            UiTheme {
                SettingsDashcamContent(
                    navController = rememberNavController(),
                    ownership = DashcamOwnership.NO,
                    dashcamMac = null,
                    dashcamDisplayName = null,
                    dashcamWarnWhenOff = false,
                    dashcamConnected = false,
                    dashcamBatteryPct = null,
                    walkAwayAlarmEnabled = false,
                    walkAwayThreshold = 30,
                    canBypassDnd = false,
                    onOwnershipChange = {},
                    onPickDeviceClick = {},
                    onWarnWhenOffChange = {},
                    onWalkAwayEnabledChange = {},
                    onWalkAwayThresholdChange = {},
                    onWalkAwayThresholdFinished = {},
                    onOverrideDndClick = {},
                )
            }
        }
    }

    @Test
    fun ownershipYesNotPicked() {
        paparazzi.snapshot {
            UiTheme {
                SettingsDashcamContent(
                    navController = rememberNavController(),
                    ownership = DashcamOwnership.YES,
                    dashcamMac = null,
                    dashcamDisplayName = null,
                    dashcamWarnWhenOff = false,
                    dashcamConnected = false,
                    dashcamBatteryPct = null,
                    walkAwayAlarmEnabled = false,
                    walkAwayThreshold = 30,
                    canBypassDnd = false,
                    onOwnershipChange = {},
                    onPickDeviceClick = {},
                    onWarnWhenOffChange = {},
                    onWalkAwayEnabledChange = {},
                    onWalkAwayThresholdChange = {},
                    onWalkAwayThresholdFinished = {},
                    onOverrideDndClick = {},
                )
            }
        }
    }

    @Test
    fun ownershipYesPicked() {
        paparazzi.snapshot {
            UiTheme {
                SettingsDashcamContent(
                    navController = rememberNavController(),
                    ownership = DashcamOwnership.YES,
                    dashcamMac = "00:11:22:33:44:55",
                    dashcamDisplayName = "Front cam",
                    dashcamWarnWhenOff = true,
                    dashcamConnected = true,
                    dashcamBatteryPct = 64,
                    walkAwayAlarmEnabled = true,
                    walkAwayThreshold = 30,
                    canBypassDnd = false,
                    onOwnershipChange = {},
                    onPickDeviceClick = {},
                    onWarnWhenOffChange = {},
                    onWalkAwayEnabledChange = {},
                    onWalkAwayThresholdChange = {},
                    onWalkAwayThresholdFinished = {},
                    onOverrideDndClick = {},
                )
            }
        }
    }
}
