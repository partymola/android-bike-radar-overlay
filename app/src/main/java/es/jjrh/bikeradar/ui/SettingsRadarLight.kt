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
import es.jjrh.bikeradar.RadarLightMode
import es.jjrh.bikeradar.data.Prefs

@Composable
fun SettingsRadarLight(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsRadarLightBody(navController, prefs)
    }
}

@Composable
private fun SettingsRadarLightBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    var autoEnabled by rememberSaveable { mutableStateOf(prefs.radarLightAutoModeEnabled) }
    var dayMode by rememberSaveable { mutableStateOf(prefs.radarLightDayMode) }
    var nightMode by rememberSaveable { mutableStateOf(prefs.radarLightNightMode) }
    var dayPickerOpen by rememberSaveable { mutableStateOf(false) }
    var nightPickerOpen by rememberSaveable { mutableStateOf(false) }

    // Same location dependency as the dashcam light: sunrise/sunset needs
    // ACCESS_COARSE_LOCATION, else it silently falls back to London. Surface the
    // re-grant card while it's actionable. See SettingsCameraLight for the
    // body-owns-the-launcher / stateless-leaf-takes-a-slot rationale.
    // The shared spec's rationale names both lights (it backs the global
    // Permissions screen); on this screen, scope the re-grant card to the radar
    // light so the copy is specific to where the rider is.
    val locSpec = remember {
        PERMISSIONS.first { Manifest.permission.ACCESS_COARSE_LOCATION in it.permissions }
            .copy(
                rationale = "Used once per ride to compute accurate sunrise/sunset for the " +
                    "radar-light auto-mode. Skip it and sunset is estimated for London.",
            )
    }
    var locPermTick by rememberSaveable { mutableStateOf(0) }
    val locGranted = remember(locPermTick) { isSpecGranted(ctx, locSpec) }

    SettingsRadarLightContent(
        onBack = { navController.popBackStack() },
        autoEnabled = autoEnabled,
        dayMode = dayMode,
        nightMode = nightMode,
        locGranted = locGranted,
        onAutoChanged = { v ->
            autoEnabled = v
            prefs.radarLightAutoModeEnabled = v
        },
        onDayClick = { dayPickerOpen = true },
        onNightClick = { nightPickerOpen = true },
        locationCard = {
            PermissionCard(locSpec, locGranted, onChanged = { locPermTick++ })
        },
    )

    if (dayPickerOpen) {
        RadarModePickerDialog(
            title = "Daytime mode",
            current = dayMode,
            onSelect = {
                dayMode = it
                prefs.radarLightDayMode = it
                dayPickerOpen = false
            },
            onDismiss = { dayPickerOpen = false },
        )
    }
    if (nightPickerOpen) {
        RadarModePickerDialog(
            title = "Night mode",
            current = nightMode,
            onSelect = {
                nightMode = it
                prefs.radarLightNightMode = it
                nightPickerOpen = false
            },
            onDismiss = { nightPickerOpen = false },
        )
    }
}

/**
 * Stateless leaf (mirrors [SettingsCameraLightContent]) so snapshot tests can
 * render without a `LocalContext`, a permission launcher, or an Activity. The
 * `autoEnabled && !locGranted` location-card gate lives here.
 */
@Composable
internal fun SettingsRadarLightContent(
    onBack: () -> Unit,
    autoEnabled: Boolean,
    dayMode: RadarLightMode,
    nightMode: RadarLightMode,
    locGranted: Boolean,
    onAutoChanged: (Boolean) -> Unit = {},
    onDayClick: () -> Unit = {},
    onNightClick: () -> Unit = {},
    locationCard: @Composable () -> Unit = {},
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader("Radar light auto-mode", onBack = onBack)

            SettingsRowGroup {
                SettingsToggleRow(
                    title = "Auto radar light mode",
                    subtitle = "Set the radar tail light at connect and at sunset",
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
                    subtitle = "${nightMode.displayName()} - switched at local sunset",
                    onClick = onNightClick,
                    chevron = false,
                    clickable = autoEnabled,
                    enabled = autoEnabled,
                    isLast = true,
                )
            }

            // Honest limitation: the radar reports its selected cycle slot, not
            // our mode-override, so the app can't confirm the switch landed.
            // Calm one-liner, not a warning. The built-in rear light is primary,
            // so this is a supplementary light - no safety alarm needed.
            if (autoEnabled) {
                Text(
                    text = "The radar can't confirm the switch - if in doubt, glance at the tail light.",
                    color = br.fgMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
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
private fun RadarModePickerDialog(
    title: String,
    current: RadarLightMode,
    onSelect: (RadarLightMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                RadarLightMode.entries.forEach { mode ->
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
