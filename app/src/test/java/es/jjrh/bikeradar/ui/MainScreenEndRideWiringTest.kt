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
 * elapsedRealtime clock to the offer, and the same state to the connecting
 * card.
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
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
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

    @Test
    fun aLinkWithNoDataYetReadsConnectingOnTheCardAndTheRow() {
        BikeRadarService.radarLinkStateForUi = MutableStateFlow(RadarLinkState(radarGattActive = true))
        composeRule.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Connecting to radar").assertIsDisplayed()
        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for radar").assertDoesNotExist()
    }

    @Test
    fun thePowerCycleAdviceWaitsOutAnOrdinaryConnect() {
        // An ordinary connect must not tell the rider to power-cycle the
        // radar; one still connecting with no data at 15 s gets the advice.
        val advice = "If this doesn't change, turn your radar off and on"
        BikeRadarService.radarLinkStateForUi = MutableStateFlow(RadarLinkState(radarGattActive = true))
        composeRule.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Connecting to radar").assertIsDisplayed()
        composeRule.onNodeWithText(advice).assertDoesNotExist()

        // Whole seconds: the shadow clock creeps while Compose idles, so a
        // millisecond either side of 15 s would not hold.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(14))
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(advice).assertDoesNotExist()

        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(advice).assertIsDisplayed()
    }

    @Test
    fun eachConnectStartsThePowerCycleWaitAgain() {
        // A radar that connected, dropped out of connecting and is connecting
        // again starts a new wait rather than counting from the first.
        val advice = "If this doesn't change, turn your radar off and on"
        val link = MutableStateFlow(RadarLinkState(radarGattActive = true))
        BikeRadarService.radarLinkStateForUi = link
        composeRule.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()

        link.value = RadarLinkState()
        composeRule.waitForIdle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()

        link.value = RadarLinkState(radarGattActive = true)
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Connecting to radar").assertIsDisplayed()
        composeRule.onNodeWithText(advice).assertDoesNotExist()
    }

    @Test
    fun aRadarFailingItsSetupAfterARideIsStillAsked() {
        // Connecting and offerable at once: the question must win, or a rider
        // with no eBike loses the only way to stop the drop cue repeating.
        BikeRadarService.radarLinkStateForUi = MutableStateFlow(
            RadarLinkState(
                radarGattActive = true,
                radarOffSinceMs = SystemClock.elapsedRealtime() - 11_000L,
                sessionRadarConnectedMs = 3_000L,
            ),
        )
        composeRule.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Finished your ride?").assertIsDisplayed()
        composeRule.onNodeWithText("I've parked").assertIsDisplayed()
        composeRule.onNodeWithText("Connecting to radar").assertDoesNotExist()
    }
}
