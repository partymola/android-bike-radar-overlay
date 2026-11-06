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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * With the radio unusable the quick-status chips and Connections rows read
 * "Not paired", so Settings home names the cause once above them: Nearby
 * devices first, alone, then the Bluetooth-off banner, as on the radar and
 * dashcam screens.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsHomeRadioTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val adapter by lazy { (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private lateinit var nav: NavHostController

    private val bannerTitle = "Bluetooth off"
    private val nearbyLine = "No alerts until you grant Nearby devices"

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
    }

    @After
    fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
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

    private fun show() {
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            dashcamOwnership = DashcamOwnership.YES
            dashcamMac = "AA:BB:CC:DD:EE:22"
        }
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "settings") {
                composable("settings") { SettingsScreen(navController = nav, prefs = prefs) }
                composable("settings/permissions") {}
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withNearbyDevicesMissingItAsksForThePermission() {
        shadowOf(adapter).setEnabled(true)
        show()
        composeRule.onNodeWithText(bannerTitle).assertDoesNotExist()
        composeRule.onNodeWithText(nearbyLine).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("settings/permissions", nav.currentDestination?.route)
    }

    @Test
    fun withNearbyDevicesMissingAndBluetoothOffItAsksForThePermissionAlone() {
        shadowOf(adapter).setEnabled(false)
        show()
        composeRule.onNodeWithText(nearbyLine).assertIsDisplayed()
        composeRule.onNodeWithText(bannerTitle).assertDoesNotExist()
    }

    @Test
    fun withTheGrantAndBluetoothOffTheBannerStaysAndOpensBluetoothSettings() {
        grantNearby()
        shadowOf(adapter).setEnabled(false)
        show()
        composeRule.onNodeWithText(nearbyLine).assertDoesNotExist()
        assertNull("nothing started before the tap", shadowOf(app).nextStartedActivity)
        composeRule.onNodeWithText(bannerTitle).assertIsDisplayed().performClick()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity?.action)
    }

    @Test
    fun withTheRadioUsableNoCauseIsShown() {
        grantNearby()
        shadowOf(adapter).setEnabled(true)
        show()
        composeRule.onNodeWithText(bannerTitle).assertDoesNotExist()
        composeRule.onNodeWithText(nearbyLine).assertDoesNotExist()
    }

    @Test
    fun aGrantOnPermissionsClearsTheCardOnTheWayBack() {
        // The path the card itself opens: tap, grant, back.
        shadowOf(adapter).setEnabled(true)
        show()
        composeRule.onNodeWithText(nearbyLine).performClick()
        composeRule.waitForIdle()
        grantNearby()
        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
        assertEquals("settings", nav.currentDestination?.route)
        composeRule.onNodeWithText(nearbyLine).assertDoesNotExist()
    }

    @Test
    fun nearbyDevicesGrantedWhileTheScreenIsOpenIsPickedUpByThePoll() {
        // Granted from the other half of a split screen, with this one still
        // resumed.
        shadowOf(adapter).setEnabled(true)
        show()
        composeRule.onNodeWithText(nearbyLine).assertIsDisplayed()

        grantNearby()
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(nearbyLine).assertDoesNotExist()
    }
}
