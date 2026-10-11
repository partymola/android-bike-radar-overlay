// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
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
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * With Nearby devices denied the bonded list reads empty, so the radar screen
 * the home row opens must ask for the permission, not send the rider to pair a
 * radar that is already paired.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarDevicePermissionTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(emptySet())
    }

    @After
    fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun show() {
        val prefs = Prefs(app)
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "settings/radar-device") {
                composable("settings/radar-device") { SettingsRadarDevice(navController = nav, prefs = prefs) }
                composable("settings/permissions") {}
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withoutNearbyDevicesItAsksForThePermission() {
        show()
        composeRule.onNodeWithText("Pair your radar").assertDoesNotExist()
        composeRule.onNodeWithText("No alerts until you grant Nearby devices").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("settings/permissions", nav.currentDestination?.route)
    }

    @Test
    fun withNearbyDevicesAnEmptyListAsksForPairing() {
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
        show()
        composeRule.onNodeWithText("Pair your radar").assertIsDisplayed()
        composeRule.onNodeWithText("Permissions needed").assertDoesNotExist()
    }
}
