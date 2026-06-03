// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import java.util.Locale

/**
 * Settings home. NavHost-routed home that links to per-section sub-screens,
 * matching `settings-screens.jsx`'s `SettingsHome` composition.
 *
 * Top: SettingsHeader with chevron-back.
 * Then: Quick Status card (compressed Radar + Cam status).
 * Then: RIDE (Alerts, Light auto-mode), CONNECTIONS (Dashcam, eBike,
 *   Home Assistant), SYSTEM (Permissions, Experimental, Debug, About).
 *
 * Each row navigates to its own sub-screen.
 */
@Composable
fun SettingsScreen(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsScreenBody(navController, prefs)
    }
}

@Composable
private fun SettingsScreenBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val devUnlocked by DevModeState.unlocked.collectAsState()
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val haHealth by HaHealthBus.state.collectAsState()
    val batteryEntries by BatteryStateBus.entries.collectAsState()
    val creds = remember { HaCredentials(ctx) }
    val haConfigured = creds.baseUrl.isNotBlank() && creds.token.isNotBlank()

    val radarBattery: BatteryEntry? = batteryEntries.values.firstOrNull { entry ->
        val n = entry.name.lowercase()
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    }
    val dashcamSlug = prefsSnap.dashcamMac?.let { mac ->
        BikeRadarService.macToSlug[mac]
            ?: BikeRadarService.macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefsSnap.dashcamDisplayName?.let { BikeRadarService.slug(it) }
    }
    val dashcamBattery = dashcamSlug?.let { batteryEntries[it] }

    val grantedCount = PERMISSIONS.count { isSpecGranted(ctx, it) }
    val requiredMissing = PERMISSIONS.count { it.required && !isSpecGranted(ctx, it) }

    SettingsMenuBody(
        navController = navController,
        devUnlocked = devUnlocked,
        prefsSnap = prefsSnap,
        radarBattery = radarBattery,
        dashcamBattery = dashcamBattery,
        haConfigured = haConfigured,
        haHealth = haHealth,
        permissionsGrantedCount = grantedCount,
        permissionsRequiredMissing = requiredMissing,
        permissionsTotal = PERMISSIONS.size,
    )
}

/**
 * Stateless leaf — renders the Settings home menu from already-derived
 * state. Visible to snapshot tests so the visual contract can be locked
 * without `LocalContext`, the radar/HA/battery buses, or `HaCredentials`.
 */
