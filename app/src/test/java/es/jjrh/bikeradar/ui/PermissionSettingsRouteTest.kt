// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Where a refused permission sends the rider, and when the app sends them
 *  there without a second tap. */
@RunWith(RobolectricTestRunner::class)
class PermissionSettingsRouteTest {

    private val nearby = PERMISSIONS.first { Manifest.permission.BLUETOOTH_CONNECT in it.permissions }
    private val notifications = PERMISSIONS.first { Manifest.permission.POST_NOTIFICATIONS in it.permissions }

    @Test
    fun onlyARefusalWithNoDialogOpensSettings() {
        assertTrue("refused twice before: no dialog", refusedWithoutADialog(false, rationaleBefore = false, rationaleAfter = false))
        assertFalse("first refusal in a dialog", refusedWithoutADialog(false, rationaleBefore = false, rationaleAfter = true))
        assertFalse("second refusal in a dialog", refusedWithoutADialog(false, rationaleBefore = true, rationaleAfter = false))
        assertFalse("granted", refusedWithoutADialog(true, rationaleBefore = false, rationaleAfter = false))
    }

    @Test
    fun notificationsOpenTheNotificationSwitch() {
        val intent = permissionSettingsIntent(notifications, "es.jjrh.bikeradar")
        assertEquals("android.settings.APP_NOTIFICATION_SETTINGS", intent.action)
        assertEquals("es.jjrh.bikeradar", intent.getStringExtra("android.provider.extra.APP_PACKAGE"))
    }

    @Test
    fun nearbyDevicesOpensAppInfo() {
        val intent = permissionSettingsIntent(nearby, "es.jjrh.bikeradar")
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", intent.action)
        assertEquals("package:es.jjrh.bikeradar", intent.dataString)
    }
}
