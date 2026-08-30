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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.CaptureLogFiles
import es.jjrh.bikeradar.CaptureLogManager
import es.jjrh.bikeradar.CrashLogger
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.DebugOverlayService
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.LinkEventJournal
import es.jjrh.bikeradar.R
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
    // Mutable so the list re-renders after a delete. Re-enumerated from disk
    // (not mutated in place) so it always reflects the true on-disk state.
    var logFiles by remember { mutableStateOf(enumerateCaptureLogs(ctx)) }
    var crashFiles by remember { mutableStateOf(enumerateCrashLogs(ctx)) }
    var journalTail by remember { mutableStateOf(readLinkJournal(ctx)) }
    var journalExpanded by remember { mutableStateOf(false) }
    var pendingDeleteAll by remember { mutableStateOf(false) }

    var replayRunning by remember { mutableStateOf(ReplayService.isRunning) }
    var radarRawHex by remember { mutableStateOf("06 09 01 13") }
    var synthRunning by remember { mutableStateOf(SyntheticScenarioService.isRunning) }
    var screenshotRunning by remember { mutableStateOf(ScreenshotCaptureService.isRunning) }
    var stateLogExpanded by remember { mutableStateOf(false) }
    // Plain remember: rotation cancels the dialog. File doesn't auto-save
    // and the worst case is the user re-taps Share and re-sees the warning,
    // which is fine in a debug screen.
    var pendingShareFile by remember { mutableStateOf<File?>(null) }

    val msgScreenshotArmed = stringResource(R.string.debug_toast_screenshot_armed)
    val msgScreenCaptureDenied = stringResource(R.string.debug_toast_screen_capture_denied)
    val msgForceReconnectSent = stringResource(R.string.debug_toast_force_reconnect_sent)
    val msgDiscoverySent = stringResource(R.string.debug_toast_discovery_sent)
    val msgStateSent = stringResource(R.string.debug_toast_state_sent)

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(ctx, ScreenshotCaptureService::class.java).apply {
                action = ScreenshotCaptureService.ACTION_START
                putExtra(ScreenshotCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenshotCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundServiceCompat(ctx, intent)
            screenshotRunning = true
            Toast.makeText(ctx, msgScreenshotArmed, Toast.LENGTH_SHORT).show()
        } else {
            screenshotRunning = false
            Toast.makeText(ctx, msgScreenCaptureDenied, Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Service-state poll pauses with the screen — no point waking
    // up the static fields every half-second from the background.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Refresh the log lists once on resume so a capture or crash
            // written while this screen was backgrounded shows up without
            // forcing a delete.
            logFiles = enumerateCaptureLogs(ctx)
            crashFiles = enumerateCrashLogs(ctx)
            journalTail = readLinkJournal(ctx)
            while (true) {
                delay(500)
                replayRunning = ReplayService.isRunning
                synthRunning = SyntheticScenarioService.isRunning
                screenshotRunning = ScreenshotCaptureService.isRunning
            }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            RadarStateBus.state.collect { s ->
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
                val ageMs = if (s.timestamp > 0L) System.currentTimeMillis() - s.timestamp else -1L
                val src = when (s.source) {
                    DataSource.V2 -> "v=2"
                    DataSource.V1 -> "v=1"
                    DataSource.NONE -> "v=?"
                }
                val n = s.vehicles.size
                val entry = "$ts  $src  n=$n  age=${ageMs}ms"
                if (stateLog.size >= 200) stateLog.removeAt(0)
                stateLog.add(entry)
            }
        }
    }

    LaunchedEffect(stateLog.size) {
        if (stateLog.isNotEmpty()) logScroll.animateScrollToItem(stateLog.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.debug_title), onBack = { navController.popBackStack() })

            // Scenarios
            DebugScenarioControls(
                replayRunning = replayRunning,
                syntheticRunning = synthRunning,
                onStartReplay = {
                    ctx.stopService(Intent(ctx, SyntheticScenarioService::class.java))
                    startForegroundServiceCompat(ctx, Intent(ctx, DebugOverlayService::class.java))
                    startForegroundServiceCompat(ctx, Intent(ctx, ReplayService::class.java))
                },
                onStopReplay = {
                    ctx.stopService(Intent(ctx, ReplayService::class.java))
                    ctx.stopService(Intent(ctx, DebugOverlayService::class.java))
                },
                onStartSynthetic = {
                    ctx.stopService(Intent(ctx, ReplayService::class.java))
                    startForegroundServiceCompat(ctx, Intent(ctx, DebugOverlayService::class.java))
                    startForegroundServiceCompat(ctx, Intent(ctx, SyntheticScenarioService::class.java))
                },
                onStopSynthetic = {
                    ctx.stopService(Intent(ctx, SyntheticScenarioService::class.java))
                    ctx.stopService(Intent(ctx, DebugOverlayService::class.java))
                },
            )

            // Service control
            SettingsSectionLabel(stringResource(R.string.debug_section_service_control))
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                DbgGhostButton(
                    text = stringResource(R.string.debug_force_radar_reconnect),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        ctx.startService(
                            Intent(ctx, BikeRadarService::class.java).apply {
                                action = BikeRadarService.ACTION_FORCE_RECONNECT
                            },
                        )
                        Toast.makeText(ctx, msgForceReconnectSent, Toast.LENGTH_SHORT).show()
                    },
                )
            }

            DebugCuePreview(
                onPreview = { cue ->
                    ctx.startService(
                        Intent(ctx, BikeRadarService::class.java).apply {
                            action = BikeRadarService.ACTION_PREVIEW_CUE
                            putExtra(BikeRadarService.EXTRA_CUE, cue)
                        },
                    )
                },
            )

            // Capture logging master switch (opt-in, off by default). Placed
            // high - above the niche dev tools below - because it (and the logs
            // it produces) is the most-used Debug surface.
            SettingsSectionLabel(stringResource(R.string.debug_section_capture_logging))
            SettingsRowGroup {
                SettingsToggleRow(
                    title = stringResource(R.string.debug_write_capture_logs_title),
                    subtitle = stringResource(R.string.debug_write_capture_logs_subtitle),
                    checked = prefsSnap.captureLoggingEnabled,
                    onCheckedChange = { prefs.captureLoggingEnabled = it },
                )
                SettingsToggleRow(
                    title = stringResource(R.string.debug_setup_transcript_title),
                    subtitle = stringResource(R.string.debug_setup_transcript_subtitle),
                    checked = prefsSnap.setupTranscriptEnabled,
                    // Inert without the master switch, so it says so structurally
                    // rather than only in prose. Auto-enabling the master instead
                    // would start ride-tracking-grade logging without the rider
                    // seeing that row's own disclosure, and hiding the row would
                    // break the issue template, which tells a reporter to find
                    // this one next to it.
                    enabled = prefsSnap.captureLoggingEnabled,
                    onCheckedChange = { prefs.setupTranscriptEnabled = it },
                )
            }

            // Capture logs
            DebugCaptureLogList(
                logFiles = logFiles,
                onShare = { f ->
                    onCaptureLogShareRequested(
                        flush = BikeRadarService.flushCaptureLogForUi,
                        warningSeen = prefs.captureLogShareWarningSeenV2,
                        share = { shareFile(ctx, f) },
                        requestWarning = { pendingShareFile = f },
                    )
                },
                onDelete = { f ->
                    f.delete()
                    logFiles = enumerateCaptureLogs(ctx)
                },
                onDeleteAll = { pendingDeleteAll = true },
            )

            // Crash reports. Stack traces only - no ride data - so sharing
            // skips the capture-log privacy warning dialog.
            DebugCrashLogList(
                crashFiles = crashFiles,
                dirtyRestarts = prefs.dirtyRestartCount,
                onShare = { f -> shareFile(ctx, f) },
                onDelete = { f ->
                    f.delete()
                    crashFiles = enumerateCrashLogs(ctx)
                },
            )

            // Link-event journal: always-on lifecycle log for both BLE links.
            // Answers "why didn't it reconnect?" after the fact - the capture
            // log can't (opt-in, and only open while a connection is live).
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clickable { journalExpanded = !journalExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.debug_link_journal, journalTail.size),
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                )
                Icon(
                    imageVector = if (journalExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = br.fgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (journalExpanded) {
                if (journalTail.isEmpty()) {
                    Text(
                        text = stringResource(R.string.debug_no_link_events),
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
                        for (entry in journalTail.take(50)) {
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

            // Screenshot capture
            SettingsSectionLabel(stringResource(R.string.debug_section_screenshot_capture))
            SettingsRowGroup {
                SettingsToggleRow(
                    title = stringResource(R.string.debug_periodic_screenshots_title),
                    subtitle = stringResource(R.string.debug_periodic_screenshots_subtitle),
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
                                },
                            )
                            screenshotRunning = false
                        }
                    },
                )
            }

            // Manual HA push
            SettingsSectionLabel(stringResource(R.string.debug_section_manual_ha_push))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DbgGhostButton(
                    text = stringResource(R.string.debug_send_discovery),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val creds = HaCredentials(ctx)
                        scope.launch(Dispatchers.IO) {
                            HaClient(creds.baseUrl, creds.token)
                                .publishBatteryDiscovery("debug_dummy", "Debug Dummy")
                        }
                        Toast.makeText(ctx, msgDiscoverySent, Toast.LENGTH_SHORT).show()
                    },
                )
                DbgGhostButton(
                    text = stringResource(R.string.debug_send_battery_50),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val creds = HaCredentials(ctx)
                        scope.launch(Dispatchers.IO) {
                            HaClient(creds.baseUrl, creds.token)
                                .publishBatteryState("debug_dummy", 50)
                        }
                        Toast.makeText(ctx, msgStateSent, Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // Diagnostics
            SettingsSectionLabel(stringResource(R.string.debug_section_diagnostics))
            SettingsRowGroup {
                SettingsToggleRow(
                    title = stringResource(R.string.debug_log_unknown_ebike_ids_title),
                    subtitle = stringResource(R.string.debug_log_unknown_ebike_ids_subtitle),
                    checked = prefsSnap.eBikeUnknownObjectLogEnabled,
                    onCheckedChange = { prefs.eBikeUnknownObjectLogEnabled = it },
                )
                SettingsToggleRow(
                    title = stringResource(R.string.debug_probe_radar_settings_title),
                    subtitle = stringResource(R.string.debug_probe_radar_settings_subtitle),
                    checked = prefsSnap.radarSettingsProbeEnabled,
                    onCheckedChange = { prefs.radarSettingsProbeEnabled = it },
                )
            }
            // Write-probe: shown only while the probe toggle is on. Sends writes
            // to the radar's 6a4e2f11 via the live connection (same-uid
            // startService rather than a broadcast, which is not a supported
            // route to the non-exported receiver). Tap, watch the tail light,
            // read the radar_probe_write + radar_2f14 echo in the capture log.
            if (prefsSnap.radarSettingsProbeEnabled) {
                val sendRadarHex: (String) -> Unit = { hex ->
                    ctx.startService(
                        Intent(ctx, BikeRadarService::class.java).apply {
                            this.action = BikeRadarService.ACTION_RADAR_LIGHT_PROBE_WRITE
                            putExtra(BikeRadarService.EXTRA_RADAR_LIGHT_HEX, hex)
                        },
                    )
                    Toast.makeText(ctx, "radar write $hex", Toast.LENGTH_SHORT).show()
                }
                // Set mode by stable TYPE: 06 09 01 TT. Sweep 0x10..0x1f to find
                // unmapped modes (Peloton etc.) - watch which TT changes the light.
                SettingsSectionLabel("Set mode by type (06 09 01 TT)")
                listOf(0x10..0x17, 0x18..0x1f).forEach { range ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        range.forEach { tt ->
                            val hex = "0609 01 %02x".format(tt)
                            DbgGhostButton(
                                text = "%02x".format(tt),
                                modifier = Modifier.weight(1f),
                                onClick = { sendRadarHex(hex) },
                            )
                        }
                    }
                }
                // Slot-select 07 00 NN (legacy; depends on the current slot list).
                SettingsSectionLabel("Select slot (07 00 NN)")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (0..5).forEach { nn ->
                        DbgGhostButton(
                            text = "$nn",
                            modifier = Modifier.weight(1f),
                            onClick = { sendRadarHex("0700 %02x".format(nn)) },
                        )
                    }
                }
                // Free-form: any hex written raw to 2f11 (slot-list config, etc.).
                SettingsSectionLabel("Raw write to 2f11 (hex)")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = radarRawHex,
                        onValueChange = { radarRawHex = it },
                        singleLine = true,
                        label = { Text("hex") },
                        modifier = Modifier.weight(1f),
                    )
                    DbgGhostButton(
                        text = stringResource(R.string.debug_send),
                        onClick = { sendRadarHex(radarRawHex) },
                    )
                }
            }
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                DbgGhostButton(
                    text = stringResource(R.string.debug_copy_diagnostic_bundle),
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
                    text = stringResource(R.string.debug_radar_state_log, stateLog.size),
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                )
                Icon(
                    imageVector = if (stateLogExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = br.fgDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (stateLogExpanded) {
                if (stateLog.isEmpty()) {
                    Text(
                        text = stringResource(R.string.debug_no_states_received),
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
                    text = stringResource(R.string.debug_lock_developer_mode),
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
            title = { Text(stringResource(R.string.debug_share_log_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.debug_share_log_dialog_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.captureLogShareWarningSeenV2 = true
                    // Kept in step so a downgrade does not re-prompt.
                    prefs.captureLogShareWarningSeen = true
                    pendingShareFile = null
                    shareFile(ctx, file)
                }) { Text(stringResource(R.string.debug_share_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingShareFile = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (pendingDeleteAll) {
        AlertDialog(
            onDismissRequest = { pendingDeleteAll = false },
            title = { Text(stringResource(R.string.debug_delete_all_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.debug_delete_all_dialog_body, logFiles.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    logFiles.forEach { it.delete() }
                    logFiles = enumerateCaptureLogs(ctx)
                    pendingDeleteAll = false
                }) { Text(stringResource(R.string.debug_delete_all)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAll = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * What happens when the rider taps Share on a capture log.
 *
 * A function rather than a lambda body because both halves are easy to get
 * wrong silently. [flush] must run FIRST and unconditionally: the log may still
 * be open, the writer is buffered on purpose (autoFlush would cost a syscall
 * per BLE notify), so without it a shared in-progress log ends mid-window and
 * the last thing that happened is the part missing. It is a no-op for a closed
 * file and null when the service is not running, so there is nothing to decide
 * and no name comparison to get wrong.
 *
 * [warningSeen] reads the V2 key deliberately. The dialog body names the radar
 * hardware identifiers a setup transcript records, so a rider who dismissed the
 * older dialog has to meet it once more, and that rider is exactly the one
 * filing a hardware report.
 */
internal fun onCaptureLogShareRequested(
    flush: (() -> Unit)?,
    warningSeen: Boolean,
    share: () -> Unit,
    requestWarning: () -> Unit,
) {
    flush?.invoke()
    if (warningSeen) share() else requestWarning()
}

/**
 * Enumerate the shareable/deletable capture logs on disk, newest first.
 *
 * INCLUDING the one currently being written, which used to be filtered out.
 * That exclusion is reasonable for an ordinary ride log and wrong for the
 * setup transcript, whose whole purpose is diagnosing a radar that never
 * connects: it deliberately stays open across the reconnect loop, so hiding
 * open files hid precisely the file a reporter needs, and getting at it meant
 * a toggle-off dance that only works while the radar is still switched on.
 * The row marks it as recording, flushes before sharing, and refuses delete.
 *
 * Re-run after a delete so the list reflects the true on-disk state rather
 * than a stale in-memory copy.
 */
internal fun enumerateCaptureLogs(ctx: Context): List<File> = ctx.getExternalFilesDir(null)
    ?.let { File(it, CaptureLogManager.CAPTURE_DIR) }
    ?.listFiles { f -> CaptureLogFiles.isCaptureLog(f) && f.length() > 0L }
    ?.sortedByDescending { it.lastModified() }
    ?: emptyList()

/**
 * Stateless leaf rendering the Replay/Synthetic scenario buttons.
 * Body owns the [ReplayService] / [SyntheticScenarioService] state
 * polling and the start/stop intent dispatch; this composable just
 * renders the row and routes taps to the appropriate callback.
 */
@Composable
internal fun DebugScenarioControls(
    replayRunning: Boolean,
    syntheticRunning: Boolean,
    onStartReplay: () -> Unit,
    onStopReplay: () -> Unit,
    onStartSynthetic: () -> Unit,
    onStopSynthetic: () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.debug_section_scenarios))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DbgPrimaryButton(
            text = if (replayRunning) {
                stringResource(R.string.debug_stop_replay)
            } else {
                stringResource(R.string.debug_replay)
            },
            active = replayRunning,
            modifier = Modifier.weight(1f),
            onClick = if (replayRunning) onStopReplay else onStartReplay,
        )
        DbgPrimaryButton(
            text = if (syntheticRunning) {
                stringResource(R.string.debug_stop_synthetic)
            } else {
                stringResource(R.string.debug_synthetic)
            },
            active = syntheticRunning,
            modifier = Modifier.weight(1f),
            onClick = if (syntheticRunning) onStopSynthetic else onStartSynthetic,
        )
    }
}

/** Newest-first tail of the always-on BLE link-event journal. */
private fun readLinkJournal(ctx: Context): List<String> = LinkEventJournal({ ctx.getExternalFilesDir(null) }).readTail()

/** Crash reports on disk, newest first (written by [CrashLogger]). */
private fun enumerateCrashLogs(ctx: Context): List<File> = ctx.getExternalFilesDir(null)
    ?.let { File(it, CrashLogger.CRASH_DIR) }
    ?.listFiles { f -> f.isFile && f.name.startsWith(CrashLogger.FILE_PREFIX) }
    ?.sortedByDescending { it.lastModified() }
    ?: emptyList()

/**
 * Stateless leaf rendering the crash-report list plus the unclean-restart
 * counter. The counter surfaces silent crash/kill loops the rider would
 * never notice mid-ride; the list makes the actual reports shareable from
 * the phone instead of needing `adb pull`.
 */
@Composable
internal fun DebugCrashLogList(
    crashFiles: List<File>,
    dirtyRestarts: Int,
    onShare: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    val br = LocalBrColors.current
    SettingsSectionLabel(stringResource(R.string.debug_section_crashes))
    Text(
        text = stringResource(R.string.debug_dirty_restart_count, dirtyRestarts),
        color = br.fgDim,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    if (crashFiles.isEmpty()) {
        Text(
            text = stringResource(R.string.debug_no_crashes),
            color = br.fgDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    } else {
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (f in crashFiles) {
                CaptureLogCard(file = f, onShare = { onShare(f) }, onDelete = { onDelete(f) })
            }
        }
    }
}

/**
 * Stateless leaf rendering the capture-log file list with per-row
 * share buttons. Body owns the disk enumeration of log files and the
 * share-warning dialog; this composable just renders the list.
 */
@Composable
internal fun DebugCaptureLogList(
    logFiles: List<File>,
    onShare: (File) -> Unit,
    onDelete: (File) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val br = LocalBrColors.current
    if (logFiles.isEmpty()) {
        SettingsSectionLabel(stringResource(R.string.debug_section_capture_logs))
        Text(
            text = stringResource(R.string.debug_no_capture_logs),
            color = br.fgDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    } else {
        // Header carries the bulk action; matches SettingsSectionLabel's type
        // so the "CAPTURE LOGS" caption lines up with the other sections.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_capture_logs_header),
                color = br.fgDim,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
            )
            DbgGhostButton(text = stringResource(R.string.debug_delete_all), onClick = onDeleteAll)
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (f in logFiles) {
                CaptureLogCard(
                    file = f,
                    onShare = { onShare(f) },
                    onDelete = { onDelete(f) },
                    isRecording = f.name == BikeRadarService.activeCaptureLogName,
                )
            }
        }
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

/** Dev cue-audition section (stateless leaf): a button per alert cue that calls
 *  [onPreview] with the cue-name constant. The caller routes it to the running
 *  service's warm beeper. Lets the rider compare the radar-reconnect pulse to
 *  the drop and close-pass cues without staging a real mid-ride drop. */
@Composable
internal fun DebugCuePreview(onPreview: (String) -> Unit) {
    val br = LocalBrColors.current
    SettingsSectionLabel(stringResource(R.string.debug_section_cue_preview))
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val cues = listOf(
            R.string.debug_cue_beep1 to BikeRadarService.CUE_BEEP_1,
            R.string.debug_cue_beep2 to BikeRadarService.CUE_BEEP_2,
            R.string.debug_cue_beep3 to BikeRadarService.CUE_BEEP_3,
            R.string.debug_cue_clear to BikeRadarService.CUE_CLEAR,
            R.string.debug_cue_urgent to BikeRadarService.CUE_URGENT,
            R.string.debug_cue_dropped to BikeRadarService.CUE_DROPPED,
            R.string.debug_cue_reconnected to BikeRadarService.CUE_RECONNECTED,
        )
        for ((labelRes, cue) in cues) {
            DbgGhostButton(
                text = stringResource(labelRes),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPreview(cue) },
            )
        }
        Text(
            text = stringResource(R.string.debug_cue_preview_hint),
            color = br.fgMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
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
private fun CaptureLogCard(
    file: File,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    isRecording: Boolean = false,
) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                text = stringResource(
                    R.string.debug_capture_log_meta,
                    file.length() / 1024,
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(file.lastModified())),
                ),
                color = br.fgDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            if (isRecording) {
                Text(
                    text = stringResource(R.string.debug_capture_log_recording),
                    color = br.caution,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
        DbgGhostButton(text = stringResource(R.string.debug_share), onClick = onShare)
        // No delete while it is being written: the writer holds the file open,
        // so a delete here removes the entry under a live PrintWriter and the
        // rider loses the session they are in the middle of recording.
        if (!isRecording) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.debug_delete_log_cd, file.name),
                    // Match the "Share" label's weight, not the dim caption grey -
                    // a no-undo destructive control shouldn't be the faintest thing
                    // in the row.
                    tint = br.fg,
                )
            }
        }
    }
}

private fun startForegroundServiceCompat(ctx: Context, intent: Intent) {
    androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
}

private fun shareFile(ctx: Context, f: File) {
    if (!f.exists() || f.length() == 0L) {
        Toast.makeText(ctx, ctx.getString(R.string.debug_toast_log_missing_or_empty), Toast.LENGTH_SHORT).show()
        return
    }
    val uri = try {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    } catch (t: IllegalArgumentException) {
        Toast.makeText(ctx, ctx.getString(R.string.debug_toast_cannot_share_file, t.message), Toast.LENGTH_LONG).show()
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
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.debug_share_chooser_title, f.name)))
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
        ?.let { File(it, CaptureLogManager.CAPTURE_DIR) }
        ?.listFiles { f -> CaptureLogFiles.isCaptureLog(f) }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
    sb.appendLine("--- Capture logs (${logFiles.size} on disk) ---")
    logFiles.take(3).forEach { sb.appendLine("${it.name}  ${it.length() / 1024}KB") }
    val journal = readLinkJournal(ctx)
    sb.appendLine("--- Link journal (newest ${journal.size.coerceAtMost(40)}) ---")
    journal.take(40).forEach { sb.appendLine(it) }
    val crashFiles = enumerateCrashLogs(ctx)
    sb.appendLine()
    sb.appendLine("--- Crash reports (${crashFiles.size} on disk) ---")
    crashFiles.take(3).forEach { sb.appendLine(it.name) }
    crashFiles.firstOrNull()?.let { newest ->
        sb.appendLine()
        sb.appendLine("--- Newest crash report ---")
        // Crash reports are version + thread + stack trace, a few KB at most.
        sb.append(runCatching { newest.readText() }.getOrDefault("(unreadable)"))
    }

    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("diagnostic", sb.toString()))
    Toast.makeText(ctx, ctx.getString(R.string.debug_toast_diagnostic_copied), Toast.LENGTH_SHORT).show()
}
