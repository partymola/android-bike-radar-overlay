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
            "Show a silent status notification while the service runs.",
            required = true,
        ))
    }
    add(PermissionSpec(
        emptyList(),
        "Draw over other apps",
        "Draw the radar overlay on top of your cycling app. Without this the alerts still play, but you won't see the overlay.",
        required = false,
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
