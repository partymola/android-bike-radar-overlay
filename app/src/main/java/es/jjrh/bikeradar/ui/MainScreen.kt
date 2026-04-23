// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val devUnlocked by DevModeState.unlocked.collectAsState()

    val radarState by RadarStateBus.state.collectAsState()
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val haHealth by HaHealthBus.state.collectAsState()
    val batteryEntries by BatteryStateBus.entries.collectAsState()

    var hasBond by remember { mutableStateOf(hasRearBond(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            hasBond = hasRearBond(ctx)
        }
    }

    // Tick to re-derive status every 2 s. Radar-fresh and dashcam-fresh are
    // both time-based, and some idle states (paused, waiting) don't receive
    // any other state-flow emissions that would otherwise trigger recompose.
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
    val status = MainStatusDeriver.derive(
        MainStatusInputs(
            firstRunComplete = prefsSnap.firstRunComplete,
            pausedUntilEpochMs = prefsSnap.pausedUntilEpochMs,
            hasBond = hasBond,
            radarFresh = radarFresh,
            haErrorRecent = haErrorRecent,
            dashcamOwned = dashcamOwned,
            dashcamWarnWhenOff = prefsSnap.dashcamWarnWhenOff,
            dashcamFresh = dashcamFresh,
            dashcamDisplayName = prefsSnap.dashcamDisplayName,
        ),
        nowMs = now,
        formatTime = {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Bike Radar",
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val now = System.currentTimeMillis()
                                if (now - lastDevTapMs > 2_000L) devTapCount = 0
                                devTapCount++
                                lastDevTapMs = now
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
            StatusCard(status)

            if (prefsSnap.dashcamOwnership == DashcamOwnership.UNANSWERED) {
                DashcamPrompt(
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
                        Row(modifier = Modifier.weight(1f)) { BatteryCard(entry) }
                    }
                }
            } else {
                for (entry in sortedBattery) {
                    BatteryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(entry: BatteryEntry) {
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

@SuppressLint("MissingPermission")
private fun hasRearBond(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.any { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    } == true
} catch (_: Throwable) { false }

@Composable
private fun StatusCard(status: MainStatus) {
    val container = toneContainer(status.tone)
    val content = toneContent(status.tone)
    val a11y = buildString {
        append(status.headline)
        status.subtitle?.let { append(". "); append(it) }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11y },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconFor(status.icon),
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
    }
}

@Composable
private fun DashcamPrompt(onYes: () -> Unit, onNo: () -> Unit) {
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

@Composable
private fun toneContainer(tone: MainStatusTone) = when (tone) {
    MainStatusTone.Good    -> MaterialTheme.colorScheme.primaryContainer
    MainStatusTone.Warn    -> MaterialTheme.colorScheme.tertiaryContainer
    MainStatusTone.Error   -> MaterialTheme.colorScheme.errorContainer
    MainStatusTone.Info    -> MaterialTheme.colorScheme.secondaryContainer
    MainStatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun toneContent(tone: MainStatusTone) = when (tone) {
    MainStatusTone.Good    -> MaterialTheme.colorScheme.onPrimaryContainer
    MainStatusTone.Warn    -> MaterialTheme.colorScheme.onTertiaryContainer
    MainStatusTone.Error   -> MaterialTheme.colorScheme.onErrorContainer
    MainStatusTone.Info    -> MaterialTheme.colorScheme.onSecondaryContainer
    MainStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun iconFor(icon: MainStatusIcon): ImageVector = when (icon) {
    MainStatusIcon.PlayCircle        -> Icons.Default.PlayCircle
    MainStatusIcon.PauseCircle       -> Icons.Default.PauseCircle
    MainStatusIcon.BluetoothDisabled -> Icons.Default.BluetoothDisabled
    MainStatusIcon.CheckCircle       -> Icons.Default.CheckCircle
    MainStatusIcon.Warning           -> Icons.Default.Warning
    MainStatusIcon.Sensors           -> Icons.Default.Sensors
}
