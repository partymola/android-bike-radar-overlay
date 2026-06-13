// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import java.util.Locale

@Composable
fun SettingsRadar(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsRadarBody(navController, prefs)
    }
}

@Composable
private fun SettingsRadarBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val creds = remember { HaCredentials(ctx) }
    val haConfigured = creds.baseUrl.isNotBlank() && creds.token.isNotBlank()

    // Slider/toggle UI state is saveable so an in-progress drag survives
    // Activity recreate (rotation, system trim). Each onValueChangeFinished
    // commits to Prefs, which is the durable backing store.
    var alertVol by rememberSaveable { mutableIntStateOf(prefs.alertVolume) }
    var alertDist by rememberSaveable { mutableIntStateOf(prefs.alertMaxDistanceM) }
    var visualDist by rememberSaveable { mutableIntStateOf(prefs.visualMaxDistanceM) }
    var overlayOpacity by rememberSaveable { mutableFloatStateOf(prefs.overlayOpacity) }
    var adaptive by rememberSaveable { mutableStateOf(prefs.adaptiveAlertsEnabled) }
    var urgentLowSpeed by rememberSaveable { mutableStateOf(prefs.urgentLowSpeedEnabled) }
    var batteryThreshold by rememberSaveable { mutableIntStateOf(prefs.batteryLowThresholdPct) }
    var batteryShowLabels by rememberSaveable { mutableStateOf(prefs.batteryShowLabels) }
    var closePassLogging by rememberSaveable { mutableStateOf(prefs.closePassLoggingEnabled) }
    var closePassEmitMinX by rememberSaveable { mutableFloatStateOf(prefs.closePassEmitMinRangeXM) }
    var closePassRiderFloor by rememberSaveable { mutableIntStateOf(prefs.closePassRiderSpeedFloorKmh) }
    var closePassClosingFloor by rememberSaveable { mutableIntStateOf(prefs.closePassClosingSpeedFloorMs) }
    var radarLongOfflineThreshold by rememberSaveable { mutableIntStateOf(prefs.radarLongOfflineThresholdMinutes) }
    var radarLongOfflineCap by rememberSaveable { mutableIntStateOf(prefs.radarLongOfflineCapSec) }
    var bannerPersistent by rememberSaveable { mutableStateOf(prefs.reconnectBannerPersistent) }
    // serviceEnabled is binary and atomic — no in-progress drag state to mirror —
    // so derive from prefs.flow instead of a local rememberSaveable. Keeps the
    // Danger-zone row honest if anything else (future MainScreen action,
    // BootReceiver edge case) flips the pref while Settings is composed.
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val serviceEnabled = prefsSnap.serviceEnabled
    var showStopDialog by rememberSaveable { mutableStateOf(false) }

    SettingsRadarContent(
        navController = navController,
        haConfigured = haConfigured,
        serviceEnabled = serviceEnabled,
        alertVol = alertVol,
        onAlertVolChange = { alertVol = it },
        onAlertVolFinished = { prefs.alertVolume = alertVol },
        alertDist = alertDist,
        onAlertDistChange = { alertDist = it },
        onAlertDistFinished = { prefs.alertMaxDistanceM = alertDist },
        visualDist = visualDist,
        onVisualDistChange = { visualDist = it },
        onVisualDistFinished = { prefs.visualMaxDistanceM = visualDist },
        overlayOpacity = overlayOpacity,
        onOverlayOpacityChange = { overlayOpacity = it },
        onOverlayOpacityFinished = { prefs.overlayOpacity = overlayOpacity },
        adaptive = adaptive,
        onAdaptiveChange = {
            adaptive = it
            prefs.adaptiveAlertsEnabled = it
        },
        urgentLowSpeed = urgentLowSpeed,
        onUrgentLowSpeedChange = {
            urgentLowSpeed = it
            prefs.urgentLowSpeedEnabled = it
        },
        batteryThreshold = batteryThreshold,
        onBatteryThresholdChange = { batteryThreshold = it },
        onBatteryThresholdFinished = { prefs.batteryLowThresholdPct = batteryThreshold },
        batteryShowLabels = batteryShowLabels,
        onBatteryShowLabelsChange = {
            batteryShowLabels = it
            prefs.batteryShowLabels = it
        },
        closePassLogging = closePassLogging,
        onClosePassLoggingChange = {
            closePassLogging = it
            prefs.closePassLoggingEnabled = it
        },
        closePassEmitMinX = closePassEmitMinX,
        onClosePassEmitMinXChange = { closePassEmitMinX = it },
        onClosePassEmitMinXFinished = { prefs.closePassEmitMinRangeXM = closePassEmitMinX },
        closePassRiderFloor = closePassRiderFloor,
        onClosePassRiderFloorChange = { closePassRiderFloor = it },
        onClosePassRiderFloorFinished = { prefs.closePassRiderSpeedFloorKmh = closePassRiderFloor },
        closePassClosingFloor = closePassClosingFloor,
        onClosePassClosingFloorChange = { closePassClosingFloor = it },
        onClosePassClosingFloorFinished = { prefs.closePassClosingSpeedFloorMs = closePassClosingFloor },
        radarLongOfflineThreshold = radarLongOfflineThreshold,
        onRadarLongOfflineThresholdChange = { radarLongOfflineThreshold = it },
        onRadarLongOfflineThresholdFinished = { prefs.radarLongOfflineThresholdMinutes = radarLongOfflineThreshold },
        radarLongOfflineCap = radarLongOfflineCap,
        onRadarLongOfflineCapChange = { radarLongOfflineCap = it },
        onRadarLongOfflineCapFinished = { prefs.radarLongOfflineCapSec = radarLongOfflineCap },
        bannerPersistent = bannerPersistent,
        onBannerPersistentChange = {
            bannerPersistent = it
            prefs.reconnectBannerPersistent = it
        },
        onStopScanningClick = { showStopDialog = true },
    )

    if (showStopDialog) {
        val cancelFocus = remember { FocusRequester() }
        // M3 AlertDialog doesn't auto-focus the dismiss button; force Cancel as default.
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(stringResource(R.string.settings_radar_stop_dialog_title)) },
            text = {
                Text(stringResource(R.string.settings_radar_stop_dialog_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.serviceEnabled = false
                        // Clear any pending pause window so re-arm doesn't
                        // land back in a stale Paused state.
                        prefs.pausedUntilEpochMs = 0L
                        ctx.stopService(Intent(ctx, BikeRadarService::class.java))
                        showStopDialog = false
                    },
                ) { Text(stringResource(R.string.settings_radar_stop_scanning), color = br.danger) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showStopDialog = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                ) { Text(stringResource(R.string.settings_radar_cancel)) }
            },
        )
    }
}

