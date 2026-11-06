// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.ServiceNotifications
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The Dashcam screen's Do Not Disturb row reads the shared walk-away channel,
 *  and reads it again when the rider comes back from system settings. */
@RunWith(RobolectricTestRunner::class)
class SettingsDashcamDndTest {

    @get:Rule val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun theDndRowReadsTheGrantAgainWhenTheRiderComesBack() {
        val prefs = Prefs(app).apply {
            dashcamOwnership = DashcamOwnership.YES
            dashcamMac = "11:22:33:44:55:66"
            dashcamDisplayName = "Front Cam"
            walkAwayAlarmEnabled = true
        }
        ServiceNotifications(app) { prefs }.ensureChannels()
        // A usable radio, so this runs without the cause card above the
        // camera (`SettingsDashcamRadioTest`).
        shadowOf((app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter).setEnabled(true)
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        val owner = FakeOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                UiTheme { SettingsDashcam(rememberNavController(), prefs) }
            }
        }
        composeRule.onNodeWithText(app.getString(R.string.settings_walkaway_dnd_tap)).performScrollTo().assertExists()

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        val nm = app.getSystemService(NotificationManager::class.java)
        val ch = nm.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID)
        ch.setBypassDnd(true)
        nm.createNotificationChannel(ch)
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        composeRule.onNodeWithText(app.getString(R.string.settings_walkaway_dnd_allowed)).performScrollTo().assertExists()
    }
}