@Composable
internal fun SettingsMenuBody(
    navController: NavController,
    devUnlocked: Boolean,
    prefsSnap: es.jjrh.bikeradar.data.PrefsSnapshot,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    haConfigured: Boolean,
    haHealth: HaHealth,
    permissionsGrantedCount: Int,
    permissionsRequiredMissing: Int,
    permissionsTotal: Int,
) {
    val br = LocalBrColors.current
    val ctx = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(title = stringResource(R.string.common_settings), onBack = { navController.popBackStack() })

            // System health card (the small one at the top of Settings)
            SystemHealthBar(radarBattery = radarBattery, dashcamBattery = dashcamBattery)

            SettingsSectionLabel(stringResource(R.string.settings_home_section_ride))
            SettingsRowGroup {
                SettingsRow(
                    icon = Icons.Default.Notifications,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_alerts_title),
                    subtitle = stringResource(
                        R.string.settings_home_alerts_subtitle,
                        prefsSnap.alertVolume,
                        prefsSnap.alertMaxDistanceM,
                    ),
                    onClick = { navController.navigate("settings/radar") },
                )
                SettingsRow(
                    icon = Icons.Default.WbSunny,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_lights_title),
                    subtitle = lightsSubtitle(ctx, prefsSnap),
                    onClick = { navController.navigate("settings/lights") },
                    isLast = true,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_home_section_connections))
            SettingsRowGroup {
                SettingsRow(
                    icon = Icons.Default.Sensors,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_radar_title),
                    subtitle = radarConnectionSubtitle(ctx, radarBattery),
                    onClick = { navController.navigate("settings/radar-device") },
                )
                SettingsRow(
                    icon = Icons.Default.Videocam,
                    iconTint = br.dashcam,
                    title = stringResource(R.string.settings_home_dashcam_title),
                    subtitle = dashcamConnectionSubtitle(ctx, prefsSnap, dashcamBattery),
                    onClick = { navController.navigate("settings/dashcam") },
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.DirectionsBike,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_ebike_title),
                    subtitle = eBikeSubtitle(ctx, prefsSnap),
                    onClick = { navController.navigate("settings/ebike") },
                )
                SettingsRow(
                    icon = Icons.Default.Home,
                    iconTint = br.safe,
                    title = stringResource(R.string.settings_home_ha_title),
                    subtitle = haSubtitle(ctx, haConfigured, haHealth),
                    onClick = { navController.navigate("settings/ha") },
                    isLast = true,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_home_section_system))
            SettingsRowGroup {
                SettingsRow(
                    icon = Icons.Default.Shield,
                    iconTint = if (permissionsRequiredMissing > 0) br.danger else br.caution,
                    title = stringResource(R.string.settings_home_permissions_title),
                    subtitle = if (permissionsRequiredMissing > 0) {
                        stringResource(
                            R.string.settings_home_permissions_subtitle_action,
                            permissionsRequiredMissing,
                            permissionsTotal,
                        )
                    } else {
                        stringResource(
                            R.string.settings_home_permissions_subtitle_granted,
                            permissionsGrantedCount,
                            permissionsTotal,
                        )
                    },
                    onClick = { navController.navigate("settings/permissions") },
                )
                SettingsRow(
                    icon = Icons.Default.FlashOn,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_experimental_title),
                    subtitle = experimentalSubtitle(ctx, prefsSnap),
                    onClick = { navController.navigate("settings/experimental") },
                )
                if (devUnlocked) {
                    SettingsRow(
                        icon = Icons.Default.Terminal,
                        iconTint = br.fgMuted,
                        title = stringResource(R.string.settings_home_debug_title),
                        subtitle = stringResource(R.string.settings_home_debug_subtitle),
                        onClick = { navController.navigate("debug") },
                    )
                }
                SettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = br.fgMuted,
                    title = stringResource(R.string.settings_home_about_title),
                    subtitle = stringResource(
                        R.string.settings_home_about_subtitle,
                        es.jjrh.bikeradar.BuildConfig.VERSION_NAME,
                    ),
                    onClick = { navController.navigate("settings/about") },
                    isLast = true,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SystemHealthBar(radarBattery: BatteryEntry?, dashcamBattery: BatteryEntry?) {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        SectionLabel(stringResource(R.string.settings_home_quick_status))
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemHealthChip(
                label = stringResource(R.string.settings_home_chip_radar),
                battery = radarBattery,
                color = if (radarBattery != null) br.safe else br.fgDim,
                modifier = Modifier.weight(1f),
            )
            SystemHealthChip(
                label = stringResource(R.string.settings_home_chip_cam),
                battery = dashcamBattery,
                color = if (dashcamBattery != null) br.safe else br.fgDim,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SystemHealthChip(
    label: String,
    battery: BatteryEntry?,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = color, size = 6.dp)
        Text(text = label, color = br.fgMuted, fontSize = 12.sp)
        if (battery != null) {
            BatteryChip(pct = battery.pct)
        } else {
            Text(
                text = stringResource(R.string.settings_home_chip_not_seen),
                color = br.fgFaint,
                fontSize = 11.sp,
            )
        }
    }
}

// Connections rows carry live connection status in their subtitles (the
// top card is now just a glanceable "Quick Status"). A recent battery read
// is the app's proxy for "connected".
private fun radarConnectionSubtitle(ctx: Context, radarBattery: BatteryEntry?): String = if (radarBattery != null) {
    ctx.getString(R.string.settings_home_conn_connected, radarBattery.pct)
} else {
    ctx.getString(R.string.settings_home_conn_not_connected)
}

