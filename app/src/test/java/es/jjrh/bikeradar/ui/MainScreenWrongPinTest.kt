// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The home card for a radar pinned to a paired device the app does not know
 * as a radar while one it does know is paired, on the real screens over a real
 * bonded list. Only the pin links, so without this card home reads "Waiting
 * for radar" for as long as the pin stays.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenWrongPinTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private lateinit var nav: NavHostController
    private val adapter get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = FakeOwner()

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
            pausedUntilEpochMs = 0L
            dashcamOwnership = DashcamOwnership.NO
        }
        shadowOf(adapter).setEnabled(true)
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        val radar = adapter.getRemoteDevice(RADAR).also { shadowOf(it).setName("RearVue8") }
        val buds = adapter.getRemoteDevice(BUDS).also { shadowOf(it).setName("Pixel Buds") }
        shadowOf(adapter).setBondedDevices(setOf(radar, buds))
        RadarStateBus.clear()
    }

    @After
    fun tearDown() {
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "main") {
                composable("main") { MainScreen(navController = nav, prefs = prefs) }
                composable(systemRowRoute(SystemRowTarget.RADAR)) { SettingsRadarDevice(navController = nav, prefs = prefs) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aPinOnAnotherDeviceAsksAndChoosingTheRadarSettlesIt() {
        prefs.radarMac = BUDS
        show()
        compose.onNodeWithText("Using the right radar?").assertExists()
        compose.onNodeWithText("No data from Pixel Buds. If it's your radar, turn it on").assertExists()

        compose.onNodeWithText("Choose radar").performClick()
        compose.waitForIdle()
        assertEquals("settings/radar-device", nav.currentDestination?.route)

        compose.onNodeWithText("RearVue8").performClick()
        compose.waitForIdle()
        assertEquals(RADAR, prefs.radarMac)

        compose.runOnUiThread { nav.popBackStack() }
        compose.waitForIdle()
        compose.onNodeWithText("Using the right radar?").assertDoesNotExist()
        compose.onNodeWithText("Waiting for radar").assertExists()
    }

    @Test
    fun unpairingThePinnedDeviceWhileAwayClearsTheQuestion() {
        // Back from Android's Bluetooth settings to the same screen, so only
        // the poll can notice.
        prefs.radarMac = BUDS
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MainScreen(navController = rememberNavController(), prefs = prefs)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Using the right radar?").assertExists()

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        shadowOf(adapter).setBondedDevices(setOf(adapter.getRemoteDevice(RADAR)))
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.onNodeWithText("Using the right radar?").assertDoesNotExist()
        compose.onNodeWithText("Waiting for radar").assertExists()
    }

    @Test
    fun aPinOnTheNamedRadarIsNotAsked() {
        prefs.radarMac = RADAR
        show()
        compose.onNodeWithText("Waiting for radar").assertExists()
        compose.onNodeWithText("Using the right radar?").assertDoesNotExist()
    }

    private companion object {
        const val RADAR = "AA:BB:CC:DD:EE:11"
        const val BUDS = "AA:BB:CC:DD:EE:01"
    }
}
