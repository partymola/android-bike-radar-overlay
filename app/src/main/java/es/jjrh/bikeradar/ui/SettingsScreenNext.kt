// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

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
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import java.util.Locale

/**
 * Redesigned Settings home. Replaces the V1 long-scroll Settings with a
 * NavHost-routed home that links to per-section sub-screens, matching
 * `settings-screens.jsx`'s `SettingsHome` composition.
 *
 * Top: SettingsHeader with chevron-back.
 * Then: System health card (compressed Radar + Cam status).
 * Then: GENERAL section (Radar & alerts, Dashcam, Home Assistant, Permissions).
 * Then: ADVANCED section (Experimental, Debug, About).
 *
 * Each row navigates to its own sub-screen. Debug + About reuse the V1
 * top-level routes; the rest are new routes added to the NavHost.
 */
@Composable
fun SettingsScreenNext(navController: NavController, prefs: Prefs) {
    NextTheme {
        SettingsScreenNextBody(navController, prefs)
    }
}

@Composable
private fun SettingsScreenNextBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
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

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            NextSettingsHeader(title = "Settings", onBack = { navController.popBackStack() })

            // System health card (the small one at the top of Settings)
            SystemHealthBar(radarBattery = radarBattery, dashcamBattery = dashcamBattery)

            NextSettingsSectionLabel("General")
            NextSettingsRowGroup {
                NextSettingsRow(
                    icon = Icons.Default.Sensors,
                    iconTint = br.brand,
                    title = "Radar & alerts",
                    subtitle = "Volume ${prefsSnap.alertVolume}% · alert at ${prefsSnap.alertMaxDistanceM} m",
                    onClick = { navController.navigate("settings/radar") },
                )
                NextSettingsRow(
                    icon = Icons.Default.Videocam,
                    iconTint = br.dashcam,
                    title = "Dashcam",
                    subtitle = dashcamSubtitle(prefsSnap),
                    onClick = { navController.navigate("settings/dashcam") },
                )
                NextSettingsRow(
                    icon = Icons.Default.Home,
                    iconTint = br.safe,
                    title = "Home Assistant",
                    subtitle = haSubtitle(haConfigured, haHealth),
                    onClick = { navController.navigate("settings/ha") },
                )
                NextSettingsRow(
                    icon = Icons.Default.Shield,
                    iconTint = if (requiredMissing > 0) br.danger else br.caution,
                    title = "Permissions",
                    subtitle = if (requiredMissing > 0)
                        "$requiredMissing of ${PERMISSIONS.size} need action"
                    else "All granted ($grantedCount of ${PERMISSIONS.size})",
                    onClick = { navController.navigate("settings/permissions") },
                    isLast = true,
                )
            }

            NextSettingsSectionLabel("Advanced")
            NextSettingsRowGroup {
                NextSettingsRow(
                    icon = Icons.Default.FlashOn,
                    iconTint = br.brand,
                    title = "Experimental",
                    subtitle = experimentalSubtitle(prefsSnap),
                    onClick = { navController.navigate("settings/experimental") },
                )
                if (devUnlocked) {
                    NextSettingsRow(
                        icon = Icons.Default.Terminal,
                        iconTint = br.fgMuted,
                        title = "Debug",
                        subtitle = "Developer mode unlocked",
                        onClick = { navController.navigate("debug") },
                    )
                }
                NextSettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = br.fgMuted,
                    title = "About",
                    subtitle = "Version ${es.jjrh.bikeradar.BuildConfig.VERSION_NAME}",
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
        SectionLabel("System")
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemHealthChip(
                label = "Radar",
                battery = radarBattery,
                color = if (radarBattery != null) br.safe else br.fgDim,
                modifier = Modifier.weight(1f),
            )
            SystemHealthChip(
                label = "Cam",
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
        if (battery != null) BatteryChip(pct = battery.pct)
        else Text(
            text = "—",
            color = br.fgFaint,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

private fun dashcamSubtitle(snap: es.jjrh.bikeradar.data.PrefsSnapshot): String = when (snap.dashcamOwnership) {
    DashcamOwnership.UNANSWERED -> "Set up your dashcam"
    DashcamOwnership.NO -> "Don't have one"
    DashcamOwnership.YES -> when {
        snap.dashcamDisplayName != null -> "${snap.dashcamDisplayName} · paired"
        snap.dashcamMac != null -> "Paired"
        else -> "Pick a device"
    }
}

private fun haSubtitle(configured: Boolean, health: HaHealth): String = when {
    !configured -> "Not configured"
    health is HaHealth.Error -> "Connection issue"
    health is HaHealth.Ok -> "Connected · MQTT ready"
    else -> "Connected"
}

private fun experimentalSubtitle(snap: es.jjrh.bikeradar.data.PrefsSnapshot): String {
    val on = listOfNotNull(
        if (snap.precogEnabled) "Precog" else null,
        if (snap.closePassLoggingEnabled) "Close-pass logging" else null,
    )
    return if (on.isEmpty()) "All off" else "${on.joinToString(", ")} on"
}

