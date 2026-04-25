// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.Permissions
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Redesigned home screen.
 *
 * Differences vs V1:
 *  - Status card holds a full-width CTA button below the headline/subtitle
 *    when the current state is actionable (Set up, Start, Resume, Pair / Fix).
 *  - A new full-width Settings card-button sits below the status card,
 *    mirroring the mockup. The top-bar overflow menu (Settings / Debug /
 *    About) is preserved per the BOTH decision.
 *  - The "Service stopped" status case is rendered when the user has
 *    toggled the foreground service off; the CTA flips it back on and
 *    starts the service if permissions allow.
 *
 * State flow, polling, and battery rendering match V1 exactly. The deriver
 * is the single source of truth — render-layer code only consumes its
 * outputs and resolves them to actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreenNext(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val devUnlocked by DevModeState.unlocked.collectAsState()

    val radarState by RadarStateBus.state.collectAsState()
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val haHealth by HaHealthBus.state.collectAsState()
    val batteryEntries by BatteryStateBus.entries.collectAsState()

    var hasBond by remember { mutableStateOf(hasRearBondNext(ctx)) }
    var btEnabled by remember { mutableStateOf(isBluetoothEnabledNext(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            hasBond = hasRearBondNext(ctx)
            btEnabled = isBluetoothEnabledNext(ctx)
        }
    }

    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tickNowMs = System.currentTimeMillis()
        }
    }

    var devTapCount by remember { mutableIntStateOf(0) }
    var lastDevTapMs by remember { mutableLongStateOf(0L) }
    var overflowExpanded by remember { mutableStateOf(false) }

    val now = tickNowMs.coerceAtLeast(radarState.timestamp)
    val radarFresh = radarState.source == DataSource.V2 &&
        now - radarState.timestamp < 10_000L
    val haErrorRecent = (haHealth as? HaHealth.Error)?.let {
        now - it.atMs < 5 * 60_000L
    } ?: false
    val dashcamSlug = prefsSnap.dashcamMac?.let { mac ->
        BikeRadarService.macToSlug[mac]
            ?: BikeRadarService.macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefsSnap.dashcamDisplayName?.let { BikeRadarService.slug(it) }
    }
    val dashcamFresh = dashcamSlug?.let { slug ->
        batteryEntries[slug]?.let { now - it.readAtMs < 30_000L } == true
    } ?: false
    val dashcamOwned = prefsSnap.dashcamOwnership == DashcamOwnership.YES &&
        prefsSnap.dashcamMac != null

    val inputs = MainStatusInputs(
        firstRunComplete = prefsSnap.firstRunComplete,
        pausedUntilEpochMs = prefsSnap.pausedUntilEpochMs,
        hasBond = hasBond,
        radarFresh = radarFresh,
        haErrorRecent = haErrorRecent,
        dashcamOwned = dashcamOwned,
        dashcamWarnWhenOff = prefsSnap.dashcamWarnWhenOff,
        dashcamFresh = dashcamFresh,
        dashcamDisplayName = prefsSnap.dashcamDisplayName,
        serviceEnabled = prefsSnap.serviceEnabled,
    )
    val status = MainStatusDeriver.derive(
        inputs,
        nowMs = now,
        formatTime = {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        },
    )
    val cta = ctaFor(inputs, now, btEnabled, navController, ctx, prefs)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Bike Radar",
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastDevTapMs > 2_000L) devTapCount = 0
                                devTapCount++
                                lastDevTapMs = nowMs
                                if (devTapCount >= 3 && !devUnlocked) {
                                    DevModeState.unlock(prefs)
                                    devTapCount = 0
                                    Toast.makeText(ctx, "Developer options enabled", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ),
                    )
                },
                actions = {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { overflowExpanded = false; navController.navigate("settings") },
                        )
                        if (devUnlocked) {
                            DropdownMenuItem(
                                text = { Text("Debug") },
                                onClick = { overflowExpanded = false; navController.navigate("debug") },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = { overflowExpanded = false; navController.navigate("about") },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCardNext(status, cta)

            // Full-width Settings card-button. Redundant with the overflow
            // menu (kept per the BOTH decision) but more discoverable on
            // a screen the rider sees every ride.
            SettingsCardButton(onClick = { navController.navigate("settings") })

            if (prefsSnap.dashcamOwnership == DashcamOwnership.UNANSWERED) {
                DashcamPromptNext(
                    onYes = {
                        prefs.dashcamOwnership = DashcamOwnership.YES
                        navController.navigate("settings")
                    },
                    onNo = { prefs.dashcamOwnership = DashcamOwnership.NO },
                )
            }

            val sortedBattery = batteryEntries.values.sortedWith(
                compareBy({ it.slug != dashcamSlug }, { it.name.lowercase() })
            )
            val isLandscape = LocalConfiguration.current.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape && sortedBattery.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (entry in sortedBattery) {
                        Row(modifier = Modifier.weight(1f)) { BatteryCardNext(entry) }
                    }
                }
            } else {
                for (entry in sortedBattery) {
                    BatteryCardNext(entry)
                }
            }
        }
    }
}

