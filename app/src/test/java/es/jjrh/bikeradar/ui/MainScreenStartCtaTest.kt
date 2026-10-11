// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The home screen's Start button, driven through the real [ctaFor]. */
@RunWith(AndroidJUnit4::class)
class MainScreenStartCtaTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun startTurnsScanningBackOnAndStartsTheService() {
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = false
        }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        var cta: StatusCta? = null
        compose.setContent {
            cta = ctaFor(
                inputs = MainStatusInputs(
                    firstRunComplete = true,
                    pausedUntilEpochMs = 0L,
                    hasBond = true,
                    radarFresh = false,
                    haErrorRecent = false,
                    dashcamOwned = false,
                    dashcamWarnWhenOff = false,
                    dashcamFresh = false,
                    dashcamDisplayName = null,
                    serviceEnabled = false,
                    bluetoothEnabled = true,
                ),
                nowMs = 1_000L,
                navController = rememberNavController(),
                ctx = app,
                prefs = prefs,
            )
        }
        compose.waitForIdle()
        assertEquals("Start scanning", cta?.label)
        cta!!.onClick()

        assertTrue(prefs.serviceEnabled)
        assertEquals(BikeRadarService::class.java.name, shadowOf(app).peekNextStartedService()?.component?.className)
    }
}
