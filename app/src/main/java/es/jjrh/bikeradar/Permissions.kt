// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A permission the home card asks for: [NEARBY_DEVICES] to run the radar
 *  at all, [NOTIFICATIONS] for everything [ServiceNotifications] posts.
 *  Without it the walk-away alarm sounds with no Dismiss or Remind, and the
 *  notification's Pause and Resume, the ride summary, and the light-fail,
 *  bond-lost and forgot-to-lock notifications are gone. Resume stays in the
 *  app; Pause does not, since only the notification offers it. The
 *  light-fail beep still plays. */
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
        if (!notificationsGranted(ctx)) return MissingPermission.NOTIFICATIONS
        return null
    }

    /** The Notifications grant. Always true below Android 13, which has none
     *  to ask for, so the card does not ask there even when the rider has
     *  blocked the app's notifications in system settings
     *  (`preTiramisuNeverAsksForNotifications`). */
    fun notificationsGranted(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
