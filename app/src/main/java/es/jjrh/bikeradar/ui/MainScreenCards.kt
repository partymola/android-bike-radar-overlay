// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.jjrh.bikeradar.AttentionCopy
import es.jjrh.bikeradar.AttentionItem
import es.jjrh.bikeradar.AttentionKind
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.ClosePassStateBus
import es.jjrh.bikeradar.HaStatus
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.isHollow
import es.jjrh.bikeradar.isMuted

// ── Hero status card ─────────────────────────────────────────────────

@Composable
internal fun HeroStatusCard(status: MainStatus, cta: StatusCta?) {
    val br = LocalBrColors.current
    val (dotColor, pulse) = dotForStatus(status.tone, status.icon, br)
    BrCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    StatusDot(color = dotColor, pulse = pulse, size = 10.dp)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.headline,
                        color = br.fg,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    status.subtitle?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = it,
                            color = br.fgMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            if (cta != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(br.bgElev2)
                        .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = cta.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cta.label,
                        color = br.fg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun dotForStatus(
    tone: MainStatusTone,
    icon: MainStatusIcon,
    br: BrColors,
): Pair<Color, Boolean> {
    // Map MainStatus to mockup's dot+pulse pairs.
    //   Service stopped     -> fgDim, no pulse  (matches `serviceOff`)
    //   Waiting for radar   -> fgDim, pulse     (matches `searching`)
    //   First-run setup     -> safe, no pulse   (matches `active` style - green welcome)
    //   Paused              -> fgDim, no pulse
    //   Not paired (BT on)  -> caution, no pulse
    //   Not paired (BT off) -> danger, no pulse  (mockup's `noBluetooth`)
    //   Live + dashcam off  -> caution, pulse  (matches `dashcamMissing`)
    //   Live + HA down      -> safe, no pulse  (still live; HA is a side-channel)
    //   Live + good         -> safe, no pulse
    return when (icon) {
        // PlayCircle is shared between First-run (Good) and Service-
        // stopped (Neutral). Mockup: first-run is the green "active"
        // welcome dot, service-stopped is the muted `serviceOff` dot.
        MainStatusIcon.PlayCircle -> when (tone) {
            MainStatusTone.Good -> br.safe to false
            else -> br.fgDim to false
        }
        MainStatusIcon.PauseCircle -> br.fgDim to false
        // BT-off (Warn tone) and Radar-not-paired (Error tone) share this
        // icon. The dot uses the tone so the two states are visually
        // distinct: caution amber for "fixable in two taps", danger red
        // for "needs the system pair flow".
        MainStatusIcon.BluetoothDisabled -> when (tone) {
            MainStatusTone.Warn -> br.caution to false
            else -> br.danger to false
        }
        MainStatusIcon.Sensors -> br.fgDim to true
        MainStatusIcon.Warning -> br.caution to true
        MainStatusIcon.CheckCircle -> when (tone) {
            MainStatusTone.Good -> br.safe to false
            else -> br.fg to false
        }
    }
}

// ── System card ──────────────────────────────────────────────────────

@Composable
internal fun BluetoothOffBanner(onTap: () -> Unit) {
    val br = LocalBrColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LocalBrShapes.current.r3))
            .background(br.danger.copy(alpha = 0.10f))
            .border(1.dp, br.danger.copy(alpha = 0.30f), RoundedCornerShape(LocalBrShapes.current.r3))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(color = br.danger, size = 8.dp)
            Text(
                text = stringResource(R.string.main_bt_banner_title),
                color = br.fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.main_bt_banner_action),
                color = br.danger,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── Needs-attention card ─────────────────────────────────────────────

/**
 * Home-screen needs-attention card (bucket 3). Shows the same end-of-trip
 * items as the post-ride notification - the rider's recovery path when the
 * notification is dismissed by mistake. Rendered only when [items] is
 * non-empty; the caller (the screen body) loads the persisted feed and
 * live-clears resolved items; the copy is resolved here so the card and the
 * notification share [AttentionCopy].
 *
 * Each row carries a dismiss X - the only exit for the historical kinds
 * (restart, audio failures), which no live reading ever clears. Dismiss
 * mutes the persisted item; a condition that still holds re-derives at the
 * next ride end.
 *
 * A calm amber (caution) accent, never a red alarm: these are things to do
 * tonight, not a live threat.
 */
@Composable
internal fun AttentionCard(items: List<AttentionItem>, onDismiss: (AttentionKind) -> Unit) {
    val br = LocalBrColors.current
    BrCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = br.caution,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.attention_title),
                    color = br.fg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            items.forEach { item ->
                val line = when (val copy = AttentionCopy.lineFor(item)) {
                    is AttentionCopy.Line.Simple ->
                        if (copy.arg != null) stringResource(copy.res, copy.arg) else stringResource(copy.res)
                    is AttentionCopy.Line.Plural -> pluralStringResource(copy.res, copy.count, copy.count)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "•", color = br.caution, fontSize = 13.sp)
                    Text(
                        text = line,
                        color = br.fgMuted,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.weight(1f),
                    )
                    // Compact target on purpose: a rare, parked-phone tap on a
                    // calm card - full 48 dp boxes would double the row height.
                    IconButton(
                        onClick = { onDismiss(item.kind) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.attention_dismiss_cd, line),
                            tint = br.fgMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SystemCard(
    radarFresh: Boolean,
    hasBond: Boolean,
    btEnabled: Boolean,
    dashcamOwned: Boolean,
    dashcamFresh: Boolean,
    dashcamPaired: Boolean,
    dashcamDisplayName: String?,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    haStatus: HaStatus,
    ebikeEnabled: Boolean = false,
    ebikeReceiving: Boolean = false,
    ebikeBatterySoc: Int? = null,
    /** The rider's low-battery threshold, so the chips band where the cue fires. */
    batteryLowThresholdPct: Int = DEFAULT_BATTERY_LOW_THRESHOLD_PCT,
) {
    val br = LocalBrColors.current

    // Three-state device vocabulary (from the UX converger):
    //   Not paired      - grey hollow ring
    //   No signal       - amber
    //   Live            - green, solid
    // Plus: BT off shown ONCE as a card-level banner, never per-row.
    //
    // Battery chip hides outside Live to avoid surfacing stale numbers.
    val radarLink = deviceLinkState(linked = btEnabled && hasBond, fresh = radarFresh)
    val radarRow = SystemRow(
        icon = Icons.Default.Sensors,
        label = stringResource(R.string.main_system_rear_radar),
        value = when (radarLink) {
            DeviceLinkState.NOT_PAIRED -> stringResource(R.string.main_system_value_not_paired)
            DeviceLinkState.LIVE -> stringResource(R.string.main_system_value_live)
            DeviceLinkState.NO_SIGNAL -> stringResource(R.string.main_system_value_no_signal)
        },
        muted = radarLink.muted,
        battery = if (radarFresh) radarBattery?.pct else null,
        dot = when (radarLink) {
            DeviceLinkState.NOT_PAIRED -> br.fgDim
            DeviceLinkState.LIVE -> br.safe
            DeviceLinkState.NO_SIGNAL -> br.caution
        },
        hollow = radarLink.hollow,
    )

    val dashcamLink = deviceLinkState(linked = dashcamOwned && dashcamPaired, fresh = dashcamFresh)
    val dashcamRow = SystemRow(
        icon = Icons.Default.Videocam,
        label = stringResource(R.string.main_system_front_dashcam),
        value = when (dashcamLink) {
            DeviceLinkState.NOT_PAIRED -> stringResource(R.string.main_system_value_not_paired)
            DeviceLinkState.LIVE -> dashcamDisplayName ?: stringResource(R.string.main_system_value_live)
            DeviceLinkState.NO_SIGNAL -> stringResource(R.string.main_system_value_no_signal)
        },
        muted = dashcamLink.muted,
        battery = if (dashcamFresh) dashcamBattery?.pct else null,
        dot = when (dashcamLink) {
            DeviceLinkState.NOT_PAIRED -> br.fgDim
            DeviceLinkState.LIVE -> br.safe
            DeviceLinkState.NO_SIGNAL -> br.caution
        },
        hollow = dashcamLink.hollow,
    )

    // eBike live-data status (read from the proprietary channel Flow uses).
    // Battery % + dot, no data dump - shown only when the feature is on.
    val ebikeRow = SystemRow(
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        label = stringResource(R.string.main_system_ebike),
        value =
        if (ebikeReceiving) {
            stringResource(R.string.main_system_value_live)
        } else {
            stringResource(R.string.main_system_value_waiting_flow)
        },
        muted = !ebikeReceiving,
        battery = ebikeBatteryChipSoc(ebikeReceiving, ebikeBatterySoc),
        dot = if (ebikeReceiving) br.safe else br.caution,
    )

    // Four states, same vocabulary as Settings: never claim a working
    // connection from stored credentials alone. Grey solid (not amber) for
    // CONFIGURED - nothing has been observed, but nothing has failed either.
    val haRow = SystemRow(
        icon = Icons.Default.Home,
        label = stringResource(R.string.main_system_home_assistant),
        value = when (haStatus) {
            HaStatus.NOT_CONFIGURED -> stringResource(R.string.main_system_value_not_configured)
            HaStatus.CONFIGURED -> stringResource(R.string.main_system_value_configured)
            HaStatus.READY -> stringResource(R.string.main_system_value_mqtt_ready)
            HaStatus.UNREACHABLE -> stringResource(R.string.main_system_value_unreachable)
        },
        muted = haStatus.isMuted,
        battery = null,
        dot = when (haStatus) {
            HaStatus.NOT_CONFIGURED -> br.fgDim
            HaStatus.CONFIGURED -> br.fgDim
            HaStatus.READY -> br.safe
            HaStatus.UNREACHABLE -> br.caution
        },
        hollow = haStatus.isHollow,
    )

    BrCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SectionLabel(stringResource(R.string.main_section_system))
            Spacer(modifier = Modifier.height(10.dp))
            SystemRowRender(radarRow, isFirst = true, batteryLowThresholdPct = batteryLowThresholdPct)
            SystemRowRender(dashcamRow, isFirst = false, batteryLowThresholdPct = batteryLowThresholdPct)
            if (ebikeEnabled) SystemRowRender(ebikeRow, isFirst = false, batteryLowThresholdPct = batteryLowThresholdPct)
            SystemRowRender(haRow, isFirst = false, batteryLowThresholdPct = batteryLowThresholdPct)
        }
    }
}

private data class SystemRow(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val muted: Boolean,
    val battery: Int?,
    val dot: Color,
    val hollow: Boolean = false,
)

@Composable
private fun SystemRowRender(
    row: SystemRow,
    isFirst: Boolean,
    batteryLowThresholdPct: Int = DEFAULT_BATTERY_LOW_THRESHOLD_PCT,
) {
    val br = LocalBrColors.current
    if (!isFirst) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(br.hairline),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = row.icon,
            contentDescription = null,
            tint = if (row.muted) br.fgDim else br.fgMuted,
            modifier = Modifier.size(17.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                color = if (row.muted) br.fgMuted else br.fg,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.value,
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
                if (row.battery != null) {
                    BatteryChip(pct = row.battery, lowThresholdPct = batteryLowThresholdPct)
                }
            }
        }
        StatusDot(color = row.dot, hollow = row.hollow, size = 7.dp)
    }
}