private data class StatusCta(val label: String, val onClick: () -> Unit)

@Composable
private fun ctaFor(
    inputs: MainStatusInputs,
    nowMs: Long,
    btEnabled: Boolean,
    navController: NavController,
    ctx: Context,
    prefs: Prefs,
): StatusCta? = when {
    !inputs.firstRunComplete ->
        StatusCta("Set up") { navController.navigate("onboarding") }

    !inputs.serviceEnabled -> StatusCta("Start") {
        prefs.serviceEnabled = true
        if (Permissions.hasRequiredForService(ctx)) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, BikeRadarService::class.java),
            )
        } else {
            // Without the bond/scan permissions the service can't run.
            // Toast and route the user to Settings → Permissions so the
            // flag flip leads to actual scanning rather than silently
            // pretending the radar is back on.
            Toast.makeText(
                ctx,
                "Grant Bluetooth permissions in Settings to start scanning",
                Toast.LENGTH_LONG,
            ).show()
            navController.navigate("settings")
        }
    }

    nowMs < inputs.pausedUntilEpochMs ->
        StatusCta("Resume") { prefs.pausedUntilEpochMs = 0L }

    !inputs.hasBond -> StatusCta(
        label = if (btEnabled) "Pair" else "Fix",
        onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) },
    )

    // Dashcam-off Warn: no CTA. The actual fix is physical (turn the
    // dashcam on); the body copy "Turn on your <dashcam>" already says
    // it. This is a documented divergence from `10_main_screen.md` §3
    // table cell 4 which lists "Fix" — the spec calls a Settings deep-
    // link "optional" and we skip it.
    // Live + all good, Live + HA down, Waiting: no CTA.
    else -> null
}

@Composable
private fun StatusCardNext(status: MainStatus, cta: StatusCta?) {
    val container = toneContainerNext(status.tone)
    val content = toneContentNext(status.tone)
    val a11y = buildString {
        append(status.headline)
        status.subtitle?.let { append(". "); append(it) }
        cta?.let { append(". "); append(it.label) }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11y },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = iconForNext(status.icon),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = content,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.headline, style = MaterialTheme.typography.titleLarge, color = content)
                    status.subtitle?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = content)
                    }
                }
            }
            if (cta != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = cta.onClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(cta.label)
                }
            }
        }
    }
}

@Composable
private fun SettingsCardButton(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Open settings" },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Settings", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun BatteryCardNext(entry: BatteryEntry) {
    val ageMs = System.currentTimeMillis() - entry.readAtMs
    val ageLabel = when {
        ageMs < 60_000L -> "just now"
        ageMs < 3_600_000L -> "${ageMs / 60_000}m ago"
        else -> "${ageMs / 3_600_000}h ago"
    }
    val pctColor = when {
        entry.pct >= 50 -> MaterialTheme.colorScheme.primary
        entry.pct >= 20 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${entry.name} battery ${entry.pct} percent, read $ageLabel" },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    ageLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${entry.pct}%", style = MaterialTheme.typography.titleLarge, color = pctColor)
        }
    }
}

@Composable
private fun DashcamPromptNext(onYes: () -> Unit, onNo: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Do you run a dashcam?", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "We can warn you on the overlay if you forget to switch it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onYes, modifier = Modifier.weight(1f)) { Text("Yes, set it up") }
                TextButton(onClick = onNo, modifier = Modifier.weight(1f)) { Text("No") }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun hasRearBondNext(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.any { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    } == true
} catch (_: Throwable) { false }

private fun isBluetoothEnabledNext(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.isEnabled == true
} catch (_: Throwable) { false }

@Composable
private fun toneContainerNext(tone: MainStatusTone) = when (tone) {
    MainStatusTone.Good    -> MaterialTheme.colorScheme.primaryContainer
    MainStatusTone.Warn    -> MaterialTheme.colorScheme.tertiaryContainer
    MainStatusTone.Error   -> MaterialTheme.colorScheme.errorContainer
    MainStatusTone.Info    -> MaterialTheme.colorScheme.secondaryContainer
    MainStatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun toneContentNext(tone: MainStatusTone) = when (tone) {
    MainStatusTone.Good    -> MaterialTheme.colorScheme.onPrimaryContainer
    MainStatusTone.Warn    -> MaterialTheme.colorScheme.onTertiaryContainer
    MainStatusTone.Error   -> MaterialTheme.colorScheme.onErrorContainer
    MainStatusTone.Info    -> MaterialTheme.colorScheme.onSecondaryContainer
    MainStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun iconForNext(icon: MainStatusIcon): ImageVector = when (icon) {
    MainStatusIcon.PlayCircle        -> Icons.Default.PlayCircle
    MainStatusIcon.PauseCircle       -> Icons.Default.PauseCircle
    MainStatusIcon.BluetoothDisabled -> Icons.Default.BluetoothDisabled
    MainStatusIcon.CheckCircle       -> Icons.Default.CheckCircle
    MainStatusIcon.Warning           -> Icons.Default.Warning
    MainStatusIcon.Sensors           -> Icons.Default.Sensors
}
