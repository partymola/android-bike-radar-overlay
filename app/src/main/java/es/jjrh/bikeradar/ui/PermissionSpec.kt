// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

// `permissions` is the list of runtime perms to request. Empty means the
// special overlay permission, routed via Settings intent. BLUETOOTH_SCAN +
// BLUETOOTH_CONNECT are grouped into one "Nearby devices" card because
// Android 12+ treats them as one permission group with a single system prompt.
internal data class PermissionSpec(
    val permissions: List<String>,
    val title: String,
    val rationale: String,
    val required: Boolean,
    // Optional badge text shown next to the title. null = no badge.
    val markLabel: String? = null,
)

internal val PERMISSIONS = buildList {
    add(PermissionSpec(
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
        "Nearby devices",
        "Scan for and connect to your radar and dashcam over Bluetooth.",
        required = true,
    ))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionSpec(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            "Notifications",
            "Post the silent service notification and any ride alerts.",
            required = true,
        ))
    }
    // Overlay permission is gated by a separate Settings intent, so onboarding
    // does not block on it; the app still works (alerts only) without it.
    // Marked Recommended rather than Optional because without it the user
    // never sees the overlay, which is the app's primary surface.
    add(PermissionSpec(
        emptyList(),
        "Draw over other apps",
        "Draw the radar overlay on top of whatever's on screen. Without this, alerts still play but you won't see the overlay.",
        required = false,
        markLabel = "Recommended",
    ))
}

internal fun isSpecGranted(ctx: Context, spec: PermissionSpec): Boolean {
    return if (spec.permissions.isEmpty()) {
        Settings.canDrawOverlays(ctx)
    } else {
        spec.permissions.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
