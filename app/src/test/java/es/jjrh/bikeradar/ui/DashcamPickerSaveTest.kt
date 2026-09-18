// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Composes the real [DashcamPickerSheet] against a shadow-bonded camera and
 * drives Save. The picker golden renders the stateless leaf, so nothing else
 * reaches what Save writes.
 */
@RunWith(RobolectricTestRunner::class)
class DashcamPickerSaveTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mac = "AA:BB:CC:DD:EE:22"
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        prefs = Prefs(app)
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val camera = adapter.getRemoteDevice(mac)
        shadowOf(camera).setName("VUE-12345")
        shadowOf(adapter).setBondedDevices(setOf(camera))
    }

    private fun showThePicker(fromOnboarding: Boolean = false) {
        composeRule.setContent {
            DashcamPickerSheet(navController = rememberNavController(), prefs = prefs, fromOnboarding = fromOnboarding)
        }
        composeRule.waitForIdle()
    }

    private fun tap(text: String) {
        composeRule.onNodeWithText(text).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun savingNoneStopsRememberingTheCamera() {
        prefs.dashcamOwnership = DashcamOwnership.YES
        prefs.dashcamMac = mac
        prefs.dashcamDisplayName = "VUE-12345"
        prefs.dashcamWarnWhenOff = true
        showThePicker()

        tap("None - I don't have one")
        tap("Save")

        assertNull(prefs.dashcamMac)
        assertNull("a name with no device behind it would still be shown", prefs.dashcamDisplayName)
        assertFalse("the warning was a choice about that camera", prefs.dashcamWarnWhenOff)
        assertEquals("Save writes the pick, never the ownership switch", DashcamOwnership.YES, prefs.dashcamOwnership)
    }

    @Test
    fun aCameraPickedDuringOnboardingStartsWithTheWarningOn() {
        showThePicker(fromOnboarding = true)

        tap("VUE-12345")
        tap("Save")

        assertTrue(prefs.dashcamWarnWhenOff)
    }

    @Test
    fun savingACameraRemembersItAndAnswersTheOwnershipQuestion() {
        showThePicker()

        tap("VUE-12345")
        tap("Save")

        assertEquals(mac, prefs.dashcamMac)
        assertEquals("VUE-12345", prefs.dashcamDisplayName)
        assertEquals(DashcamOwnership.YES, prefs.dashcamOwnership)
        assertFalse("only onboarding turns the warning on for the rider", prefs.dashcamWarnWhenOff)
    }
}
