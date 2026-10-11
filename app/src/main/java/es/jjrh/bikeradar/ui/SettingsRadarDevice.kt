// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.content.Intent
import android.os.SystemClock
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.EBikeStatusReader
import es.jjrh.bikeradar.MissingPermission
import es.jjrh.bikeradar.Permissions
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarLinkState
import es.jjrh.bikeradar.RadarLinkStatus
import es.jjrh.bikeradar.RadarSelection
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.batteryReadIsFresh
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.radarStreamIsLive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Radar device-link management. The counterpart to the alert-config "Alerts"
 * screen: this one is ONLY about which physical radar the app talks to, not
 * alert tuning, and not the radar tail light (that lives in Light auto-mode).
 *
 * The radar is found by name-match by default; this screen lets a rider with
 * more than one bonded radar pin THIS bike's unit ([Prefs.radarMac]) so the
 * app never streams from the wrong one, pin a radar the name-match misses, and
 * take back a pin made on the wrong device. Pairing itself happens in Android's
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
    // Ticked, not sampled once: the entries flow only emits when a device IS
    // seen, so a screen left open would hold its last verdict indefinitely and
    // keep calling a dead radar connected.
    val tickNowMs = rememberStatusClock()

    // Re-enumerated on the tick, not once per radarMac. Bonding happens in
    // Android's Bluetooth settings, which this screen's only action deep-links
    // to, and coming back changes no pref - so keyed on radarMac alone this
    // was the ONE surface that did not notice the rider doing exactly what it
    // sent them to do. `getBondedDevices` also returns an empty set while the
    // adapter is off, so this is how the screen tracks the radio at all.
    val allBonded = remember(prefsSnap.radarMac, tickNowMs) { RadarSelection.bondedDevices(ctx) }
    val bonded = remember(allBonded) { RadarSelection.choosableRadars(allBonded) }
    val eBikeMac = remember(allBonded) { EBikeStatusReader.findBondedEBikeMac(ctx) }
    val others = remember(allBonded, prefsSnap.dashcamMac, eBikeMac) {
        RadarSelection.unlistedDevices(allBonded, listOf(prefsSnap.dashcamMac, eBikeMac))
    }
    // Without it the bonded list reads empty, so "pair your radar" would send
    // the rider to a Bluetooth list where the radar already is.
    val nearbyMissing = remember(tickNowMs) {
        Permissions.missingForService(ctx) == MissingPermission.NEARBY_DEVICES
    }

    // Radar battery is matched by name across the bus (same heuristic the home
    // Quick Status card uses); a recent read is the proxy for connected.
    // The tick is hoisted past the && below, whose short-circuit would skip the
    // read when no battery entry exists - and an unread tick means no
    // recomposition, so the status would freeze exactly in the aborting-radar
    // case it is for.
    val tickNow = tickNowMs
    val radarBattery = batteryEntries.values.firstOrNull { entry ->
        RadarSelection.isRadarName(entry.name)
    }
    // Frames outrank the battery proxy. A battery reading is evidence the link
    // works; decoded targets are evidence it is DOING its job, and the two come
    // apart in both directions. A legacy-stream radar may expose no battery at
    // all while streaming perfectly, and the home card has always scored itself
    // on frame freshness, so reading this off the battery alone is also what
    // let the two screens disagree about the same radar.
    val radarState by RadarStateBus.state.collectAsState()
    val framesFresh = radarStreamIsLive(radarState, tickNow)
    // Kept as a nullable rather than a boolean so the chip below still has a
    // reading to render, but it deliberately does NOT feed `connected`.
    // A battery reading proves the link works, not that the radar is
    // delivering targets, and the setup sequence now publishes one on every
    // attempt that reaches its battery step - so an aborting radar retrying
    // every 1.5 s would keep a reading permanently fresh and read Connected
    // while sending nothing. That is the state the tri-state was built to
    // stop calling Connected.
    val freshBattery = radarBattery?.takeIf { batteryReadIsFresh(it.readAtMs, tickNow) }
    val connected = framesFresh

    // The static is not a snapshot read, so a service (re)start invalidates
    // nothing by itself - the tick's recomposition is what re-reads it, within
    // 5 s. The remember on the fallback is load-bearing too: a fresh instance
    // per recomposition would make collectAsState resubscribe on every tick.
    // The deriver's age arithmetic is elapsedRealtime on BOTH sides; the tick
    // stays wall clock for the battery freshness above.
    val noServiceFlow = remember { MutableStateFlow(RadarLinkState()) }
    val linkSnap by (BikeRadarService.radarLinkStateForUi ?: noServiceFlow).collectAsState()

    val chosen = prefsSnap.radarMac
    val activeName = RadarSelection.activeRadarName(allBonded, chosen)

    var offsetCm by rememberSaveable { mutableIntStateOf(prefs.radarLateralOffsetCm) }

    // The same classifier the home System row uses, so the two surfaces
    // cannot describe one radar differently. The deriver's age arithmetic is
    // elapsedRealtime on BOTH sides; the tick above stays wall clock for the
    // battery freshness.
    val link = deviceLinkState(
        // Whether there IS a radar, not whether we can say WHICH: with two
        // bonded and none pinned the app still links one, so the card must
        // not read "Not paired" over a live stream. Naming it is the title's
        // job, and the picker below is where the rider settles it.
        linked = RadarSelection.hasLinkableRadar(allBonded, chosen),
        fresh = connected,
        limited = radarState.source == DataSource.V1,
        connecting = RadarLinkStatus.isConnecting(linkSnap, SystemClock.elapsedRealtime()),
    )

    SettingsRadarDeviceContent(
        onBack = { navController.popBackStack() },
        bonded = bonded,
        others = others,
        chosenMac = chosen,
        activeName = activeName,
        link = link,
        batteryPct = freshBattery?.pct,
        batteryLowThresholdPct = prefsSnap.batteryLowThresholdPct,
        onPairDifferent = {
            ctx.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        nearbyMissing = nearbyMissing,
        onGrantPermissions = { navController.navigate("settings/permissions") },
        offerChooser = RadarSelection.shouldOfferChooser(allBonded, chosen),
        onSelectRadar = { radar -> RadarSelection.pin(prefs, radar) },
        offsetCm = offsetCm,
        onOffsetChange = { offsetCm = it },
        onOffsetCommit = { prefs.radarLateralOffsetCm = offsetCm },
        firmwareRev = prefsSnap.radarFirmwareRev,
    )
}

