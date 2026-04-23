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
    var pinging by remember { mutableStateOf(false) }

    var alertVol by remember { mutableIntStateOf(prefs.alertVolume) }
    var batteryThreshold by remember { mutableIntStateOf(prefs.batteryLowThresholdPct) }
    var batteryShowLabels by remember { mutableStateOf(prefs.batteryShowLabels) }
    var dashcamOwnership by remember { mutableStateOf(prefs.dashcamOwnership) }
    var dashcamMac by remember { mutableStateOf(prefs.dashcamMac) }
    var dashcamDisplayName by remember { mutableStateOf(prefs.dashcamDisplayName) }
    var dashcamWarn by remember { mutableStateOf(prefs.dashcamWarnWhenOff) }
    var walkAwayEnabled by remember { mutableStateOf(prefs.walkAwayAlarmEnabled) }
    var walkAwayThreshold by remember { mutableIntStateOf(prefs.walkAwayAlarmThresholdSec) }
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
                display = "$alertVol",
                onValueChange = { alertVol = it.toInt() },
                onValueChangeFinished = { prefs.alertVolume = alertVol },
            )
            SettingsSliderRow(
                label = "Alert distance",
                value = alertDist.toFloat(),
                range = 10f..40f,
                display = "${alertDist}m",
                onValueChange = { alertDist = it.toInt() },
                onValueChangeFinished = { prefs.alertMaxDistanceM = alertDist },
            )

            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader("Overlay")
            SettingsSliderRow(
                label = "Visual distance",
                value = visualDist.toFloat(),
                range = 10f..80f,
                display = "${visualDist}m",
                onValueChange = { visualDist = it.toInt() },
                onValueChangeFinished = { prefs.visualMaxDistanceM = visualDist },
            )
            SettingsSliderRow(
                label = "Battery low threshold",
                value = batteryThreshold.toFloat(),
                range = 10f..50f,
                display = "${batteryThreshold}%",
                onValueChange = { batteryThreshold = it.toInt() },
                onValueChangeFinished = { prefs.batteryLowThresholdPct = batteryThreshold },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Label low-battery device on overlay", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
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
                            dashcamDisplayName?.let {
                                "$it  ${dashcamMac ?: ""}".trim()
                            } ?: "Not selected — tap to pick",
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Warn if dashcam is left running",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (dashcamMac == null || !dashcamWarn)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Notify when the radar turns off but the dashcam is still on the bike.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = walkAwayEnabled,
                        enabled = dashcamMac != null && dashcamWarn,
                        onCheckedChange = {
                            walkAwayEnabled = it
                            prefs.walkAwayAlarmEnabled = it
                        },
                    )
                }
                if (walkAwayEnabled && dashcamMac != null && dashcamWarn) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "Wait ${walkAwayThreshold}s before alerting",
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
                AssistChip(onClick = {}, label = { Text(bondStatus) })
            }
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Bluetooth settings") }
            Column(modifier = Modifier.fillMaxWidth()) {
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
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val cmd = "adb shell dumpsys bluetooth_manager | grep -E 'PairingAlgorithm|le_encrypted'"
                        Text(
                            "Verify LESC bond (run on PC):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
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
                        scope.launch(Dispatchers.IO) {
                            val r = HaClient(urlField.trim(), tokenField.trim()).ping()
                            if (r.isSuccess) prefs.haLastValidatedEpochMs = System.currentTimeMillis()
                            pingResult = r
                            pinging = false
                        }
                    },
                    enabled = !pinging && urlField.isNotBlank() && tokenField.isNotBlank(),
                ) { Text(if (pinging) "Testing..." else "Test connection") }

                pingResult?.let { r ->
                    AssistChip(
                        onClick = {},
                        label = { Text(if (r.isSuccess) "OK" else (r.exceptionOrNull()?.message ?: "Error")) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (r.isSuccess) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            else MaterialTheme.colorScheme.errorContainer,
                            labelColor = if (r.isSuccess) androidx.compose.ui.graphics.Color.White
                            else MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
            }
            Button(
                onClick = {
                    creds.save(urlField.trim(), tokenField.trim())
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader("Permissions")
            var permsRefresh by remember { mutableIntStateOf(0) }
            PERMISSIONS.forEach { spec ->
                PermissionCard(
                    spec = spec,
                    refresh = permsRefresh,
                    onRefresh = { permsRefresh++ },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))
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
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
