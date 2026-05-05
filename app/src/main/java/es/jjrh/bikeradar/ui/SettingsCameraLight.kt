// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val br = LocalBrColors.current
    var autoEnabled by rememberSaveable { mutableStateOf(prefs.autoLightModeEnabled) }
    var dayMode by rememberSaveable { mutableStateOf(prefs.cameraLightDayMode) }
    var nightMode by rememberSaveable { mutableStateOf(prefs.cameraLightNightMode) }
    var dayPickerOpen by rememberSaveable { mutableStateOf(false) }
    var nightPickerOpen by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader("Front light auto-mode", onBack = { navController.popBackStack() })

            SettingsRowGroup {
                SettingsToggleRow(
                    title = "Auto front light mode",
                    subtitle = "Set light mode at power-on and at sunset",
                    checked = autoEnabled,
                    onCheckedChange = { v -> autoEnabled = v; prefs.autoLightModeEnabled = v },
                    leadingIcon = Icons.Default.WbSunny,
                    leadingTint = br.brand,
                    isLast = false,
                )
                SettingsRow(
                    icon = Icons.Default.LightMode,
                    iconTint = if (autoEnabled) br.brand else br.fgMuted,
                    title = "Daytime mode",
                    subtitle = dayMode.displayName(),
                    onClick = { dayPickerOpen = true },
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
                    onClick = { nightPickerOpen = true },
                    chevron = false,
                    clickable = autoEnabled,
                    enabled = autoEnabled,
                    isLast = true,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    if (dayPickerOpen) {
        ModePickerDialog(
            title = "Daytime mode",
            current = dayMode,
            onSelect = { dayMode = it; prefs.cameraLightDayMode = it; dayPickerOpen = false },
            onDismiss = { dayPickerOpen = false },
        )
    }
    if (nightPickerOpen) {
        ModePickerDialog(
            title = "Night mode",
            current = nightMode,
            onSelect = { nightMode = it; prefs.cameraLightNightMode = it; nightPickerOpen = false },
            onDismiss = { nightPickerOpen = false },
        )
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