// ── Close-pass stats card ─────────────────────────────────────────────

@Composable
internal fun ClosePassStatsCard(
    loggingEnabled: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit = {},
) {
    val br = LocalBrColors.current
    val count by ClosePassStateBus.sessionCount.collectAsState()
    // The card links to the ride-history screen, but only once there's
    // plausibly history to see: with counting off and a zero session count a
    // first-run rider would land on an empty screen, so the link + tap target
    // stay inert until counting is on or this ride has logged a pass.
    val historyAvailable = loggingEnabled || count > 0
    val cardModifier = Modifier
        .fillMaxWidth()
        .let { if (historyAvailable) it.clickable(onClick = onClick) else it }
    BrCard(modifier = cardModifier) {
        Column(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    SectionLabel(stringResource(R.string.main_section_close_passes))
                }
                if (historyAvailable) {
                    Text(
                        text = stringResource(R.string.main_close_passes_history_link),
                        color = br.fgDim,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val countDesc = pluralStringResource(R.plurals.main_close_passes_count_desc, count, count)
            Text(
                text = count.toString(),
                color = if (count > 0) br.fg else br.fgDim,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Light,
                fontSize = 38.sp,
                letterSpacing = (-1).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    contentDescription = countDesc
                },
            )
            if (loggingEnabled || count > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.main_close_passes_caption),
                    color = br.fgDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            // Hidden in compact mode (landscape) so the card doesn't
            // push the rest of the left column past the viewport on
            // first-run installs that haven't enabled HA logging.
            if (!loggingEnabled && count == 0 && !compact) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.main_close_passes_empty_hint),
                    color = br.fgDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

// ── Dashcam ownership prompt (kept for first-run flows) ─────────────

@Composable
internal fun DashcamPromptCard(onYes: () -> Unit, onNo: () -> Unit) {
    val br = LocalBrColors.current
    BrCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.main_dashcam_prompt_title),
                color = br.fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.main_dashcam_prompt_body),
                color = br.fgMuted,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(br.dashcam)
                        .combinedClickable(onClick = onYes),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.main_dashcam_prompt_yes),
                        color = br.bg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = onNo),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.main_dashcam_prompt_no),
                        color = br.fg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
