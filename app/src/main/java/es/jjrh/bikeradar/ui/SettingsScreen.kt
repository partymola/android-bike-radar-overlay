// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BuildConfig
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val creds = remember { HaCredentials(ctx) }

    var urlField by remember { mutableStateOf(creds.baseUrl) }
    var tokenField by remember { mutableStateOf(creds.token) }
    var tokenVisible by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<Result<String>?>(null) }
    var mqttResult by remember { mutableStateOf<Result<String>?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var haConfigured by remember {
        mutableStateOf(creds.baseUrl.isNotBlank() && creds.token.isNotBlank())
    }

    var alertVol by remember { mutableIntStateOf(prefs.alertVolume) }
    var batteryThreshold by remember { mutableIntStateOf(prefs.batteryLowThresholdPct) }
    var batteryShowLabels by remember { mutableStateOf(prefs.batteryShowLabels) }
    var dashcamOwnership by remember { mutableStateOf(prefs.dashcamOwnership) }
    var dashcamMac by remember { mutableStateOf(prefs.dashcamMac) }
    var dashcamDisplayName by remember { mutableStateOf(prefs.dashcamDisplayName) }
    var dashcamWarn by remember { mutableStateOf(prefs.dashcamWarnWhenOff) }
    var walkAwayEnabled by remember { mutableStateOf(prefs.walkAwayAlarmEnabled) }
    var walkAwayThreshold by remember { mutableIntStateOf(prefs.walkAwayAlarmThresholdSec) }
    var adaptiveAlerts by remember { mutableStateOf(prefs.adaptiveAlertsEnabled) }
    var precog by remember { mutableStateOf(prefs.precogEnabled) }
    var closePassLogging by remember { mutableStateOf(prefs.closePassLoggingEnabled) }
    var closePassEmitMinX by remember { mutableStateOf(prefs.closePassEmitMinRangeXM) }
    var closePassRiderFloor by remember { mutableIntStateOf(prefs.closePassRiderSpeedFloorKmh) }
    var closePassClosingFloor by remember { mutableIntStateOf(prefs.closePassClosingSpeedFloorMs) }
    var showPicker by remember { mutableStateOf(false) }
    var alertDist by remember { mutableIntStateOf(prefs.alertMaxDistanceM) }
    var visualDist by remember { mutableIntStateOf(prefs.visualMaxDistanceM) }

    var bondStatus by remember { mutableStateOf(bondStatusText(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            bondStatus = bondStatusText(ctx)
        }
    }

    var troubleshootingExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Column + verticalScroll instead of LazyColumn: avoids gesture conflict where
        // LazyColumn intercepts horizontal drags before Slider can claim them.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Ordered most-tweaked-first: audio alerts, on-screen rendering,
            // dashcam, then one-time setup (pairing, HA, permissions, about).
            SettingsSectionHeader("Radar alerts")
            SettingsSliderRow(
                label = "Alert volume",
                value = alertVol.toFloat(),
                range = 0f..100f,
                display = "$alertVol%",
                helper = "System beep volume for approach alerts. 0 silences audio; the overlay still flashes.",
                onValueChange = { alertVol = it.toInt() },
                onValueChangeFinished = { prefs.alertVolume = alertVol },
            )
            SettingsSliderRow(
                label = "Alert distance",
                value = alertDist.toFloat(),
                range = 10f..40f,
                display = "$alertDist m",
                helper = "Start beeping when a vehicle is this close. Vehicles farther away appear on the overlay but stay silent. Scaled by bike speed when adaptive alerts are on.",
                onValueChange = { alertDist = it.toInt() },
                onValueChangeFinished = { prefs.alertMaxDistanceM = alertDist },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Adaptive alert colours",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Scale amber/red thresholds by your bike speed: more sensitive when stopped, less when cruising.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = adaptiveAlerts,
                    onCheckedChange = {
                        adaptiveAlerts = it
                        prefs.adaptiveAlertsEnabled = it
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Log close passes to Home Assistant",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (haConfigured) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (haConfigured)
                            "Publish an event to HA when a vehicle overtakes inside the lateral distance you set below. Strict gates — only genuine close passes count. Builds a route-safety dataset over time."
                        else
                            "Requires Home Assistant — set it up below.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = closePassLogging,
                    enabled = haConfigured,
                    onCheckedChange = {
                        closePassLogging = it
                        prefs.closePassLoggingEnabled = it
                    },
                )
            }
            if (closePassLogging) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                  Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Lateral clearance threshold", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Publish only when the minimum lateral clearance drops below ${String.format(java.util.Locale.US, "%.1f", closePassEmitMinX)} m.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = closePassEmitMinX,
                        onValueChange = { closePassEmitMinX = it },
                        valueRange = 0.5f..2.0f,
                        steps = 14,  // 0.1 m increments
                        onValueChangeFinished = {
                            prefs.closePassEmitMinRangeXM = closePassEmitMinX
                        },
                    )
                    Text("Minimum rider speed: $closePassRiderFloor km/h", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Detector ignores stationary-rider situations (red lights, pushing the bike).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = closePassRiderFloor.toFloat(),
                        onValueChange = { closePassRiderFloor = it.toInt() },
                        valueRange = 5f..30f,
                        steps = 4,
                        onValueChangeFinished = {
                            prefs.closePassRiderSpeedFloorKmh = closePassRiderFloor
                        },
                    )
                    Text("Minimum closing speed: $closePassClosingFloor m/s", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Roughly ${(closePassClosingFloor * 3.6).toInt()} km/h of relative approach speed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = closePassClosingFloor.toFloat(),
                        onValueChange = { closePassClosingFloor = it.toInt() },
                        valueRange = 3f..15f,
                        steps = 11,
                        onValueChangeFinished = {
                            prefs.closePassClosingSpeedFloorMs = closePassClosingFloor
                        },
                    )
                  }  // end inner Column in close-pass card
                }  // end close-pass Card
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader("Overlay")
            SettingsSliderRow(
                label = "Visual distance",
                value = visualDist.toFloat(),
                range = 10f..80f,
                display = "$visualDist m",
                helper = "Farthest vehicle drawn on the overlay. Beyond this, approaching traffic is ignored on screen.",
                onValueChange = { visualDist = it.toInt() },
                onValueChangeFinished = { prefs.visualMaxDistanceM = visualDist },
            )
            SettingsSliderRow(
                label = "Battery low threshold",
                value = batteryThreshold.toFloat(),
                range = 10f..50f,
                display = "$batteryThreshold%",
                helper = "Flag the radar or dashcam as low when its battery drops below this.",
                onValueChange = { batteryThreshold = it.toInt() },
                onValueChangeFinished = { prefs.batteryLowThresholdPct = batteryThreshold },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Label low-battery device on overlay", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Show 'RADAR 12%' or 'DASHCAM 8%' on screen instead of a silent warning tint.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = batteryShowLabels,
                    onCheckedChange = { batteryShowLabels = it; prefs.batteryShowLabels = it },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader("Dashcam")
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("I have a front dashcam", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = dashcamOwnership == es.jjrh.bikeradar.data.DashcamOwnership.YES,
                    onCheckedChange = { on ->
                        val next = if (on) es.jjrh.bikeradar.data.DashcamOwnership.YES
                                   else es.jjrh.bikeradar.data.DashcamOwnership.NO
                        dashcamOwnership = next
                        prefs.dashcamOwnership = next
                        if (!on) {
                            dashcamMac = null
                            dashcamDisplayName = null
                            dashcamWarn = false
                            prefs.dashcamMac = null
                            prefs.dashcamDisplayName = null
                            prefs.dashcamWarnWhenOff = false
                        }
                    },
                )
            }
            if (dashcamOwnership == es.jjrh.bikeradar.data.DashcamOwnership.YES) {
              Column(modifier = Modifier.padding(start = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPicker = true }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Device", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            dashcamDisplayName ?: "Not selected — tap to pick",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Change", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Warn on overlay when dashcam is off",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        color = if (dashcamMac == null)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(
                        checked = dashcamWarn,
                        enabled = dashcamMac != null,
                        onCheckedChange = { dashcamWarn = it; prefs.dashcamWarnWhenOff = it },
                    )
                }
                if (dashcamWarn && dashcamMac != null) {
                  Column(modifier = Modifier.padding(start = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Warn if dashcam is left running",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Notify when the radar turns off but the dashcam is still on the bike.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = walkAwayEnabled,
                            onCheckedChange = {
                                walkAwayEnabled = it
                                prefs.walkAwayAlarmEnabled = it
                            },
                        )
                    }
                    if (walkAwayEnabled) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(
                                    "Wait $walkAwayThreshold seconds before alerting",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Slider(
                                    value = walkAwayThreshold.toFloat(),
                                    onValueChange = { walkAwayThreshold = it.toInt() },
                                    valueRange = 15f..120f,
                                    steps = 6,  // 15 / 30 / 45 / 60 / 75 / 90 / 105 / 120
                                    onValueChangeFinished = {
                                        prefs.walkAwayAlarmThresholdSec = walkAwayThreshold
                                    },
                                )
                            }
                        }
                    }
                  }
                }
              }  // end dashcam-YES indent Column
            }
            if (showPicker) {
                DashcamPickerDialog(
                    currentMac = dashcamMac,
                    onDismiss = { showPicker = false },
                    onConfirm = { mac, name ->
                        dashcamMac = mac
                        dashcamDisplayName = name
                        prefs.dashcamMac = mac
                        prefs.dashcamDisplayName = name
                        if (mac == null) {
                            dashcamWarn = false
                            prefs.dashcamWarnWhenOff = false
                        }
                        showPicker = false
                    },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader("Pairing")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Status:", style = MaterialTheme.typography.bodyMedium)
                AssistChip(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    label = { Text(bondStatus) },
                )
            }
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Bluetooth settings") }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
              Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { troubleshootingExpanded = !troubleshootingExpanded }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Troubleshooting", style = MaterialTheme.typography.bodyMedium)
                    Icon(
                        if (troubleshootingExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
                AnimatedVisibility(visible = troubleshootingExpanded) {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        val cmd = "adb shell dumpsys bluetooth_manager | grep -E 'PairingAlgorithm|le_encrypted'"
                        Text(
                            "For developers: verify your pair used LE Secure Connections. Skip if the radar shows live data. Run this on a PC with ADB:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                cmd,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("cmd", cmd))
                                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("\u2398", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader("Home Assistant")
            OutlinedTextField(
                value = urlField,
                onValueChange = { urlField = it },
                label = { Text("Base URL") },
                placeholder = { Text("https://ha.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tokenField,
                onValueChange = { tokenField = it },
                label = { Text("Long-lived token") },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        pinging = true
                        val url = urlField.trim()
                        val tok = tokenField.trim()
                        scope.launch(Dispatchers.IO) {
                            val client = HaClient(url, tok)
                            val pr = client.ping()
                            pingResult = pr
                            if (pr.isSuccess) {
                                creds.save(url, tok)
                                prefs.haLastValidatedEpochMs = System.currentTimeMillis()
                                haConfigured = true
                                mqttResult = client.probeMqttService()
                            } else {
                                mqttResult = null
                            }
                            pinging = false
                        }
                    },
                    enabled = !pinging && urlField.isNotBlank() && tokenField.isNotBlank(),
                ) { Text(if (pinging) "Testing..." else "Test and save") }
            }
            pingResult?.let { r ->
                AssistChip(
                    onClick = {},
                    label = { Text(if (r.isSuccess) "HA: saved" else "HA: ${r.exceptionOrNull()?.message ?: "error"}") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (r.isSuccess) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.errorContainer,
                        labelColor = if (r.isSuccess) androidx.compose.ui.graphics.Color.White
                        else MaterialTheme.colorScheme.onErrorContainer,
                    ),
                )
            }
            mqttResult?.let { r ->
                AssistChip(
                    onClick = {},
                    label = { Text(if (r.isSuccess) "MQTT: ready" else "MQTT: ${r.exceptionOrNull()?.message ?: "error"}") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (r.isSuccess) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor = if (r.isSuccess) androidx.compose.ui.graphics.Color.White
                        else MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                )
            }
            Button(
                onClick = {
                    creds.save(urlField.trim(), tokenField.trim())
                    haConfigured = urlField.isNotBlank() && tokenField.isNotBlank()
                    Toast.makeText(ctx, "Saved without testing", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = urlField.isNotBlank() && tokenField.isNotBlank(),
            ) { Text("Save without testing") }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader("Experimental")
            Text(
                "Features still being tested. May be jittery or change without notice.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Predict overtake paths (1 s lookahead)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Render each vehicle 1 second into the future — see where overtakers are heading, not just where they are. Can look jittery in noisy traffic.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = precog,
                        onCheckedChange = {
                            precog = it
                            prefs.precogEnabled = it
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader("Permissions")
            var permsRefresh by remember { mutableIntStateOf(0) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PERMISSIONS.forEach { spec ->
                    PermissionCard(
                        spec = spec,
                        refresh = permsRefresh,
                        onRefresh = { permsRefresh++ },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    helper: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (helper != null) {
            Text(
                helper,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
        )
    }
}

@SuppressLint("MissingPermission")
private fun bondStatusText(ctx: Context): String {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        ?: return "Bluetooth unavailable"
    val adapter = mgr.adapter ?: return "Bluetooth unavailable"
    if (!adapter.isEnabled) return "Bluetooth off"
    return try {
        val bonded = adapter.bondedDevices?.firstOrNull { dev ->
            val n = dev.name?.lowercase() ?: ""
            n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
        }
        if (bonded != null) "Bonded with ${bonded.name}" else "Not bonded"
    } catch (_: Throwable) { "Not bonded" }
}
