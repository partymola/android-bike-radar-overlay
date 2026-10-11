// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ServiceAutoStartTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun grantAll() = shadowOf(app).grantPermissions(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    @Test
    fun `a rider who stopped scanning is not restarted`() {
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = false
        }
        grantAll()
        assertFalse(ServiceAutoStart.startIfAllowed(app, prefs))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun `a rider who has not finished setup is not started`() {
        val prefs = Prefs(app).apply {
            firstRunComplete = false
            serviceEnabled = true
        }
        grantAll()
        assertFalse(ServiceAutoStart.startIfAllowed(app, prefs))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun `a rider missing a permission is not started`() {
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
        }
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(ServiceAutoStart.startIfAllowed(app, prefs))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun `all gates open starts the radar service`() {
        val prefs = Prefs(app).apply {
            firstRunComplete = true
            serviceEnabled = true
        }
        grantAll()
        assertTrue(ServiceAutoStart.startIfAllowed(app, prefs))
        assertEquals(BikeRadarService::class.java.name, shadowOf(app).peekNextStartedService()?.component?.className)
    }
}