private fun dashcamConnectionSubtitle(
    ctx: Context,
    snap: es.jjrh.bikeradar.data.PrefsSnapshot,
    dashcamBattery: BatteryEntry?,
): String = if (dashcamBattery != null) {
    ctx.getString(R.string.settings_home_conn_connected, dashcamBattery.pct)
} else {
    dashcamSubtitle(ctx, snap)
}

// One row now summarises both lights. Radar-first (matches the consolidated
// screen's section order); fold in dashcam ownership so a rider with no
// dashcam isn't told about a front light they can't use.
internal fun lightsSubtitle(ctx: Context, snap: es.jjrh.bikeradar.data.PrefsSnapshot): String {
    val rear = ctx.getString(
        if (snap.radarLightAutoModeEnabled) R.string.settings_home_lights_on else R.string.settings_home_lights_off,
    )
    val hasFront = snap.dashcamOwnership == DashcamOwnership.YES
    if (!hasFront) return ctx.getString(R.string.settings_home_lights_rear_only, rear)
    val front = ctx.getString(
        if (snap.autoLightModeEnabled) R.string.settings_home_lights_on else R.string.settings_home_lights_off,
    )
    return if (!snap.radarLightAutoModeEnabled && !snap.autoLightModeEnabled) {
        ctx.getString(R.string.settings_home_lights_all_off)
    } else {
        ctx.getString(R.string.settings_home_lights_summary, rear, front)
    }
}

private fun eBikeSubtitle(ctx: Context, snap: es.jjrh.bikeradar.data.PrefsSnapshot): String = when (snap.eBikeOwnership) {
    es.jjrh.bikeradar.data.EBikeOwnership.UNANSWERED -> ctx.getString(R.string.settings_home_ebike_setup)
    es.jjrh.bikeradar.data.EBikeOwnership.NO -> ctx.getString(R.string.settings_home_dont_have_one)
    es.jjrh.bikeradar.data.EBikeOwnership.YES -> when {
        !snap.eBikeDataEnabled -> ctx.getString(R.string.settings_home_ebike_off)
        else -> ctx.getString(R.string.settings_home_ebike_on)
    }
}

private fun dashcamSubtitle(ctx: Context, snap: es.jjrh.bikeradar.data.PrefsSnapshot): String = when (snap.dashcamOwnership) {
    DashcamOwnership.UNANSWERED -> ctx.getString(R.string.settings_home_dashcam_setup)
    DashcamOwnership.NO -> ctx.getString(R.string.settings_home_dont_have_one)
    DashcamOwnership.YES -> when {
        snap.dashcamDisplayName != null ->
            ctx.getString(R.string.settings_home_dashcam_paired_named, snap.dashcamDisplayName)
        snap.dashcamMac != null -> ctx.getString(R.string.settings_home_dashcam_paired)
        else -> ctx.getString(R.string.settings_home_dashcam_pick)
    }
}

private fun haSubtitle(ctx: Context, configured: Boolean, health: HaHealth): String = when {
    !configured -> ctx.getString(R.string.settings_home_ha_not_configured)
    health is HaHealth.Error -> ctx.getString(R.string.settings_home_ha_issue)
    health is HaHealth.Ok -> ctx.getString(R.string.settings_home_ha_mqtt_ready)
    else -> ctx.getString(R.string.settings_home_ha_connected)
}

private fun experimentalSubtitle(ctx: Context, snap: es.jjrh.bikeradar.data.PrefsSnapshot): String {
    val on = buildList {
        if (snap.precogEnabled) add(ctx.getString(R.string.settings_home_exp_precog))
        if (snap.experimentalLateralPanning) add(ctx.getString(R.string.settings_home_exp_panning))
    }
    return if (on.isEmpty()) {
        ctx.getString(R.string.settings_home_exp_all_off)
    } else {
        ctx.getString(R.string.settings_home_exp_active, on.joinToString(" + "))
    }
}
