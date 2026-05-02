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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Debug screen: scenario controls (Replay / Synthetic), service control,
 * screenshot capture, manual HA push, capture-log list with share,
 * diagnostic bundle, radar-state log, lock-developer-mode.
 */
@Composable
fun DebugScreen(navController: NavController, prefs: Prefs) {
    UiTheme {
        DebugScreenBody(navController, prefs)
    }
}

@Composable
private fun DebugScreenBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val scope = rememberCoroutineScope()
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())

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
    // Plain remember: rotation cancels the dialog. File doesn't auto-save
    // and the worst case is the user re-taps Share and re-sees the warning,
    // which is fine in a debug screen.
    var pendingShareFile by remember { mutableStateOf<File?>(null) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(ctx, ScreenshotCaptureService::class.java).apply {
                action = ScreenshotCaptureService.ACTION_START
                putExtra(ScreenshotCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenshotCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundServiceCompat(ctx, intent)
            screenshotRunning = true
            Toast.makeText(ctx, "Screenshot capture armed", Toast.LENGTH_SHORT).show()
        } else {
            screenshotRunning = false
            Toast.makeText(ctx, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Service-state poll pauses with the screen — no point waking
    // up the static fields every half-second from the background.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(500)
                replayRunning = ReplayService.isRunning
                synthRunning = SyntheticScenarioService.isRunning
                screenshotRunning = ScreenshotCaptureService.isRunning
            }
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

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader("Debug", onBack = { navController.popBackStack() })

            // Scenarios
            SettingsSectionLabel("Scenarios")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DbgPrimaryButton(
                    text = if (replayRunning) "Stop Replay" else "Replay",
                    active = replayRunning,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (replayRunning) {
                            ctx.stopService(Intent(ctx, ReplayService::class.java))
                            ctx.stopService(Intent(ctx, DebugOverlayService::class.java))
                        } else {
                            ctx.stopService(Intent(ctx, SyntheticScenarioService::class.java))
                            startForegroundServiceCompat(ctx, Intent(ctx, DebugOverlayService::class.java))
                            startForegroundServiceCompat(ctx, Intent(ctx, ReplayService::class.java))
                        }
                    },
                )
                DbgPrimaryButton(
                    text = if (synthRunning) "Stop Synthetic" else "Synthetic",
                    active = synthRunning,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (synthRunning) {
                            ctx.stopService(Intent(ctx, SyntheticScenarioService::class.java))
                            ctx.stopService(Intent(ctx, DebugOverlayService::class.java))
                        } else {
                            ctx.stopService(Intent(ctx, ReplayService::class.java))
                            startForegroundServiceCompat(ctx, Intent(ctx, DebugOverlayService::class.java))
                            startForegroundServiceCompat(ctx, Intent(ctx, SyntheticScenarioService::class.java))
                        }
                    },
                )
            }

            // Service control
            SettingsSectionLabel("Service control")
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                DbgGhostButton(
                    text = "Force radar reconnect",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        ctx.startService(
                            Intent(ctx, BikeRadarService::class.java).apply {
                                action = BikeRadarService.ACTION_FORCE_RECONNECT
                            }
                        )
                        Toast.makeText(ctx, "Force reconnect sent", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // Screenshot capture
            SettingsSectionLabel("Screenshot capture")
            SettingsRowGroup {
                SettingsToggleRow(
                    title = "Periodic screenshots",
                    subtitle = "Capture the screen every minute while the radar overlay is active. Saved to the app's files dir under screenshots/.",
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

            // Manual HA push
            SettingsSectionLabel("Manual HA push")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DbgGhostButton(
                    text = "Send discovery",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val creds = HaCredentials(ctx)
                        scope.launch(Dispatchers.IO) {
                            HaClient(creds.baseUrl, creds.token)
                                .publishBatteryDiscovery("debug_dummy", "Debug Dummy")
                        }
                        Toast.makeText(ctx, "Discovery sent", Toast.LENGTH_SHORT).show()
                    },
                )
                DbgGhostButton(
                    text = "Send battery 50%",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val creds = HaCredentials(ctx)
                        scope.launch(Dispatchers.IO) {
                            HaClient(creds.baseUrl, creds.token)
                                .publishBatteryState("debug_dummy", 50)
                        }
                        Toast.makeText(ctx, "State 50% sent", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // Capture logs
            SettingsSectionLabel("Capture logs")
            if (logFiles.isEmpty()) {
                Text(
                    text = "No capture logs yet.",
                    color = br.fgDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (f in logFiles) {
                        CaptureLogCard(file = f, onShare = {
                            if (prefs.captureLogShareWarningSeen) {
                                shareFile(ctx, f)
                            } else {
                                pendingShareFile = f
                            }
                        })
                    }
                }
            }

            // Diagnostics
            SettingsSectionLabel("Diagnostics")
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                DbgGhostButton(
                    text = "Copy diagnostic bundle",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { shareDiagnosticBundle(ctx, prefs) },
                )
            }

            // Radar state log
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clickable { stateLogExpanded = !stateLogExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RADAR STATE LOG (${stateLog.size})",
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                )
                Icon(
                    imageVector = if (stateLogExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = br.fgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (stateLogExpanded) {
                if (stateLog.isEmpty()) {
                    Text(
                        text = "No states received yet.",
                        color = br.fgDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(br.bgElev1)
                            .border(1.dp, br.hairline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        for (entry in stateLog.takeLast(50)) {
                            Text(
                                text = entry,
                                color = br.fgMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            // Lock developer mode (last)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                DbgGhostButton(
                    text = "Lock developer mode",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        DevModeState.lock(prefs)
                        navController.popBackStack()
                    },
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    pendingShareFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingShareFile = null },
            title = { Text("Share this capture log?") },
            text = {
                Text(
                    "This log records the exact time of every radar packet. " +
                        "Anyone you share it with can work out when and where " +
                        "you rode, and how often vehicles passed close. Only " +
                        "share with people you trust.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.captureLogShareWarningSeen = true
                    pendingShareFile = null
                    shareFile(ctx, file)
                }) { Text("Share anyway") }
            },
            dismissButton = {
                TextButton(onClick = { pendingShareFile = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DbgPrimaryButton(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) br.brand else br.bgElev2)
            .border(
                1.dp,
                if (active) Color.Transparent else br.hairline2,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) br.bg else br.fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DbgGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = br.fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CaptureLogCard(file: File, onShare: () -> Unit) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = br.fg,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            Text(
                text = "${file.length() / 1024} KB · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(file.lastModified()))}",
                color = br.fgDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        DbgGhostButton(text = "Share", onClick = onShare)
    }
}

private fun startForegroundServiceCompat(ctx: Context, intent: Intent) {
    androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
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
