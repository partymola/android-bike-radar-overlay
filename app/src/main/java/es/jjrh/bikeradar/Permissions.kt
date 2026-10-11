// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A permission the radar service cannot start without. */
enum class MissingPermission { NEARBY_DEVICES, NOTIFICATIONS }

object Permissions {
    fun hasRequiredForService(ctx: Context): Boolean = missingForService(ctx) == null

    /** What stops the service starting, Nearby devices first when both are
     *  missing, or null when nothing does. */
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
