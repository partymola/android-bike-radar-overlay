// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * With the radio unusable the camera card reads "Not paired", as the home row
 * does, so the screen names the cause above it: Nearby devices first, then
 * Bluetooth off, as on the radar screen and the home card. The rest of the
 * screen stays, since its settings do not need the radio.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDashcamRadioTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val adapter by lazy { (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private lateinit var nav: NavHostController

    private val bluetoothOffTitle = "Bluetooth is off"
    private val nearbyLine = "No alerts until you grant Nearby devices"

    @After
    fun tearDown() {
        BatteryStateBus.clearForTest()
    }

    // Scan and Connect only, deliberately not Notifications, so the cases with
    // a usable radio still run with a permission missing: the card is about
    // Nearby devices, not any missing permission.
    private fun grantNearby() {
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    }

    private fun pickedCamera() = Prefs(app).apply {
        dashcamOwnership = DashcamOwnership.YES
        dashcamMac = "AA:BB:CC:DD:EE:22"
        dashcamDisplayName = "Front cam"
        walkAwayAlarmEnabled = true
    }

    private fun show(prefs: Prefs) {
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "settings/dashcam") {
                composable("settings/dashcam") { SettingsDashcam(navController = nav, prefs = prefs) }
                composable("settings/permissions") {}
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withBluetoothOffItSaysSoAndOpensBluetoothSettings() {
        grantNearby()
        shadowOf(adapter).setEnabled(false)
        show(pickedCamera())
        // The card says what a tap does, and the camera keeps the home row's word.
        composeRule.onNodeWithText("Tap to enable").assertIsDisplayed()
        composeRule.onNodeWithText("Not paired").assertIsDisplayed()
        assertNull("nothing started before the tap", shadowOf(app).nextStartedActivity)
        composeRule.onNodeWithText(bluetoothOffTitle).assertIsDisplayed().performClick()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity?.action)
        assertNull(shadowOf(app).nextStartedActivity)
        // Nothing below the card is taken away.
        composeRule.onNodeWithText("Alert if the dashcam is still on").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withNearbyDevicesMissingItAsksForThePermission() {
        shadowOf(adapter).setEnabled(true)
        show(pickedCamera())
        composeRule.onNodeWithText(bluetoothOffTitle).assertDoesNotExist()
        composeRule.onNodeWithText("Not paired").assertIsDisplayed()
        composeRule.onNodeWithText(nearbyLine).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("settings/permissions", nav.currentDestination?.route)
    }

    @Test
    fun withNearbyDevicesMissingAndBluetoothOffItAsksForThePermissionFirst() {
        // Ranked as on the home card (aMissingPermissionBeatsPausedBluetoothAndPairing).
        shadowOf(adapter).setEnabled(false)
        show(pickedCamera())
        composeRule.onNodeWithText(nearbyLine).assertIsDisplayed()
        composeRule.onNodeWithText(bluetoothOffTitle).assertDoesNotExist()
    }

    @Test
    fun withTheRadioUsableNoCauseIsShown() {
        grantNearby()
        shadowOf(adapter).setEnabled(true)
        show(pickedCamera())
        composeRule.onNodeWithText("No signal").assertIsDisplayed()
        composeRule.onNodeWithText(bluetoothOffTitle).assertDoesNotExist()
        composeRule.onNodeWithText(nearbyLine).assertDoesNotExist()
    }

    @Test
    fun bluetoothTurnedOffAndBackOnWhileTheScreenIsOpenIsPickedUpByTheTick() {
        grantNearby()
        shadowOf(adapter).setEnabled(true)
        show(pickedCamera())
        composeRule.onNodeWithText("No signal").assertIsDisplayed()
        composeRule.onNodeWithText(bluetoothOffTitle).assertDoesNotExist()

        shadowOf(adapter).setEnabled(false)
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bluetoothOffTitle).assertIsDisplayed()

        // The rider back from Bluetooth settings.
        shadowOf(adapter).setEnabled(true)
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bluetoothOffTitle).assertDoesNotExist()
        composeRule.onNodeWithText("No signal").assertIsDisplayed()
    }

    @Test
    fun nearbyDevicesGrantedWhileTheScreenIsOpenIsPickedUpByTheTick() {
        // Granted from App info with the screen left open behind it.
        shadowOf(adapter).setEnabled(true)
        show(pickedCamera())
        composeRule.onNodeWithText(nearbyLine).assertIsDisplayed()

        grantNearby()
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(nearbyLine).assertDoesNotExist()
        composeRule.onNodeWithText("No signal").assertIsDisplayed()
    }

    @Test
    fun withNoCameraPickedYetBluetoothOffIsStillNamed() {
        // The picker behind "Pick" lists nothing with Bluetooth off.
        grantNearby()
        shadowOf(adapter).setEnabled(false)
        show(Prefs(app).apply { dashcamOwnership = DashcamOwnership.YES })
        composeRule.onNodeWithText(bluetoothOffTitle).assertIsDisplayed()
        composeRule.onNodeWithText("Pick").assertIsDisplayed()
    }

    @Test
    fun aRiderWithNoCameraIsNotToldAboutBluetooth() {
        grantNearby()
        shadowOf(adapter).setEnabled(false)
        show(pickedCamera().apply { dashcamOwnership = DashcamOwnership.NO })
        composeRule.onNodeWithText("Saved dashcam").assertIsDisplayed()
        composeRule.onNodeWithText(bluetoothOffTitle).assertDoesNotExist()
    }

    @Test
    fun aCameraReadNotPairedShowsNoBattery() {
        // A reading stays fresh for a while after Bluetooth goes off; the home
        // row shows no chip beside "Not paired", so neither does this card.
        grantNearby()
        shadowOf(adapter).setEnabled(false)
        BatteryStateBus.update(BatteryEntry(slug = "front_cam", name = "Front cam", pct = 64))
        show(pickedCamera())
        composeRule.onNodeWithText("Not paired").assertIsDisplayed()
        composeRule.onNodeWithText("64%").assertDoesNotExist()
    }
}
