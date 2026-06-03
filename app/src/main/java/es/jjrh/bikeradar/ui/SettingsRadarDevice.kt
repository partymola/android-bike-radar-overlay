// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarSelection
import es.jjrh.bikeradar.data.Prefs

/**
 * Radar device-link management. The counterpart to the alert-config "Alerts"
 * screen: this one is ONLY about which physical radar the app talks to, not
 * alert tuning, and not the radar tail light (that lives in Light auto-mode).
 *
 * The radar is found by name-match by default; this screen lets a rider with
 * more than one bonded radar pin THIS bike's unit ([Prefs.radarMac]) so the
 * app never streams from the wrong one. Pairing itself happens in Android's
 * Bluetooth settings (the platform blocks programmatic LESC bonding), so the
 * only action here is a deep-link to it. See [RadarSelection].
 */
@Composable
fun SettingsRadarDevice(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsRadarDeviceBody(navController, prefs)
    }
}

@Composable
private fun SettingsRadarDeviceBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val batteryEntries by BatteryStateBus.entries.collectAsState()
    val bonded = remember(prefsSnap.radarMac) { RadarSelection.bondedRadars(ctx) }

    // Radar battery is matched by name across the bus (same heuristic the home
    // Quick Status card uses); a read within 30s == connected.
    val radarBattery = batteryEntries.values.firstOrNull { entry ->
        RadarSelection.isRadarName(entry.name)
    }
    val connected = radarBattery != null &&
        System.currentTimeMillis() - radarBattery.readAtMs < 30_000L

    val chosen = prefsSnap.radarMac
    val chosenBonded = bonded.firstOrNull { it.mac.equals(chosen, ignoreCase = true) }
    val activeName = when {
        chosenBonded != null -> chosenBonded.name
        bonded.size == 1 -> bonded.first().name
        else -> null // ambiguous (>1, none chosen) or never paired
    }

    SettingsRadarDeviceContent(
        onBack = { navController.popBackStack() },
        bonded = bonded,
        chosenMac = chosen,
        activeName = activeName,
        connected = connected,
        batteryPct = if (connected) radarBattery.pct else null,
        onPairDifferent = {
            ctx.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onSelectRadar = { radar ->
            prefs.radarMac = radar.mac
            prefs.radarDisplayName = radar.name
        },
    )
}

/**
 * Stateless leaf so snapshot tests can lock the three states (connected,
 * offline, never-paired) plus the ambiguous multi-radar selection list,
 * without a Bluetooth stack or `Prefs`.
 */
@Composable
internal fun SettingsRadarDeviceContent(
    onBack: () -> Unit,
    bonded: List<RadarSelection.BondedRadar>,
    chosenMac: String?,
    activeName: String?,
    connected: Boolean,
    batteryPct: Int?,
    onPairDifferent: () -> Unit = {},
    onSelectRadar: (RadarSelection.BondedRadar) -> Unit = {},
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader(stringResource(R.string.settings_radardev_header), onBack = onBack)

            Text(
                text = stringResource(R.string.settings_radardev_intro),
                color = br.fgDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (bonded.isEmpty()) {
                // Never paired: the radar is required, so prompt pairing.
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    IntentCard(
                        title = stringResource(R.string.settings_radardev_pair_title),
                        subtitle = stringResource(R.string.settings_radardev_pair_subtitle),
                        filled = true,
                        onClick = onPairDifferent,
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))
                return@Column
            }

            // Device card (mirrors the Dashcam screen's rich device summary).
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
                            .background(br.brand.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = br.brand,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeName ?: stringResource(R.string.settings_radardev_not_selected),
                            color = br.fg,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusDot(
                                color = when {
                                    activeName == null -> br.fgDim
                                    connected -> br.safe
                                    else -> br.caution
                                },
                                hollow = activeName == null,
                                size = 6.dp,
                            )
                            Text(
                                text = when {
                                    activeName == null -> stringResource(R.string.settings_radardev_pick_radar)
                                    connected -> stringResource(R.string.settings_radardev_connected)
                                    else -> stringResource(R.string.settings_radardev_not_in_range)
                                },
                                color = br.fgMuted,
                                fontSize = 12.sp,
                            )
                            if (connected && batteryPct != null) {
                                BatteryChip(pct = batteryPct)
                            }
                        }
                    }
                }
            }

            // Ambiguity: more than one bonded radar -> let the rider pin which
            // one is on this bike (RadarSelection honours the pinned MAC).
            if (bonded.size > 1) {
                SettingsSectionLabel(stringResource(R.string.settings_radardev_choose_radar))
                SettingsRowGroup {
                    bonded.forEachIndexed { i, radar ->
                        val isChosen = radar.mac.equals(chosenMac, ignoreCase = true)
                        SettingsRow(
                            icon = Icons.Default.Sensors,
                            iconTint = if (isChosen) br.brand else br.fgMuted,
                            title = radar.name,
                            subtitle = if (isChosen) {
                                stringResource(R.string.settings_radardev_selected)
                            } else {
                                stringResource(R.string.settings_radardev_tap_to_use)
                            },
                            onClick = { onSelectRadar(radar) },
                            chevron = false,
                            isLast = i == bonded.lastIndex,
                        )
                    }
                }
            }

            SettingsSectionLabel(stringResource(R.string.settings_radardev_section_actions))
            SettingsRowGroup {
                SettingsActionRow(
                    leadingIcon = Icons.Default.Bluetooth,
                    leadingTint = br.brand,
                    title = stringResource(R.string.settings_radardev_pair_different_title),
                    actionLabel = stringResource(R.string.settings_radardev_open),
                    onAction = onPairDifferent,
                    subtitle = stringResource(R.string.settings_radardev_pair_different_subtitle),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
