// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.RadarLinkState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Composes the real home screen against a published radar link state. The
 * other End ride tests drive [ctaFor] and [es.jjrh.bikeradar.RadarLinkStatus]
 * directly, so only this one sees the screen hand the live state and the
 * elapsedRealtime clock to the offer.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenEndRideWiringTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
            pausedUntilEpochMs = 0L
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        shadowOf(radar).setName("RearVue8")
        shadowOf(adapter).setBondedDevices(setOf(radar))
        RadarStateBus.clear()
        // Moves elapsedRealtime away from the other clocks, so a wiring that
        // ages the off-instant on the wrong one reads an age of an hour.
        ShadowSystemClock.simulateDeepSleep(Duration.ofHours(1))
    }

    @After
    fun tearDown() {
        BikeRadarService.radarLinkStateForUi = null
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun showWith(downForMs: Long, bikeLocked: Boolean) {
        BikeRadarService.radarLinkStateForUi = MutableStateFlow(
            RadarLinkState(
                radarOffSinceMs = SystemClock.elapsedRealtime() - downForMs,
                sessionRadarConnectedMs = 3_000L,
                bikeLocked = bikeLocked,
            ),
        )
        composeRule.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
    }

    @Test
    fun aRadarDownAfterARideIsAsked() {
        showWith(downForMs = 11_000L, bikeLocked = false)
        composeRule.onNodeWithText("Finished your ride?").assertIsDisplayed()
        composeRule.onNodeWithText("I've parked").assertIsDisplayed()
    }

    @Test
    fun aLockedBikeIsNotAsked() {
        showWith(downForMs = 11_000L, bikeLocked = true)
        composeRule.onNodeWithText("Waiting for radar").assertIsDisplayed()
        composeRule.onNodeWithText("Finished your ride?").assertDoesNotExist()
        composeRule.onNodeWithText("I've parked").assertDoesNotExist()
    }

    @Test
    fun aBriefDropIsNotAsked() {
        showWith(downForMs = 5_000L, bikeLocked = false)
        composeRule.onNodeWithText("Waiting for radar").assertIsDisplayed()
        composeRule.onNodeWithText("I've parked").assertDoesNotExist()
    }
}
