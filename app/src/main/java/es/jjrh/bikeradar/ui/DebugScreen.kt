// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.DebugOverlayService
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.ReplayService
import es.jjrh.bikeradar.SyntheticScenarioService
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val stateLog = remember { mutableStateListOf<String>() }
    val logScroll = rememberLazyListState()
    val logFiles = remember {
        val active = BikeRadarService.activeCaptureLogName
        ctx.getExternalFilesDir(null)
            ?.listFiles { f ->
                f.name.startsWith("bike-radar-capture-") &&
                    f.name.endsWith(".log") &&
                    f.length() > 0L &&
                    f.name != active
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    var replayRunning by remember { mutableStateOf(ReplayService.isRunning) }
    var synthRunning by remember { mutableStateOf(SyntheticScenarioService.isRunning) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            replayRunning = ReplayService.isRunning
            synthRunning = SyntheticScenarioService.isRunning
        }
    }

    LaunchedEffect(Unit) {
        RadarStateBus.state.collect { s ->
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
            val ageMs = if (s.timestamp > 0L) System.currentTimeMillis() - s.timestamp else -1L
            val src = if (s.source == DataSource.V2) "v=2" else "v=?"
            val n = s.vehicles.size
            val entry = "$ts  $src  n=$n  age=${ageMs}ms"
            if (stateLog.size >= 200) stateLog.removeAt(0)
            stateLog.add(entry)
        }
    }

    LaunchedEffect(stateLog.size) {
        if (stateLog.isNotEmpty()) logScroll.animateScrollToItem(stateLog.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // ── Scenario controls ─────────────────────────────────────────────
            item { DebugSectionHeader("Scenarios") }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (replayRunning) {
                                ctx.stopService(Intent(ctx, ReplayService::class.java))
                                ctx.stopService(Intent(ctx, DebugOverlayService::class.java))
                            } else {
                                ctx.stopService(Intent(ctx, SyntheticScenarioService::class.java))
                                ctx.startForegroundServiceCompat(DebugOverlayService::class.java)
                                ctx.startForegroundServiceCompat(ReplayService::class.java)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (replayRunning) "Stop Replay" else "Replay") }
                    Button(
                        onClick = {
                            if (synthRunning) {
                                ctx.stopService(Intent(ctx, SyntheticScenarioService::class.java))
                                ctx.stopService(Intent(ctx, DebugOverlayService::class.java))
                            } else {
                                ctx.stopService(Intent(ctx, ReplayService::class.java))
                                ctx.startForegroundServiceCompat(DebugOverlayService::class.java)
                                ctx.startForegroundServiceCompat(SyntheticScenarioService::class.java)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (synthRunning) "Stop Synthetic" else "Synthetic") }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        ctx.startService(
                            Intent(ctx, BikeRadarService::class.java).apply {
                                action = BikeRadarService.ACTION_FORCE_RECONNECT
                            }
                        )
                        Toast.makeText(ctx, "Force reconnect sent", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Force radar reconnect") }
            }

            // ── Manual HA push ────────────────────────────────────────────────
            item { DebugSectionHeader("Manual HA push") }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val creds = HaCredentials(ctx)
                            scope.launch(Dispatchers.IO) {
                                HaClient(creds.baseUrl, creds.token)
                                    .publishBatteryDiscovery("debug_dummy", "Debug Dummy")
                            }
                            Toast.makeText(ctx, "Discovery sent", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Send discovery") }
                    OutlinedButton(
                        onClick = {
                            val creds = HaCredentials(ctx)
                            scope.launch(Dispatchers.IO) {
                                HaClient(creds.baseUrl, creds.token)
                                    .publishBatteryState("debug_dummy", 50)
                            }
                            Toast.makeText(ctx, "State 50% sent", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Send 50%") }
                }
            }

            // ── Capture log list ──────────────────────────────────────────────
            item { DebugSectionHeader("Capture logs") }
            items(logFiles) { f ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            Text(
                                "${f.length() / 1024} KB  •  ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(f.lastModified()))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { shareFile(ctx, f) }) { Text("Share") }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (logFiles.isEmpty()) {
                item { Text("No capture logs yet.", style = MaterialTheme.typography.bodySmall) }
            }

            // ── Diagnostic bundle ─────────────────────────────────────────────
            item { DebugSectionHeader("Diagnostics") }
            item {
                OutlinedButton(
                    onClick = { shareDiagnosticBundle(ctx, prefs) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copy diagnostic bundle") }
            }

            // ── Raw state viewer ──────────────────────────────────────────────
            item { DebugSectionHeader("Radar state log (last 200)") }
            items(stateLog) { entry ->
                Text(entry, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
            if (stateLog.isEmpty()) {
                item { Text("No states received yet.", style = MaterialTheme.typography.bodySmall) }
            }

            // ── Dev mode lock ─────────────────────────────────────────────────
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                OutlinedButton(
                    onClick = {
                        DevModeState.lock(prefs)
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Lock developer mode") }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DebugSectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))
    }
}

private fun shareFile(ctx: Context, f: File) {
    if (!f.exists() || f.length() == 0L) {
        Toast.makeText(ctx, "Log file missing or empty", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = try {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    } catch (t: IllegalArgumentException) {
        Toast.makeText(ctx, "Cannot share this file: ${t.message}", Toast.LENGTH_LONG).show()
        return
    }
    val mime = ctx.contentResolver.getType(uri) ?: "text/plain"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_SUBJECT, f.name)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(ctx.contentResolver, f.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share ${f.name}"))
}

private fun shareDiagnosticBundle(ctx: Context, prefs: Prefs) {
    val sb = StringBuilder()
    sb.appendLine("=== Bike Radar Diagnostic Bundle ===")
    sb.appendLine("Generated: ${Date()}")
    sb.appendLine()
    sb.appendLine("--- Prefs ---")
    sb.appendLine(prefs.dumpAll())
    sb.appendLine("--- LESC bond verification (run on PC) ---")
    sb.appendLine("adb shell dumpsys bluetooth_manager | grep -E 'PairingAlgorithm|le_encrypted'")
    sb.appendLine()
    val logFiles = ctx.getExternalFilesDir(null)
        ?.listFiles { f -> f.name.startsWith("bike-radar-capture-") && f.name.endsWith(".log") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
    sb.appendLine("--- Capture logs (${logFiles.size} on disk) ---")
    logFiles.take(3).forEach { sb.appendLine("${it.name}  ${it.length() / 1024}KB") }

    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("diagnostic", sb.toString()))
    Toast.makeText(ctx, "Diagnostic bundle copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun Context.startForegroundServiceCompat(cls: Class<*>) {
    androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, cls))
}
