// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.RadarLinkState
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The home screen reads the permission state into its card, and the rest of
 * the screen agrees with it. A bonded radar and a saved dashcam are set up in
 * every test, so a row that ignores the permission has something to show.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenPermissionsHeroTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
            pausedUntilEpochMs = 0L
            dashcamOwnership = DashcamOwnership.YES
            dashcamMac = "AA:BB:CC:DD:EE:22"
            eBikeDataEnabled = true
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        shadowOf(radar).setName("RearVue8")
        shadowOf(adapter).setBondedDevices(setOf(radar))
        RadarStateBus.clear()
    }

    @After
    fun tearDown() {
        BikeRadarService.radarLinkStateForUi = null
        RadarStateBus.clear()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun grantBluetooth() = shadowOf(app).grantPermissions(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    private fun show() {
        compose.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun aDeniedPermissionShowsOnTheHomeScreen() {
        show()
        compose.onAllNodesWithText("Permissions needed").assertCountEquals(1)
    }

    @Test
    fun everyPermissionGrantedShowsNoneOfIt() {
        grantBluetooth()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        show()
        compose.onAllNodesWithText("Waiting for radar").assertCountEquals(1)
        compose.onAllNodesWithText("Permissions needed").assertCountEquals(0)
    }

    @Test
    fun withoutNearbyDevicesTheRowsBlameNothingElse() {
        // The card names the cause once. A dashcam row reading "No signal"
        // beside a radar row reading "Not paired" splits one cause in two, and
        // "Waiting for Flow" sends the rider to an app that cannot help.
        show()
        compose.onAllNodesWithText("Permissions needed").assertCountEquals(1)
        assertEquals("radar and dashcam rows", 2, count("Not paired"))
        assertEquals(0, count("No signal"))
        assertEquals(0, count("Waiting for Flow"))
        assertEquals(1, count("Needs Nearby devices"))
    }

    @Test
    fun withoutNotificationsTheRadarStartsAndTheCardSaysWhatIsLost() {
        // The service runs and beeps; what goes is the alarms' Dismiss and
        // Snooze, so the card keeps asking, with a button to the fix.
        grantBluetooth()
        show()
        assertEquals(BikeRadarService::class.java.name, shadowOf(app).peekNextStartedService()?.component?.className)
        compose.onAllNodesWithText("Alarms can't be silenced without Notifications").assertCountEquals(1)
        compose.onAllNodesWithText("Grant permissions").assertCountEquals(1)
    }

    @Test
    fun aRunningServiceIsStillAskedForNotifications() {
        grantBluetooth()
        BikeRadarService.radarLinkStateForUi = MutableStateFlow(RadarLinkState())
        show()
        compose.onAllNodesWithText("Alarms can't be silenced without Notifications").assertCountEquals(1)
    }

    @Test
    fun aRiderWhoStoppedScanningIsNotRestartedByThisScreen() {
        // The screen asks the start rule on every entry; the rule's
        // serviceEnabled gate is what keeps "Stop scanning" stopped here.
        prefs.serviceEnabled = false
        grantBluetooth()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        compose.mainClock.autoAdvance = false
        compose.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        assertNull(shadowOf(app).peekNextStartedService())
        compose.onAllNodesWithText("Service stopped").assertCountEquals(1)
    }

    @Test
    fun aGrantWhileTheScreenStaysResumedStartsTheService() {
        // Split screen: the grant happens in the other window, so this
        // activity never passes through ON_RESUME.
        compose.mainClock.autoAdvance = false
        compose.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        compose.mainClock.advanceTimeByFrame()
        assertNull(shadowOf(app).peekNextStartedService())

        grantBluetooth()
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()

        assertEquals(BikeRadarService::class.java.name, shadowOf(app).peekNextStartedService()?.component?.className)
    }
}
