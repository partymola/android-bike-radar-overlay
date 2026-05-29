// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.CameraLightMode
import es.jjrh.bikeradar.data.Prefs

@Composable
fun SettingsCameraLight(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsCameraLightBody(navController, prefs)
    }
}

@Composable
private fun SettingsCameraLightBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    var autoEnabled by rememberSaveable { mutableStateOf(prefs.autoLightModeEnabled) }
    var dayMode by rememberSaveable { mutableStateOf(prefs.cameraLightDayMode) }
    var nightMode by rememberSaveable { mutableStateOf(prefs.cameraLightNightMode) }
    var dayPickerOpen by rememberSaveable { mutableStateOf(false) }
    var nightPickerOpen by rememberSaveable { mutableStateOf(false) }

    // When auto-mode is on but approximate location is denied, the
    // sunrise/sunset calc silently falls back to London. Surface the
    // grant prompt right here (it's also in onboarding + Settings ->
    // Permissions, but an existing rider who never re-runs onboarding
    // would otherwise never be told). Reuses PermissionCard for the
    // request + rationale + permanent-denial deeplink; shown only
    // while it's actionable (auto-mode on AND not yet granted).
    //
    // The body owns the location `PermissionCard` (and thus its
    // permission-launcher); the stateless [SettingsCameraLightContent]
    // leaf takes that card as a `locationCard` slot so snapshot tests
    // can substitute a launcher-free [PermissionCardContent].
    val locSpec = remember {
        PERMISSIONS.first { Manifest.permission.ACCESS_COARSE_LOCATION in it.permissions }
    }
    var locPermTick by rememberSaveable { mutableStateOf(0) }
    val locGranted = remember(locPermTick) { isSpecGranted(ctx, locSpec) }

    SettingsCameraLightContent(
        onBack = { navController.popBackStack() },
        autoEnabled = autoEnabled,
        dayMode = dayMode,
        nightMode = nightMode,
        locGranted = locGranted,
        onAutoChanged = { v ->
            autoEnabled = v
            prefs.autoLightModeEnabled = v
        },
        onDayClick = { dayPickerOpen = true },
        onNightClick = { nightPickerOpen = true },
        locationCard = {
            PermissionCard(locSpec, locGranted, onChanged = { locPermTick++ })
        },
    )

    if (dayPickerOpen) {
        ModePickerDialog(
            title = "Daytime mode",
            current = dayMode,
            onSelect = {
                dayMode = it
                prefs.cameraLightDayMode = it
                dayPickerOpen = false
            },
            onDismiss = { dayPickerOpen = false },
        )
    }
    if (nightPickerOpen) {
        ModePickerDialog(
            title = "Night mode",
            current = nightMode,
            onSelect = {
                nightMode = it
                prefs.cameraLightNightMode = it
                nightPickerOpen = false
            },
            onDismiss = { nightPickerOpen = false },
        )
    }
}

/**
 * Stateless leaf - wraps the screen chrome (header, the auto-mode toggle
 * row group, and the conditional location re-grant card) from
 * pre-resolved state. Tests can call this without a `LocalContext`, a
 * permission launcher, or an Activity: grant state is pre-resolved and
 * the location card is provided as a [locationCard] slot (snapshot tests
 * pass a launcher-free [PermissionCardContent]; the real body passes a
 * stateful [PermissionCard]).
 *
 * The `autoEnabled && !locGranted` visibility gate for the location card
 * lives here so a golden can exercise the M7 re-grant surface.
 */
@Composable
internal fun SettingsCameraLightContent(
    onBack: () -> Unit,
    autoEnabled: Boolean,
    dayMode: CameraLightMode,
    nightMode: CameraLightMode,
    locGranted: Boolean,
    onAutoChanged: (Boolean) -> Unit = {},
    onDayClick: () -> Unit = {},
    onNightClick: () -> Unit = {},
    locationCard: @Composable () -> Unit = {},
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader("Dashcam light auto-mode", onBack = onBack)

            SettingsRowGroup {
                SettingsToggleRow(
                    title = "Auto dashcam light mode",
                    subtitle = "Set light mode at power-on and at sunset",
                    checked = autoEnabled,
                    onCheckedChange = onAutoChanged,
                    leadingIcon = Icons.Default.WbSunny,
                    leadingTint = br.brand,
                    isLast = false,
                )
                SettingsRow(
                    icon = Icons.Default.LightMode,
                    iconTint = if (autoEnabled) br.brand else br.fgMuted,
                    title = "Daytime mode",
                    subtitle = dayMode.displayName(),
                    onClick = onDayClick,
                    chevron = false,
                    clickable = autoEnabled,
                    enabled = autoEnabled,
                    isLast = false,
                )
                SettingsRow(
                    icon = Icons.Default.DarkMode,
                    iconTint = if (autoEnabled) br.brand else br.fgMuted,
                    title = "Night mode",
                    subtitle = "${nightMode.displayName()} - applied at local sunset",
                    onClick = onNightClick,
                    chevron = false,
                    clickable = autoEnabled,
                    enabled = autoEnabled,
                    isLast = true,
                )
            }

            if (autoEnabled && !locGranted) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    locationCard()
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ModePickerDialog(
    title: String,
    current: CameraLightMode,
    onSelect: (CameraLightMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                CameraLightMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Text(text = mode.displayName(), fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