/**
 * Stateless leaf so snapshot tests can lock the four states (connected,
 * connecting, offline, never-paired) plus the ambiguous multi-radar selection
 * list, without a Bluetooth stack or `Prefs`.
 */
@Composable
internal fun SettingsRadarDeviceContent(
    onBack: () -> Unit,
    bonded: List<RadarSelection.BondedRadar>,
    chosenMac: String?,
    activeName: String?,
    /** Classified by the same [deviceLinkState] the home System row uses.
     *  [DeviceLinkState.LIMITED] also drives the range-only note below: a
     *  rider must not mistake a partly-working radar for a fully-working
     *  one, and nothing else on any screen says so. */
    link: DeviceLinkState,
    batteryPct: Int?,
    batteryLowThresholdPct: Int = DEFAULT_BATTERY_LOW_THRESHOLD_PCT,
    others: List<RadarSelection.BondedRadar> = emptyList(),
    /** [RadarSelection.shouldOfferChooser]: two named radars, or a pin on
     *  another device that the one named radar must be able to replace. */
    offerChooser: Boolean,
    onPairDifferent: () -> Unit = {},
    /** Nearby devices is denied, so an empty bonded list says nothing about
     *  pairing; the card asks for the permission instead. */
    nearbyMissing: Boolean = false,
    onGrantPermissions: () -> Unit = {},
    onSelectRadar: (RadarSelection.BondedRadar) -> Unit = {},
    offsetCm: Int = 0,
    onOffsetChange: (Int) -> Unit = {},
    onOffsetCommit: () -> Unit = {},
    firmwareRev: String? = null,
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

            if (link == DeviceLinkState.NOT_PAIRED && nearbyMissing) {
                // The same words as the home card that sent the rider here.
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    IntentCard(
                        title = stringResource(R.string.main_status_permissions_title),
                        subtitle = stringResource(R.string.main_status_permissions_nearby_sub),
                        filled = true,
                        onClick = onGrantPermissions,
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))
                return@Column
            }

            if (link == DeviceLinkState.NOT_PAIRED) {
                // No radar to link: the radar is required, so prompt pairing.
                // The escape hatch still renders - a radar the name heuristic
                // doesn't recognise lands exactly here, bonded but unlisted.
                // A pinned one is linkable and gets the device card below
                // (`SettingsRadarWrongPinTest`).
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    IntentCard(
                        title = stringResource(R.string.settings_radardev_pair_title),
                        subtitle = stringResource(R.string.settings_radardev_pair_subtitle),
                        filled = true,
                        onClick = onPairDifferent,
                    )
                }
                RadarNotListedHatch(
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
                                color = when (link) {
                                    DeviceLinkState.NOT_PAIRED -> br.fgDim
                                    DeviceLinkState.LIVE -> br.safe
                                    else -> br.caution
                                },
                                hollow = link.hollow,
                                size = 6.dp,
                            )
                            Text(
                                // Always the state, even when the title reads
                                // "Not selected": with several bonded and none
                                // pinned the app is streaming from one of them,
                                // and telling the rider to pick instead of
                                // saying so hides live cover behind an errand.
                                // The picker section below carries the errand.
                                text = stringResource(radarLinkLabel(link)),
                                color = br.fgMuted,
                                fontSize = 12.sp,
                            )
                            // Shown on LIMITED too. The battery is a fact about
                            // the device, not a claim about cover, and a
                            // range-only radar reports it just as well; hiding
                            // it there made this screen disagree with the home
                            // card about the same radar.
                            if (link.isDelivering && batteryPct != null) {
                                BatteryChip(pct = batteryPct, lowThresholdPct = batteryLowThresholdPct)
                            }
                        }
                        if (firmwareRev != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_radardev_firmware, firmwareRev),
                                color = br.fgDim,
                                fontSize = 11.sp,
                            )
                        }
                        if (link == DeviceLinkState.LIMITED) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.settings_radardev_limited_source),
                                color = br.caution,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            // Let the rider pin which radar is on this bike (RadarSelection
            // honours the pinned MAC).
            if (offerChooser) {
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

            RadarNotListedHatch(
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
                // A range-only radar's decoder never reads the offset, so the
                // slider says so rather than promising an effect
                // (`SettingsRadarDeviceStatusTest`).
                helper = stringResource(
                    if (link == DeviceLinkState.LIMITED) {
                        R.string.settings_radardev_position_no_effect
                    } else {
                        R.string.settings_radardev_position_helper
                    },
                ),
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
 *  there is nothing to list. Shared with the setup pairing step, which
 *  passes its own wording and a border to match the cards around it. */
@Composable
internal fun RadarNotListedHatch(
    others: List<RadarSelection.BondedRadar>,
    chosenMac: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectRadar: (RadarSelection.BondedRadar) -> Unit,
    title: String = stringResource(R.string.settings_radardev_not_listed_title),
    subtitle: String = stringResource(R.string.settings_radardev_not_listed_subtitle),
    bordered: Boolean = false,
    paddingHorizontal: Dp = 16.dp,
    topGap: Dp = 8.dp,
) {
    if (others.isEmpty()) return
    val br = LocalBrColors.current
    if (topGap > 0.dp) Spacer(modifier = Modifier.height(topGap))
    SettingsRowGroup(paddingHorizontal = paddingHorizontal, bordered = bordered) {
        SettingsRow(
            icon = Icons.Default.Bluetooth,
            iconTint = br.fgMuted,
            title = title,
            subtitle = subtitle,
            onClick = onToggle,
            chevronIcon = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            stateDescription = stringResource(if (expanded) R.string.common_expanded else R.string.common_collapsed),
            onClickLabel = stringResource(
                if (expanded) R.string.settings_radardev_hide_devices else R.string.settings_radardev_show_devices,
            ),
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
                        stringResource(R.string.settings_radardev_tap_to_use_as_radar)
                    },
                    onClick = { onSelectRadar(dev) },
                    chevron = false,
                    isLast = i == others.lastIndex,
                )
            }
        }
    }
}
