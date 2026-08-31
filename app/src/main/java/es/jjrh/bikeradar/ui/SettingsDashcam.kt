// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.ServiceNotifications
import es.jjrh.bikeradar.batteryReadIsFresh
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun SettingsDashcam(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsDashcamBody(navController, prefs)
    }
}

@Composable
private fun SettingsDashcamBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val batteryEntries by BatteryStateBus.entries.collectAsState()

    val dashcamSlug = prefsSnap.dashcamMac?.let { mac ->
        BikeRadarService.macToSlug[mac]
            ?: BikeRadarService.macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefsSnap.dashcamDisplayName?.let { BikeRadarService.slug(it) }
    }
    // Ticked, not sampled once: the entries flow only emits when a device IS
    // seen, so a screen left open would hold its last verdict indefinitely and
    // keep calling a dead dashcam connected.
    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Resume-first, then loop. The gated loop restarts on RESUME
            // and would otherwise delay BEFORE its first assignment, so a
            // screen left open across an hour of standby would difference
            // two hour-old values, get a small number, and render a dead
            // device as connected for the first five seconds back.
            tickNowMs = System.currentTimeMillis()
            while (true) {
                delay(5_000)
                tickNowMs = System.currentTimeMillis()
            }
        }
    }
    val dashcamBattery = dashcamSlug?.let { batteryEntries[it] }
    val dashcamConnected = dashcamBattery != null &&
        batteryReadIsFresh(dashcamBattery.readAtMs, tickNowMs)

    var walkAwayThreshold by rememberSaveable { mutableIntStateOf(prefs.walkAwayAlarmThresholdSec) }

    val nm = remember(ctx) { ctx.getSystemService(NotificationManager::class.java) }
    val canBypassDnd = nm
        ?.getNotificationChannel(ServiceNotifications.WALKAWAY_CHANNEL_ID)
        ?.canBypassDnd() == true

    SettingsDashcamContent(
        navController = navController,
        ownership = prefsSnap.dashcamOwnership,
        dashcamMac = prefsSnap.dashcamMac,
        dashcamDisplayName = prefsSnap.dashcamDisplayName,
        dashcamWarnWhenOff = prefsSnap.dashcamWarnWhenOff,
        dashcamConnected = dashcamConnected,
        // Re-read on the same tick as the freshness above. Without it this
        // screen calls the camera paired while the row that opened it calls
        // it not paired, which is the disagreement the shared vocabulary
        // exists to remove.
        btEnabled = remember(tickNowMs) { bluetoothIsOn(ctx) },
        dashcamBatteryPct = if (dashcamConnected) dashcamBattery.pct else null,
        batteryLowThresholdPct = prefsSnap.batteryLowThresholdPct,
        walkAwayAlarmEnabled = prefsSnap.walkAwayAlarmEnabled,
        walkAwayThreshold = walkAwayThreshold,
        canBypassDnd = canBypassDnd,
        onOwnershipChange = { on ->
            prefs.dashcamOwnership = if (on) DashcamOwnership.YES else DashcamOwnership.NO
            if (!on) {
                prefs.dashcamMac = null
                prefs.dashcamDisplayName = null
                prefs.dashcamWarnWhenOff = false
            }
        },
        onPickDeviceClick = { navController.navigate("dashcam-picker") },
        onWarnWhenOffChange = { prefs.dashcamWarnWhenOff = it },
        onWalkAwayEnabledChange = { prefs.walkAwayAlarmEnabled = it },
        onWalkAwayThresholdChange = { walkAwayThreshold = it },
        onWalkAwayThresholdFinished = { prefs.walkAwayAlarmThresholdSec = walkAwayThreshold },
        onOverrideDndClick = {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, ServiceNotifications.WALKAWAY_CHANNEL_ID)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        },
    )
}

/**
 * Stateless leaf — renders the Dashcam settings screen from
 * already-derived state. No `LocalContext`, no `Prefs`, no
 * `NotificationManager`. Visible to snapshot tests so the visual
 * contract can be locked across the three ownership states.
 */
