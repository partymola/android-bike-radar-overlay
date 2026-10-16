// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The not-paired card's Pair button, on the real home screen over a real
 * bonded list. It opens the app's radar screen only when that screen has a
 * paired device to offer as the radar, and the subtitle says which of the two
 * the tap does.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenPairCtaTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private val adapter get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

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
        RadarStateBus.clear()
    }

    @After
    fun tearDown() {
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun bond(vararg devices: Pair<String, String>) {
        val bonded = devices.map { (mac, name) ->
            adapter.getRemoteDevice(mac).also { shadowOf(it).setName(name) }
        }
        shadowOf(adapter).setBondedDevices(bonded.toSet<BluetoothDevice>())
    }

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = FakeOwner()
    private lateinit var nav: NavHostController

    /** Home and the real radar screen in one graph, under a lifecycle the
     *  test can move. */
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = "main") {
                    composable("main") { MainScreen(navController = nav, prefs = prefs) }
                    composable(systemRowRoute(SystemRowTarget.RADAR)) { SettingsRadarDevice(navController = nav, prefs = prefs) }
                }
            }
        }
    }

    /** Checks the card's subtitle, taps Pair, and returns the route it
     *  landed on. */
    private fun tapPair(expectSubtitle: String): String? {
        compose.waitForIdle()
        compose.onNodeWithText("Radar not paired").assertExists()
        compose.onNodeWithText(expectSubtitle).assertExists()
        compose.onNodeWithText("Pair").performClick()
        compose.waitForIdle()
        return nav.currentDestination?.route
    }

    @Test
    fun anotherPairedDeviceOpensTheRadarScreen() {
        prefs.dashcamMac = "AA:BB:CC:DD:EE:02"
        bond(
            "AA:BB:CC:DD:EE:01" to "Pixel Buds",
            "AA:BB:CC:DD:EE:02" to "Dashcam",
            "AA:BB:CC:DD:EE:03" to "smart system eBike",
        )
        show()
        val landed = tapPair("Pair it, or pick it if it's already paired")
        assertEquals("settings/radar-device", landed)
        assertNull(shadowOf(app).nextStartedActivity)
        // The screen it opened offers the same devices home counted.
        compose.onNodeWithText("Already paired your radar?").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pixel Buds").assertExists()
        compose.onNodeWithText("Dashcam").assertDoesNotExist()
        compose.onNodeWithText("smart system eBike").assertDoesNotExist()
    }

    @Test
    fun aDevicePairedWhileAwayChangesWherePairLeads() {
        // The rider back from Android's Bluetooth settings, to the same screen.
        bond()
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Pair it in Android's Bluetooth settings").assertExists()

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        bond("AA:BB:CC:DD:EE:01" to "Pixel Buds")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true

        assertEquals("settings/radar-device", tapPair("Pair it, or pick it if it's already paired"))
    }

    @Test
    fun nothingPairedOpensAndroidBluetooth() {
        bond()
        show()
        val landed = tapPair("Pair it in Android's Bluetooth settings")
        assertEquals("main", landed)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity?.action)
    }

    @Test
    fun devicesTheRadarScreenWouldNotOfferOpenAndroidBluetooth() {
        // The saved dashcam, the eBike and a front camera are never offered as
        // the radar there, so sending the rider there would show them nothing
        // to pick. The camera's name misses the radar heuristic and is one the
        // front-camera matcher accepts.
        prefs.dashcamMac = "AA:BB:CC:DD:EE:02"
        bond(
            "AA:BB:CC:DD:EE:02" to "Dashcam",
            "AA:BB:CC:DD:EE:03" to "smart system eBike",
            "AA:BB:CC:DD:EE:04" to "VUE-12345",
        )
        show()
        val landed = tapPair("Pair it in Android's Bluetooth settings")
        assertEquals("main", landed)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity?.action)
    }
}
