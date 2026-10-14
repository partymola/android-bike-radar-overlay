// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.ClosePassStateBus
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.RideHistoryRecord
import es.jjrh.bikeradar.RideHistoryStore
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
import java.io.File

/**
 * Ride history is kept for every rider, so the close-pass card must lead to it
 * whenever a ride is stored, not only once close-pass counting is on.
 */
@RunWith(RobolectricTestRunner::class)
class ClosePassHistoryLinkTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var opened = 0

    @Before fun clean() {
        ClosePassStateBus.reset()
        RadarStateBus.clear()
        historyFile().delete()
    }

    @After fun tidy() {
        historyFile().delete()
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun historyFile() = File(File(app.getExternalFilesDir(null), RideHistoryStore.HISTORY_DIR), RideHistoryStore.FILE_NAME)

    private fun storeOneRide() {
        RideHistoryStore({ app.getExternalFilesDir(null) }).append(
            RideHistoryRecord(
                startedAtMs = 1_000L,
                endedAtMs = 2_000L,
                overtakes = 3,
                closePasses = 0,
                grazingPasses = 0,
                hgvClosePasses = 0,
                peakClosingKmh = null,
                closingSpeedP90Kmh = null,
                minLateralClearanceM = null,
                distanceKm = 7.4f,
                exposureSeconds = 60L,
                alertsPerKm = null,
                tightestPassClearanceM = null,
                tightestPassClosingKmh = null,
            ),
        )
    }

    private fun card(rideStored: Boolean) {
        composeRule.setContent {
            UiTheme { ClosePassStatsCard(loggingEnabled = false, rideStored = rideStored, rangeOnly = false, onClick = { opened++ }) }
        }
        composeRule.waitForIdle()
    }

    private fun homeScreen() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
            pausedUntilEpochMs = 0L
            closePassLoggingEnabled = false
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        shadowOf(radar).setName("RearVue8")
        shadowOf(adapter).setBondedDevices(setOf(radar))
        composeRule.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
    }

    @Test
    fun `a stored ride opens history with counting off`() {
        card(rideStored = true)
        composeRule.onNodeWithText("History ›").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `no stored ride and counting off offers no link and no tap target`() {
        card(rideStored = false)
        composeRule.onAllNodesWithText("History ›").assertCountEquals(0)
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `the home screen offers history when a ride is already stored`() {
        storeOneRide()
        homeScreen()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("History ›").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a ride saved while the home screen is open shows the link without leaving it`() {
        homeScreen()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("History ›").assertCountEquals(0)

        storeOneRide()
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("History ›").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
