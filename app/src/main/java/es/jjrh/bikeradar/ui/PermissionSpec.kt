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
// Android 12+ treats them as one NEARBY_DEVICES permission group with a single
// system prompt. The app is BLE-central only (radar, dashcam, eBike), so it
// needs no BLUETOOTH_ADVERTISE.
internal data class PermissionSpec(
    val permissions: List<String>,
    val title: String,
    val rationale: String,
    val required: Boolean,
    // Optional badge text shown next to the title. null = no badge.
    val markLabel: String? = null,
)

internal val PERMISSIONS = buildList {
    add(
        PermissionSpec(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            "Nearby devices",
            "Scan for and connect to your radar, dashcam and eBike over Bluetooth.",
            required = true,
        ),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionSpec(
                listOf(Manifest.permission.POST_NOTIFICATIONS),
                "Notifications",
                "Post the silent service notification and any ride alerts.",
                required = true,
            ),
        )
    }
    // Overlay permission is gated by a separate Settings intent, so onboarding
    // does not block on it; the app still works (alerts only) without it.
    // Marked Recommended rather than Optional because without it the user
    // never sees the overlay, which is the app's primary surface.
    add(
        PermissionSpec(
            emptyList(),
            "Draw over other apps",
            "Draw the radar overlay on top of whatever's on screen. Without this, alerts still play but you won't see the overlay.",
            required = false,
            markLabel = "Recommended",
        ),
    )
    // Approximate location, read once per ride for the dashcam-light
    // day/night auto-mode (sunrise/sunset). Genuinely optional: skipped or
    // denied, SunsetCalculator falls back to London, so the app is fully
    // usable without it. Surfaced here so onboarding and Settings ->
    // Permissions both prompt for it; it was previously manifest-only, so
    // users had to grant it through Android system settings.
    add(
        PermissionSpec(
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            "Approximate location",
            "Used once per ride to compute accurate sunrise/sunset for the dashcam-light auto-mode. Skip it and sunset is estimated for London.",
            required = false,
            markLabel = "Optional",
        ),
    )
}

internal fun isSpecGranted(ctx: Context, spec: PermissionSpec): Boolean = if (spec.permissions.isEmpty()) {
    Settings.canDrawOverlays(ctx)
} else {
    spec.permissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
}
