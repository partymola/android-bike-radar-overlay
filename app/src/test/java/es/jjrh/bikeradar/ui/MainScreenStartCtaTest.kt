// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.MissingPermission
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The home screen's Start and Grant buttons, driven through the real [ctaFor]. */
@RunWith(AndroidJUnit4::class)
class MainScreenStartCtaTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun grantAll() = shadowOf(app).grantPermissions(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    /** Composes [ctaFor] inside a graph holding the routes it can open, taps
     *  the button it returns, and gives back where the tap landed. */
    private fun tap(prefs: Prefs, serviceEnabled: Boolean, missing: MissingPermission?, expectLabel: String): String? {
        var cta: StatusCta? = null
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "main") {
                composable("main") {
                    cta = ctaFor(
                        inputs = MainStatusInputs(
                            pausedUntilEpochMs = 0L,
                            hasBond = true,
                            radarFresh = false,
                            haErrorRecent = false,
                            dashcamOwned = false,
                            dashcamWarnWhenOff = false,
                            dashcamFresh = false,
                            dashcamDisplayName = null,
                            serviceEnabled = serviceEnabled,
                            bluetoothEnabled = true,
                            missingPermission = missing,
                        ),
                        nowMs = 1_000L,
                        navController = nav,
                        ctx = app,
                        prefs = prefs,
                    )
                }
                composable("settings/permissions") {}
            }
        }
        compose.waitForIdle()
        assertEquals(expectLabel, cta?.label)
        compose.runOnUiThread { cta!!.onClick() }
        compose.waitForIdle()
        return nav.currentDestination?.route
    }

    @Test
    fun startTurnsScanningBackOnAndStartsTheService() {
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = false
        }
        grantAll()
        val landed = tap(prefs, serviceEnabled = false, missing = null, expectLabel = "Start scanning")

        assertTrue(prefs.serviceEnabled)
        assertEquals(BikeRadarService::class.java.name, shadowOf(app).peekNextStartedService()?.component?.className)
        assertEquals("main", landed)
    }

    @Test
    fun startWithoutPermissionsOpensPermissions() {
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = false
        }
        val landed = tap(prefs, serviceEnabled = false, missing = MissingPermission.NEARBY_DEVICES, expectLabel = "Start scanning")

        assertTrue("scanning stays chosen, ready for the grant", prefs.serviceEnabled)
        assertNull(shadowOf(app).peekNextStartedService())
        assertEquals("settings/permissions", landed)
    }

    @Test
    fun grantPermissionsOpensPermissions() {
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
        }
        val landed = tap(prefs, serviceEnabled = true, missing = MissingPermission.NOTIFICATIONS, expectLabel = "Grant permissions")
        assertEquals("settings/permissions", landed)
    }
}
