// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.Manifest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A refused permission's button names the page [permissionSettingsIntent]
 *  opens for it. */
@RunWith(AndroidJUnit4::class)
class PermissionCardLabelTest {

    @get:Rule val compose = createComposeRule()

    private fun show(permission: String) {
        val spec = PERMISSIONS.first { permission in it.permissions }
        compose.setContent {
            UiTheme { PermissionCardContent(spec = spec, granted = false, permanentlyDenied = true, onAction = {}) }
        }
        compose.waitForIdle()
    }

    @Test
    fun refusedNotificationsOpenTheNotificationSettings() {
        show(Manifest.permission.POST_NOTIFICATIONS)
        compose.onNodeWithText("Open notification settings").assertExists()
        compose.onNodeWithText("Open App info").assertDoesNotExist()
    }

    @Test
    fun refusedNearbyDevicesOpensAppInfo() {
        show(Manifest.permission.BLUETOOTH_CONNECT)
        compose.onNodeWithText("Open App info").assertExists()
    }
}