@Composable
internal fun SettingsDashcamContent(
    navController: NavController,
    ownership: DashcamOwnership,
    dashcamMac: String?,
    dashcamDisplayName: String?,
    dashcamWarnWhenOff: Boolean,
    dashcamConnected: Boolean,
    /** Deliberately no default: a caller that forgets it would render a
     *  camera as paired while the radio is off, which is the exact
     *  disagreement with the home row this parameter exists to prevent. */
    btEnabled: Boolean,
    dashcamBatteryPct: Int?,
    batteryLowThresholdPct: Int = DEFAULT_BATTERY_LOW_THRESHOLD_PCT,
    walkAwayAlarmEnabled: Boolean,
    walkAwayThreshold: Int,
    canBypassDnd: Boolean,
    onOwnershipChange: (Boolean) -> Unit,
    onPickDeviceClick: () -> Unit,
    onWarnWhenOffChange: (Boolean) -> Unit,
    onWalkAwayEnabledChange: (Boolean) -> Unit,
    onWalkAwayThresholdChange: (Int) -> Unit,
    onWalkAwayThresholdFinished: () -> Unit,
    onOverrideDndClick: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.settings_dashcam_title), onBack = { navController.popBackStack() })

            // Ownership toggle (top section before any device card)
            SettingsRowGroup {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_dashcam_have_dashcam),
                    subtitle = if (ownership == DashcamOwnership.YES) {
                        stringResource(R.string.settings_dashcam_have_dashcam_subtitle_on)
                    } else {
                        stringResource(R.string.settings_dashcam_have_dashcam_subtitle_off)
                    },
                    checked = ownership == DashcamOwnership.YES,
                    onCheckedChange = onOwnershipChange,
                )
            }

            if (ownership == DashcamOwnership.YES) {
                Spacer(modifier = Modifier.height(14.dp))
                // Device pairing card matching the JSX 'rich device summary'.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(br.bgElev1)
                        .border(1.dp, br.hairline, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(br.dashcam.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = br.dashcam,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dashcamDisplayName ?: stringResource(R.string.settings_dashcam_not_selected),
                                color = br.fg,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Same vocabulary as the home System row, from the
                            // same mapping: a rider arrives here by tapping
                            // that row, so the state must survive the journey
                            // and must not be carried by colour alone.
                            val link = deviceLinkState(
                                linked = btEnabled && dashcamMac != null,
                                fresh = dashcamConnected,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusDot(
                                    color = when (link) {
                                        DeviceLinkState.NOT_PAIRED -> br.fgDim
                                        DeviceLinkState.LIVE -> br.safe
                                        else -> br.caution
                                    },
                                    hollow = link.hollow,
                                    size = 6.dp,
                                )
                                Text(
                                    text = stringResource(dashcamLinkLabel(link)),
                                    color = br.fgMuted,
                                    fontSize = 12.sp,
                                )
                                if (dashcamConnected && dashcamBatteryPct != null) {
                                    BatteryChip(pct = dashcamBatteryPct, lowThresholdPct = batteryLowThresholdPct)
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, br.hairline2, RoundedCornerShape(8.dp))
                                .clickable(onClick = onPickDeviceClick)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = if (dashcamMac == null) {
                                    stringResource(R.string.settings_dashcam_pick)
                                } else {
                                    stringResource(R.string.settings_dashcam_change)
                                },
                                color = br.fg,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                SettingsSectionLabel(stringResource(R.string.settings_dashcam_behaviour_label))
                SettingsRowGroup {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_dashcam_warn_off),
                        subtitle = stringResource(R.string.settings_dashcam_warn_off_subtitle),
                        checked = dashcamWarnWhenOff,
                        enabled = dashcamMac != null,
                        onCheckedChange = onWarnWhenOffChange,
                    )
                }

                if (dashcamMac != null && dashcamWarnWhenOff) {
                    SettingsSectionLabel(stringResource(R.string.settings_dashcam_walkaway_label))
                    SettingsRowGroup {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_dashcam_walkaway_alert),
                            subtitle = stringResource(R.string.settings_dashcam_walkaway_alert_subtitle),
                            checked = walkAwayAlarmEnabled,
                            onCheckedChange = onWalkAwayEnabledChange,
                            isLast = !walkAwayAlarmEnabled,
                        )
                        if (walkAwayAlarmEnabled) {
                            SettingsRow(
                                icon = Icons.Default.NotificationsActive,
                                iconTint = br.dashcam,
                                title = stringResource(R.string.settings_dashcam_override_dnd),
                                subtitle = if (canBypassDnd) {
                                    stringResource(R.string.settings_dashcam_override_dnd_subtitle_allowed)
                                } else {
                                    stringResource(R.string.settings_dashcam_override_dnd_subtitle_tap)
                                },
                                onClick = onOverrideDndClick,
                                isLast = true,
                            )
                        }
                    }
                    if (walkAwayAlarmEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        NestedCard {
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_dashcam_threshold_title),
                                valueDisplay = stringResource(R.string.settings_dashcam_threshold_value, walkAwayThreshold),
                                helper = stringResource(R.string.settings_dashcam_threshold_helper),
                                value = walkAwayThreshold.toFloat(),
                                valueRange = 15f..120f,
                                steps = 6,
                                onValueChange = { onWalkAwayThresholdChange(it.toInt()) },
                                onValueChangeFinished = onWalkAwayThresholdFinished,
                                paddingHorizontal = 0.dp,
                                paddingBottom = 0.dp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
