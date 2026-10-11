// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.Prefs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The home screen reads the permission state into its hero. */
@RunWith(AndroidJUnit4::class)
class MainScreenPermissionsHeroTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun show() {
        val prefs = Prefs(app).apply { firstRunComplete = true }
        compose.setContent { MainScreen(navController = rememberNavController(), prefs = prefs) }
        compose.waitForIdle()
    }

    @Test
    fun aDeniedPermissionShowsOnTheHomeScreen() {
        show()
        compose.onAllNodesWithText(app.getString(R.string.main_status_permissions_title)).assertCountEquals(1)
    }

    @Test
    fun everyPermissionGrantedShowsNoneOfIt() {
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        show()
        compose.onAllNodesWithText(app.getString(R.string.main_status_permissions_title)).assertCountEquals(0)
    }
}
