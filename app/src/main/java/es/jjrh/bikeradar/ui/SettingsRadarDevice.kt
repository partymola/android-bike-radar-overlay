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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarSelection
import es.jjrh.bikeradar.data.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val allBonded = remember(prefsSnap.radarMac) { RadarSelection.bondedDevices(ctx) }
    val bonded = remember(allBonded) { allBonded.filter { RadarSelection.isRadarName(it.name) } }
    // Escape hatch: every bonded device the radar heuristic does NOT
    // recognise, offered behind "My radar isn't listed".
    val others = remember(allBonded) { allBonded.filterNot { RadarSelection.isRadarName(it.name) } }

    // Radar battery is matched by name across the bus (same heuristic the home
    // Quick Status card uses); a read within 30s == connected.
    val radarBattery = batteryEntries.values.firstOrNull { entry ->
        RadarSelection.isRadarName(entry.name)
    }
    val connected = radarBattery != null &&
        System.currentTimeMillis() - radarBattery.readAtMs < 30_000L

    val chosen = prefsSnap.radarMac
    // The chosen unit may live in EITHER list (a pinned odd-name radar is
    // in `others`), so resolve against the full bonded set.
    val chosenBonded = allBonded.firstOrNull { it.mac.equals(chosen, ignoreCase = true) }
    val activeName = when {
        chosenBonded != null -> chosenBonded.name
        bonded.size == 1 -> bonded.first().name
        else -> null // ambiguous (>1, none chosen) or never paired
    }

    var offsetCm by rememberSaveable { mutableIntStateOf(prefs.radarLateralOffsetCm) }

    SettingsRadarDeviceContent(
        onBack = { navController.popBackStack() },
        bonded = bonded,
        others = others,
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
        offsetCm = offsetCm,
        onOffsetChange = { offsetCm = it },
        onOffsetCommit = { prefs.radarLateralOffsetCm = offsetCm },
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
    others: List<RadarSelection.BondedRadar> = emptyList(),
    onPairDifferent: () -> Unit = {},
    onSelectRadar: (RadarSelection.BondedRadar) -> Unit = {},
    offsetCm: Int = 0,
    onOffsetChange: (Int) -> Unit = {},
    onOffsetCommit: () -> Unit = {},
) {
    val br = LocalBrColors.current
    var othersExpanded by rememberSaveable { mutableStateOf(false) }
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
                // Never paired: the radar is required, so prompt pairing. The
                // escape hatch still renders - a radar the name heuristic
                // doesn't recognise lands exactly here, bonded but unlisted.
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    IntentCard(
                        title = stringResource(R.string.settings_radardev_pair_title),
                        subtitle = stringResource(R.string.settings_radardev_pair_subtitle),
                        filled = true,
                        onClick = onPairDifferent,
                    )
                }
                OthersEscapeHatch(
                    others = others,
                    chosenMac = chosenMac,
                    expanded = othersExpanded,
                    onToggle = { othersExpanded = !othersExpanded },
                    onSelectRadar = onSelectRadar,
                )
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

            OthersEscapeHatch(
                others = others,
                chosenMac = chosenMac,
                expanded = othersExpanded,
                onToggle = { othersExpanded = !othersExpanded },
                onSelectRadar = onSelectRadar,
            )

            SettingsSectionLabel(stringResource(R.string.settings_radardev_section_position))
            val offsetDisplay = when {
                offsetCm > 0 -> stringResource(R.string.settings_radardev_position_right, offsetCm)
                offsetCm < 0 -> stringResource(R.string.settings_radardev_position_left, -offsetCm)
                else -> stringResource(R.string.settings_radardev_position_centred)
            }
            val maxCm = Prefs.RADAR_LATERAL_OFFSET_MAX_CM
            SettingsSliderRow(
                title = stringResource(R.string.settings_radardev_position_title),
                valueDisplay = offsetDisplay,
                helper = stringResource(R.string.settings_radardev_position_helper),
                value = offsetCm.toFloat(),
                valueRange = -maxCm.toFloat()..maxCm.toFloat(),
                // Continuous track (no fixed step grid), snapped in code to the
                // valid set: 0, then +/-MIN..+/-MAX in 1 cm steps. A uniform M3
                // step grid can't express the 0 <-> +/-5 jump-then-fine scale.
                onValueChange = { onOffsetChange(snapOffsetCm(it)) },
                onValueChangeFinished = onOffsetCommit,
            )

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

/** Snap a raw slider position (cm) to the valid mount-offset set: 0 (centred),
 *  then a magnitude from [Prefs.RADAR_LATERAL_OFFSET_MIN_CM] to
 *  [Prefs.RADAR_LATERAL_OFFSET_MAX_CM] in 1 cm steps. The 0 <-> +/-min jump is
 *  deliberate: sub-5 cm offsets aren't worth setting, but past the minimum the
 *  rider wants fine 1 cm control. A uniform slider step grid can't express this
 *  scale, so the track is continuous and snapping happens here. */
internal fun snapOffsetCm(raw: Float): Int {
    val min = Prefs.RADAR_LATERAL_OFFSET_MIN_CM
    val cm = raw.roundToInt()
        .coerceIn(-Prefs.RADAR_LATERAL_OFFSET_MAX_CM, Prefs.RADAR_LATERAL_OFFSET_MAX_CM)
    val mag = abs(cm)
    return when {
        mag * 2 < min -> 0
        mag < min -> if (cm > 0) min else -min
        else -> cm
    }
}

/** "My radar isn't listed" - the pick-any-bonded-device escape hatch for
 *  radars the name heuristic doesn't know. Collapsed by default so
 *  headphones/watches don't clutter the common path; hidden entirely when
 *  every bonded device already matched the heuristic. */
@Composable
private fun OthersEscapeHatch(
    others: List<RadarSelection.BondedRadar>,
    chosenMac: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectRadar: (RadarSelection.BondedRadar) -> Unit,
) {
    if (others.isEmpty()) return
    val br = LocalBrColors.current
    Spacer(modifier = Modifier.height(8.dp))
    SettingsRowGroup {
        SettingsRow(
            icon = Icons.Default.Bluetooth,
            iconTint = br.fgMuted,
            title = stringResource(R.string.settings_radardev_not_listed_title),
            subtitle = stringResource(R.string.settings_radardev_not_listed_subtitle),
            onClick = onToggle,
            chevron = !expanded,
            isLast = !expanded,
        )
        if (expanded) {
            others.forEachIndexed { i, dev ->
                val isChosen = dev.mac.equals(chosenMac, ignoreCase = true)
                SettingsRow(
                    icon = Icons.Default.Bluetooth,
                    iconTint = if (isChosen) br.brand else br.fgMuted,
                    title = dev.name,
                    subtitle = if (isChosen) {
                        stringResource(R.string.settings_radardev_selected)
                    } else {
                        stringResource(R.string.settings_radardev_tap_to_use)
                    },
                    onClick = { onSelectRadar(dev) },
                    chevron = false,
                    isLast = i == others.lastIndex,
                )
            }
        }
    }
}
