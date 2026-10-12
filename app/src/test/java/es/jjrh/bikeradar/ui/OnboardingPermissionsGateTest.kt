// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Setup's Continue waits for Nearby devices only, on the shipped
 * [PERMISSIONS] list: the service runs without Notifications, so holding a
 * rider on this step for it would keep them from every alert.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingPermissionsGateTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
    }

    @After
    fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun showStep() {
        val prefs = Prefs(app)
        composeRule.setContent {
            UiTheme { PermissionsStep(prefs = prefs, onContinue = {}) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun continueOpensWithNearbyDevicesAloneGranted() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        showStep()

        composeRule.onNodeWithText("Continue").assertIsEnabled()
        // Marked like the overlay, the other permission the radar runs without.
        composeRule.onAllNodesWithText("Recommended", ignoreCase = true).assertCountEquals(2)
    }

    @Test
    fun continueWaitsForNearbyDevicesEvenWithNotificationsGranted() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        showStep()

        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
    }
}
