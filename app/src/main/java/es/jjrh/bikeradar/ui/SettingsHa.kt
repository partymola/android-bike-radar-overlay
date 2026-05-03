// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.HaUrlPolicy
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsHa(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsHaBody(navController, prefs)
    }
}

@Composable
private fun SettingsHaBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val scope = rememberCoroutineScope()
    val creds = remember { HaCredentials(ctx) }
    val haHealth by HaHealthBus.state.collectAsState()

    // urlField + tokenField + tokenVisible survive Activity recreate
    // (rotation, system-killed-on-resume) so a half-typed token isn't
    // lost. pingResult/mqttResult/pinging are transient — if the
    // process is killed mid-ping the result was never persisted to
    // creds anyway, and forcing a re-test on resume is the right UX.
    var urlField by rememberSaveable { mutableStateOf(creds.baseUrl) }
    var tokenField by rememberSaveable { mutableStateOf(creds.token) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<Result<String>?>(null) }
    var mqttResult by remember { mutableStateOf<Result<String>?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var haConfigured by remember { mutableStateOf(creds.baseUrl.isNotBlank() && creds.token.isNotBlank()) }
    // The (url, token) the current chip is reporting on. Set whenever
    // pingResult is set, cleared when the user edits past it. Lets the
    // chip-clear logic be exact — independent of Compose's recomposition
    // timing or any in-flight ping coroutine.
    var resultForCreds by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Clear chip feedback once the user has typed past the URL/token
    // the chip was reporting on. Comparing pinned values rather than
    // gating on `!pinging` makes the behaviour independent of timing —
    // the chip survives any keystroke that didn't change the inputs the
    // result was for, and an in-flight ping completing later still
    // pins its own result snapshot.
    LaunchedEffect(urlField, tokenField) {
        val pinned = resultForCreds
        if (pinned != null && pinned != (urlField.trim() to tokenField.trim())) {
            pingResult = null
            mqttResult = null
            resultForCreds = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader("Home Assistant", onBack = { navController.popBackStack() })

            // Connection state pill
            val connected = haConfigured && haHealth !is HaHealth.Error
            ConnectionStateCard(connected = connected, health = haHealth)

            Spacer(modifier = Modifier.height(14.dp))
            HaField(
                label = "Base URL",
                value = urlField,
                onChange = { urlField = it },
                placeholder = "https://homeassistant.local:8123",
                mono = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
            )
            HaField(
                label = "Long-lived token",
                value = tokenField,
                onChange = { tokenField = it },
                placeholder = "eyJ0eXAiOiJKV1QiLCJh…",
                mono = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                            tint = br.fgMuted,
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrandButton(
                    text = if (pinging) "Testing…" else "Test and save",
                    enabled = !pinging && urlField.isNotBlank() && tokenField.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val url = urlField.trim()
                        val tok = tokenField.trim()
                        val refusal = urlRefusalMessage(url)
                        if (refusal != null) {
                            // Preserve any previously saved working creds.
                            // Pill keeps reflecting actual storage state.
                            pingResult = Result.failure(Exception(refusal))
                            mqttResult = null
                            resultForCreds = url to tok
                            return@BrandButton
                        }
                        pinging = true
                        scope.launch(Dispatchers.IO) {
                            val client = HaClient(url, tok)
                            val pr = client.ping()
                            pingResult = pr
                            if (pr.isSuccess) {
                                creds.save(url, tok)
                                prefs.haLastValidatedEpochMs = System.currentTimeMillis()
                                prefs.haIntent = es.jjrh.bikeradar.data.HaIntent.YES
                                haConfigured = true
                                mqttResult = client.probeMqttService()
                            } else {
                                mqttResult = null
                            }
                            resultForCreds = url to tok
                            pinging = false
                        }
                    },
                )
                GhostButton(
                    text = "Save without testing",
                    enabled = urlField.isNotBlank() && tokenField.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val url = urlField.trim()
                        val tok = tokenField.trim()
                        val refusal = urlRefusalMessage(url)
                        if (refusal != null) {
                            pingResult = Result.failure(Exception(refusal))
                            mqttResult = null
                            resultForCreds = url to tok
                            return@GhostButton
                        }
                        creds.save(url, tok)
                        // The "validated" timestamp keys off ping(); a save
                        // bypassing test must invalidate it, otherwise any
                        // "creds last verified N days ago" cue misleads.
                        prefs.haLastValidatedEpochMs = 0L
                        prefs.haIntent = es.jjrh.bikeradar.data.HaIntent.YES
                        haConfigured = url.isNotBlank() && tok.isNotBlank()
                        android.widget.Toast.makeText(ctx, "Saved without testing", android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // Clear button: removes encrypted creds + resets the connected
            // pill. The privacy screen documents this as the off-switch
            // for HA publishing; without it, the only way to disconnect
            // would be uninstalling.
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                GhostButton(
                    text = "Clear HA configuration",
                    enabled = haConfigured,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        creds.clear()
                        prefs.haLastValidatedEpochMs = 0L
                        // Reset the onboarding intent flag so a fresh
                        // re-trigger (or onbtest variant) shows the chooser
                        // again rather than landing on an empty fields form.
                        prefs.haIntent = es.jjrh.bikeradar.data.HaIntent.UNSET
                        urlField = ""
                        tokenField = ""
                        haConfigured = false
                        pingResult = null
                        mqttResult = null
                        resultForCreds = null
                        android.widget.Toast.makeText(
                            ctx,
                            "HA configuration cleared",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }

            // Result chips, side-by-side, when present
            if (pingResult != null || mqttResult != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pingResult?.let { r ->
                        BrChip(
                            text = if (r.isSuccess) "HA: saved" else "HA: ${r.exceptionOrNull()?.message ?: "error"}",
                            color = if (r.isSuccess) br.safe else br.danger,
                        )
                    }
                    mqttResult?.let { r ->
                        BrChip(
                            text = if (r.isSuccess) "MQTT: ready" else "MQTT: ${r.exceptionOrNull()?.message ?: "error"}",
                            color = if (r.isSuccess) br.safe else br.caution,
                        )
                    }
                }
            }

            // Published entities (synthetic — these are HA discovery
            // entries the app is known to publish; live count would
            // require a query we don't run yet). The status dot reflects
            // whether HA is currently reachable, not the entity value.
            SettingsSectionLabel("Published entities")
            SettingsRowGroup {
                EntityRow(name = "sensor.bike_radar_battery", value = "—", connected = connected, isLast = false)
                EntityRow(name = "sensor.bike_dashcam_battery", value = "—", connected = connected, isLast = false)
                EntityRow(name = "event.bike_close_pass", value = "events", connected = connected, isLast = false)
                EntityRow(name = "binary_sensor.bike_radar_online", value = if (connected) "on" else "off", connected = connected, isLast = true)
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/**
 * Pre-network gate. Returns the user-facing refusal message when the URL is
 * malformed or sends a bearer token in cleartext to a non-LAN host, else null.
 */
private fun urlRefusalMessage(url: String): String? = when (val r = HaUrlPolicy.validate(url)) {
    is HaUrlPolicy.Result.CleartextWanRefused -> HaUrlPolicy.refusalMessage(r.host)
    HaUrlPolicy.Result.Malformed -> HaUrlPolicy.malformedMessage()
    HaUrlPolicy.Result.Empty, HaUrlPolicy.Result.Ok -> null
}

@Composable
private fun ConnectionStateCard(connected: Boolean, health: HaHealth) {
    val br = LocalBrColors.current
    val accent = if (connected) br.safe else br.fgDim
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(color = accent, size = 8.dp)
        Column {
            Text(
                text = if (connected) "Connected" else "Not configured",
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = when {
                !connected -> "Add your URL + token below."
                health is HaHealth.Error -> "Last error: ${health.message}"
                else -> "MQTT discovery active"
            }
            Text(
                text = subtitle,
                color = br.fgMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun HaField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = label.uppercase(),
            color = br.fgDim,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 1.0.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = br.fgDim) },
            singleLine = true,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            trailingIcon = trailingIcon,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = br.fg,
                unfocusedTextColor = br.fg,
                focusedBorderColor = br.brand,
                unfocusedBorderColor = br.hairline2,
                cursorColor = br.brand,
                focusedContainerColor = br.bgElev1,
                unfocusedContainerColor = br.bgElev1,
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 13.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BrandButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) br.brand else br.bgElev2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) br.bg else br.fgDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GhostButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) br.fg else br.fgDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EntityRow(name: String, value: String, connected: Boolean, isLast: Boolean) {
    val br = LocalBrColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(color = if (connected) br.safe else br.fgDim, size = 5.dp)
            Text(
                text = name,
                color = br.fg,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                color = br.fgDim,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            )
        }
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 20.dp, end = 20.dp)
                    .background(br.hairline),
            )
        }
    }
}
