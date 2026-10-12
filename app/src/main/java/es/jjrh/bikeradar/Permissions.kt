// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A permission the radar needs from the rider: [NEARBY_DEVICES] to run at
 *  all, [NOTIFICATIONS] for the alarms' Dismiss and Snooze. */
enum class MissingPermission { NEARBY_DEVICES, NOTIFICATIONS }

object Permissions {
    /** Nearby devices only. Android needs nothing more to run the service, and
     *  without Notifications it still beeps and draws the overlay, so refusing
     *  to start would cost the rider every alert (`PermissionsTest`). */
    fun hasRequiredForService(ctx: Context): Boolean = missingForService(ctx) != MissingPermission.NEARBY_DEVICES

    /** The permission the home card asks for, Nearby devices first when both
     *  are missing, or null when nothing is. */
    fun missingForService(ctx: Context): MissingPermission? {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        if (!granted(Manifest.permission.BLUETOOTH_SCAN) || !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            return MissingPermission.NEARBY_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            return MissingPermission.NOTIFICATIONS
        }
        return null
    }
}
