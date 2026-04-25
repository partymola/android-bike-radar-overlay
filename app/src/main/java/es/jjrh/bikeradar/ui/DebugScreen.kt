// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.DebugOverlayService
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.ReplayService
import es.jjrh.bikeradar.ScreenshotCaptureService
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
    var screenshotRunning by remember { mutableStateOf(ScreenshotCaptureService.isRunning) }
    var stateLogExpanded by remember { mutableStateOf(false) }
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(ctx, ScreenshotCaptureService::class.java).apply {
                action = ScreenshotCaptureService.ACTION_START
                putExtra(ScreenshotCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenshotCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ctx.startForegroundServiceCompat(intent)
            screenshotRunning = true
            Toast.makeText(ctx, "Screenshot capture armed", Toast.LENGTH_SHORT).show()
        } else {
            screenshotRunning = false
            Toast.makeText(ctx, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            replayRunning = ReplayService.isRunning
            synthRunning = SyntheticScenarioService.isRunning
            screenshotRunning = ScreenshotCaptureService.isRunning
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
            item { DebugSectionHeader("Service control") }
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

            // ── Screenshot capture ────────────────────────────────────────────
            item { DebugSectionHeader("Screenshot capture") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Periodic screenshots",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Captures the screen once a minute while the radar overlay is active. " +
                                "Saved to the app's files dir under screenshots/.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = screenshotRunning,
                        onCheckedChange = { wantOn ->
                            if (wantOn) {
                                val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                    as MediaProjectionManager
                                projectionLauncher.launch(mpm.createScreenCaptureIntent())
                            } else {
                                ctx.startService(
                                    Intent(ctx, ScreenshotCaptureService::class.java).apply {
                                        action = ScreenshotCaptureService.ACTION_STOP
                                    }
                                )
                                screenshotRunning = false
                            }
                        },
                    )
                }
            }

            // ── Experimental UX ───────────────────────────────────────────────
            item { DebugSectionHeader("Experimental UX") }
            item {
                Text(
                    "Per-screen toggles for the in-progress UX redesign. Flip on " +
                        "to render the redesigned screen, off to return to the " +
                        "current one. Both versions read the same persisted state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item {
                NextUxRow(
                    label = "Onboarding",
                    sublabel = if (prefsSnap.nextUxOnboarding) "redesigned" else "current",
                    checked = prefsSnap.nextUxOnboarding,
                    onChange = { prefs.nextUxOnboarding = it },
                )
            }
            item {
                NextUxRow(
                    label = "Main",
                    sublabel = if (prefsSnap.nextUxMain) "redesigned" else "current",
                    checked = prefsSnap.nextUxMain,
                    onChange = { prefs.nextUxMain = it },
                )
            }
            item {
                NextUxRow(
                    label = "Settings",
                    sublabel = if (prefsSnap.nextUxSettings) "redesigned" else "current",
                    checked = prefsSnap.nextUxSettings,
                    onChange = { prefs.nextUxSettings = it },
                )
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
                    ) { Text("Send battery 50%") }
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
                Spacer(modifier = Modifier.height(8.dp))
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
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { stateLogExpanded = !stateLogExpanded }
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Radar state log (${stateLog.size})",
                        style = MaterialTheme.typography.titleMedium,
                        letterSpacing = 0.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        if (stateLogExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (stateLogExpanded) {
                items(stateLog) { entry ->
                    Text(entry, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                if (stateLog.isEmpty()) {
                    item { Text("No states received yet.", style = MaterialTheme.typography.bodySmall) }
                }
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
private fun NextUxRow(
    label: String,
    sublabel: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                sublabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DebugSectionHeader(title: String) {
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

private fun Context.startForegroundServiceCompat(intent: Intent) {
    androidx.core.content.ContextCompat.startForegroundService(this, intent)
}
