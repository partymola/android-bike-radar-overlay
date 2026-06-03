// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import es.jjrh.bikeradar.R

// `permissions` is the list of runtime perms to request. Empty means the
// special overlay permission, routed via Settings intent. BLUETOOTH_SCAN +
// BLUETOOTH_CONNECT are grouped into one "Nearby devices" card because
// Android 12+ treats them as one NEARBY_DEVICES permission group with a single
// system prompt. The app is BLE-central only (radar, dashcam, eBike), so it
// needs no BLUETOOTH_ADVERTISE.
//
// title / rationale / markLabel are carried as @StringRes ids, not finished
// strings, so this spec list can stay a plain top-level val (no Context) while
// the copy is still translatable: PermissionCardContent resolves the ids with
// stringResource at render. markLabelRes is null when there is no badge.
internal data class PermissionSpec(
    val permissions: List<String>,
    @get:StringRes val titleRes: Int,
    @get:StringRes val rationaleRes: Int,
    val required: Boolean,
    @get:StringRes val markLabelRes: Int? = null,
)

internal val PERMISSIONS = buildList {
    add(
        PermissionSpec(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            R.string.permission_nearby_title,
            R.string.permission_nearby_rationale,
            required = true,
        ),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionSpec(
                listOf(Manifest.permission.POST_NOTIFICATIONS),
                R.string.permission_notifications_title,
                R.string.permission_notifications_rationale,
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
            R.string.permission_overlay_title,
            R.string.permission_overlay_rationale,
            required = false,
            markLabelRes = R.string.permission_mark_recommended,
        ),
    )
    // Approximate location, read once per ride for the front- and radar-light
    // day/night auto-modes (sunrise/sunset). Genuinely optional: skipped or
    // denied, SunsetCalculator falls back to London, so the app is fully
    // usable without it. Surfaced here so onboarding and Settings ->
    // Permissions both prompt for it; it was previously manifest-only, so
    // users had to grant it through Android system settings.
    add(
        PermissionSpec(
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            R.string.permission_location_title,
            R.string.permission_location_rationale,
            required = false,
            markLabelRes = R.string.common_optional,
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
