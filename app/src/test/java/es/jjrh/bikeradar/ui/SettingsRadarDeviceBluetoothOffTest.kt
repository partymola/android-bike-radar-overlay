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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.RadarSelection
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
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
 * With Bluetooth off Android lists nothing paired, so the radar screen must say
 * Bluetooth is off rather than ask the rider to pair a radar that is paired
 * (here a pinned one, which the service still counts as its radar).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRadarDeviceBluetoothOffTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val adapter by lazy { (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
        // The read is empty, as Android's is with the adapter off.
        shadowOf(adapter).setBondedDevices(emptySet())
        RadarSelection.pin(Prefs(app), RadarSelection.BondedRadar("AA:BB:CC:DD:EE:11", "RearVue8"))
    }

    @After
    fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun show() {
        val prefs = Prefs(app)
        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "settings/radar-device") {
                composable("settings/radar-device") { SettingsRadarDevice(navController = nav, prefs = prefs) }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun withBluetoothOffItSaysSoAndOpensBluetoothSettings() {
        shadowOf(adapter).setEnabled(false)
        show()
        composeRule.onNodeWithText("Pair your radar").assertDoesNotExist()
        composeRule.onNodeWithText("Put the radar in pair mode, then pair it in Android's Bluetooth settings.").assertDoesNotExist()
        // The card says what a tap does.
        composeRule.onNodeWithText("Tap to enable").assertIsDisplayed()
        assertNull("nothing started before the tap", shadowOf(app).nextStartedActivity)
        composeRule.onNodeWithText("Bluetooth is off").assertIsDisplayed().performClick()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity?.action)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun withBluetoothOnAnEmptyListStillAsksForPairing() {
        shadowOf(adapter).setEnabled(true)
        show()
        composeRule.onNodeWithText("Pair your radar").assertIsDisplayed()
        composeRule.onNodeWithText("Bluetooth is off").assertDoesNotExist()
    }

    @Test
    fun bluetoothTurnedOffWhileTheScreenIsOpenIsPickedUpByTheTick() {
        shadowOf(adapter).setEnabled(true)
        show()
        composeRule.onNodeWithText("Pair your radar").assertIsDisplayed()
        shadowOf(adapter).setEnabled(false)
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bluetooth is off").assertIsDisplayed()
        composeRule.onNodeWithText("Pair your radar").assertDoesNotExist()
    }
}
