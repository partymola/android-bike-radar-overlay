// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.data.DashcamOwnership
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Dashcam settings screen using the
 * stateless [SettingsDashcamContent] leaf so the test does not depend
 * on Prefs, the battery bus, or `NotificationManager`.
 *
 * Variants cover the three ownership states the screen renders: NO
 * (top toggle only), YES with no device picked (toggle + Pick card),
 * and YES with a device picked (full set: device card, Behaviour
 * toggle, Walk-away alarm + DnD row + threshold slider).
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsDashcamSnapshotTest {

    @Test
    fun ownershipNo() {
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