/**
 * Stateless leaf - renders the scrolling Settings → Alerts
 * content from already-derived UI state. No `rememberSaveable`, no
 * `Prefs`. Visible to snapshot tests so the visual contract can be
 * locked without Prefs scaffolding. The body wires the saveable
 * slider/toggle state and the stop-scanning dialog.
 */
/**
 * Map the slider value (0.5..1.0 multiplier) to the 4-step user-facing
 * label. The slider is stepped at 4 stops so the float lands close to
 * one of these regardless of jitter.
 */
internal fun overlayDimLabel(context: Context, opacity: Float): String = when {
    opacity >= 0.95f -> context.getString(R.string.settings_radar_overlay_dim_off)
    opacity >= 0.78f -> context.getString(R.string.settings_radar_overlay_dim_light)
    opacity >= 0.6f -> context.getString(R.string.settings_radar_overlay_dim_medium)
    else -> context.getString(R.string.settings_radar_overlay_dim_strong)
}

@Composable
internal fun SettingsRadarContent(
    navController: NavController,
    haConfigured: Boolean,
    serviceEnabled: Boolean,
    alertVol: Int,
    onAlertVolChange: (Int) -> Unit,
    onAlertVolFinished: () -> Unit,
    alertDist: Int,
    onAlertDistChange: (Int) -> Unit,
    onAlertDistFinished: () -> Unit,
    visualDist: Int,
    onVisualDistChange: (Int) -> Unit,
    onVisualDistFinished: () -> Unit,
    overlayOpacity: Float,
    onOverlayOpacityChange: (Float) -> Unit,
    onOverlayOpacityFinished: () -> Unit,
    adaptive: Boolean,
    onAdaptiveChange: (Boolean) -> Unit,
    urgentLowSpeed: Boolean,
    onUrgentLowSpeedChange: (Boolean) -> Unit,
    batteryThreshold: Int,
    onBatteryThresholdChange: (Int) -> Unit,
    onBatteryThresholdFinished: () -> Unit,
    batteryShowLabels: Boolean,
    onBatteryShowLabelsChange: (Boolean) -> Unit,
    closePassLogging: Boolean,
    onClosePassLoggingChange: (Boolean) -> Unit,
    closePassEmitMinX: Float,
    onClosePassEmitMinXChange: (Float) -> Unit,
    onClosePassEmitMinXFinished: () -> Unit,
    closePassRiderFloor: Int,
    onClosePassRiderFloorChange: (Int) -> Unit,
    onClosePassRiderFloorFinished: () -> Unit,
    closePassClosingFloor: Int,
    onClosePassClosingFloorChange: (Int) -> Unit,
    onClosePassClosingFloorFinished: () -> Unit,
    radarLongOfflineThreshold: Int,
    onRadarLongOfflineThresholdChange: (Int) -> Unit,
    onRadarLongOfflineThresholdFinished: () -> Unit,
    radarLongOfflineCap: Int,
    onRadarLongOfflineCapChange: (Int) -> Unit,
    onRadarLongOfflineCapFinished: () -> Unit,
    bannerPersistent: Boolean,
    onBannerPersistentChange: (Boolean) -> Unit,
    onStopScanningClick: () -> Unit,
) {
    val br = LocalBrColors.current
    val ctx = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.settings_radar_header), onBack = { navController.popBackStack() })

            // Alerts group — sliders sit directly on the screen background
            // matching the JSX which puts them outside any card.
            SettingsSectionLabel(stringResource(R.string.settings_radar_section_alerts))
            SettingsSliderRow(
                title = stringResource(R.string.settings_radar_alert_volume_title),
                valueDisplay = stringResource(R.string.settings_radar_percent_value, alertVol),
                helper = stringResource(R.string.settings_radar_alert_volume_helper),
                value = alertVol.toFloat(),
                valueRange = 0f..100f,
                onValueChange = { onAlertVolChange(it.toInt()) },
                onValueChangeFinished = onAlertVolFinished,
            )
            SettingsSliderRow(
                title = stringResource(R.string.settings_radar_alert_distance_title),
                valueDisplay = stringResource(R.string.settings_radar_meters_value, alertDist),
                helper = stringResource(R.string.settings_radar_alert_distance_helper),
                value = alertDist.toFloat(),
                valueRange = 10f..40f,
                onValueChange = { onAlertDistChange(it.toInt()) },
                onValueChangeFinished = onAlertDistFinished,
            )
            SettingsRowGroup {
                SettingsToggleRow(
                    leadingIcon = Icons.Default.NotificationsActive,
                    leadingTint = br.danger,
                    title = stringResource(R.string.settings_radar_urgent_low_speed_title),
                    subtitle = stringResource(R.string.settings_radar_urgent_low_speed_subtitle),
                    checked = urgentLowSpeed,
                    onCheckedChange = onUrgentLowSpeedChange,
                )
            }
            SettingsSectionLabel(stringResource(R.string.settings_radar_section_overlay))
            SettingsSliderRow(
                title = stringResource(R.string.settings_radar_visual_distance_title),
                valueDisplay = stringResource(R.string.settings_radar_meters_value, visualDist),
                helper = stringResource(R.string.settings_radar_visual_distance_helper),
                value = visualDist.toFloat(),
                valueRange = 10f..80f,
                onValueChange = { onVisualDistChange(it.toInt()) },
                onValueChangeFinished = onVisualDistFinished,
            )
            SettingsSliderRow(
                title = stringResource(R.string.settings_radar_overlay_dimmer_title),
                valueDisplay = overlayDimLabel(ctx, overlayOpacity),
                helper = stringResource(R.string.settings_radar_overlay_dimmer_helper),
                value = overlayOpacity,
                valueRange = 0.5f..1.0f,
                steps = 2,
                onValueChange = onOverlayOpacityChange,
                onValueChangeFinished = onOverlayOpacityFinished,
            )

            SettingsSectionLabel(stringResource(R.string.settings_radar_section_adaptive))
            SettingsRowGroup {
                SettingsToggleRow(
                    leadingIcon = Icons.Default.Speed,
                    leadingTint = br.brand,
                    title = stringResource(R.string.settings_radar_adaptive_title),
                    subtitle = stringResource(R.string.settings_radar_adaptive_subtitle),
                    checked = adaptive,
                    onCheckedChange = onAdaptiveChange,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_radar_section_connection))
            SettingsSliderRow(
                title = stringResource(R.string.settings_radar_idle_after_title),
                valueDisplay = stringResource(R.string.settings_radar_minutes_value, radarLongOfflineThreshold),
                helper = stringResource(R.string.settings_radar_idle_after_helper),
                value = radarLongOfflineThreshold.toFloat(),
                valueRange = 5f..120f,
                onValueChange = { onRadarLongOfflineThresholdChange(it.toInt()) },
                onValueChangeFinished = onRadarLongOfflineThresholdFinished,
            )
            SettingsSliderRow(
                title = stringResource(R.string.settings_radar_idle_interval_title),
                valueDisplay = stringResource(R.string.settings_radar_seconds_value, radarLongOfflineCap),
                helper = stringResource(R.string.settings_radar_idle_interval_helper),
                value = radarLongOfflineCap.toFloat(),
                valueRange = 5f..120f,
                onValueChange = { onRadarLongOfflineCapChange(it.toInt()) },
                onValueChangeFinished = onRadarLongOfflineCapFinished,
            )
            SettingsRowGroup {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_radar_banner_persistent_title),
                    subtitle = stringResource(R.string.settings_radar_banner_persistent_subtitle),
                    checked = bannerPersistent,
                    onCheckedChange = onBannerPersistentChange,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_radar_section_battery))
            NestedCard {
                SettingsSliderRow(
                    title = stringResource(R.string.settings_radar_low_battery_title),
                    valueDisplay = stringResource(R.string.settings_radar_percent_value, batteryThreshold),
                    helper = stringResource(R.string.settings_radar_low_battery_helper),
                    value = batteryThreshold.toFloat(),
                    valueRange = 10f..50f,
                    onValueChange = { onBatteryThresholdChange(it.toInt()) },
                    onValueChangeFinished = onBatteryThresholdFinished,
                    paddingHorizontal = 0.dp,
                    paddingBottom = 0.dp,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            SettingsRowGroup {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_radar_show_labels_title),
                    subtitle = stringResource(R.string.settings_radar_show_labels_subtitle),
                    checked = batteryShowLabels,
                    onCheckedChange = onBatteryShowLabelsChange,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_radar_section_close_pass))
            SettingsRowGroup {
                SettingsToggleRow(
                    leadingIcon = Icons.Default.Sensors,
                    leadingTint = br.safe,
                    title = stringResource(R.string.settings_radar_close_pass_title),
                    subtitle = if (haConfigured) {
                        stringResource(R.string.settings_radar_close_pass_subtitle_ha)
                    } else {
                        stringResource(R.string.settings_radar_close_pass_subtitle)
                    },
                    checked = closePassLogging,
                    onCheckedChange = onClosePassLoggingChange,
                )
            }

            if (closePassLogging) {
                Spacer(modifier = Modifier.height(8.dp))
                NestedCard {
                    Column {
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_radar_lateral_clearance_title),
                            valueDisplay = stringResource(
                                R.string.settings_radar_meters_decimal_value,
                                String.format(Locale.US, "%.1f", closePassEmitMinX),
                            ),
                            helper = stringResource(R.string.settings_radar_lateral_clearance_helper),
                            value = closePassEmitMinX,
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            onValueChange = onClosePassEmitMinXChange,
                            onValueChangeFinished = onClosePassEmitMinXFinished,
                            paddingHorizontal = 0.dp,
                            paddingBottom = 14.dp,
                        )
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_radar_min_rider_speed_title),
                            valueDisplay = stringResource(R.string.settings_radar_kmh_value, closePassRiderFloor),
                            helper = stringResource(R.string.settings_radar_min_rider_speed_helper),
                            value = closePassRiderFloor.toFloat(),
                            valueRange = 5f..30f,
                            steps = 4,
                            onValueChange = { onClosePassRiderFloorChange(it.toInt()) },
                            onValueChangeFinished = onClosePassRiderFloorFinished,
                            paddingHorizontal = 0.dp,
                            paddingBottom = 14.dp,
                        )
                        SettingsSliderRow(
                            title = stringResource(R.string.settings_radar_min_closing_speed_title),
                            valueDisplay = stringResource(R.string.settings_radar_ms_value, closePassClosingFloor),
                            helper = stringResource(
                                R.string.settings_radar_min_closing_speed_helper,
                                (closePassClosingFloor * 3.6).toInt(),
                            ),
                            value = closePassClosingFloor.toFloat(),
                            valueRange = 3f..15f,
                            steps = 11,
                            onValueChange = { onClosePassClosingFloorChange(it.toInt()) },
                            onValueChangeFinished = onClosePassClosingFloorFinished,
                            paddingHorizontal = 0.dp,
                            paddingBottom = 0.dp,
                        )
                    }
                }
            }

            // Indefinite kill-switch (survives reboot). Pause is the time-bounded variant.
            SettingsSectionLabel(stringResource(R.string.settings_radar_section_danger))
            SettingsRowGroup {
                if (serviceEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BrOutlinedButton(
                            label = stringResource(R.string.settings_radar_stop_scanning),
                            tone = br.danger,
                            leadingIcon = Icons.Default.PowerSettingsNew,
                            onClick = onStopScanningClick,
                        )
                        Text(
                            text = stringResource(R.string.settings_radar_stop_scanning_helper),
                            color = br.fgDim,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    Box(modifier = Modifier.semantics(mergeDescendants = true) { }) {
                        SettingsRow(
                            icon = Icons.Default.PowerOff,
                            iconTint = br.fgMuted,
                            title = stringResource(R.string.settings_radar_scanning_stopped_title),
                            subtitle = stringResource(R.string.settings_radar_scanning_stopped_subtitle),
                            onClick = {},
                            clickable = false,
                            chevron = false,
                            isLast = true,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
