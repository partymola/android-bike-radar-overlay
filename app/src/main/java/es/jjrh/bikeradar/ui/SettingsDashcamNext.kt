// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import java.util.Locale

@Composable
fun SettingsDashcamNext(navController: NavController, prefs: Prefs) {
    NextTheme {
        SettingsDashcamNextBody(navController, prefs)
    }
}

@Composable
private fun SettingsDashcamNextBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val batteryEntries by BatteryStateBus.entries.collectAsState()

    val dashcamSlug = prefsSnap.dashcamMac?.let { mac ->
        BikeRadarService.macToSlug[mac]
            ?: BikeRadarService.macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefsSnap.dashcamDisplayName?.let { BikeRadarService.slug(it) }
    }
    val dashcamBattery = dashcamSlug?.let { batteryEntries[it] }
    val dashcamConnected = dashcamBattery != null &&
        System.currentTimeMillis() - dashcamBattery.readAtMs < 30_000L

    var walkAwayThreshold by rememberSaveable { mutableIntStateOf(prefs.walkAwayAlarmThresholdSec) }

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            NextSettingsHeader("Dashcam", onBack = { navController.popBackStack() })

            // Ownership toggle (top section before any device card)
            NextSettingsRowGroup {
                NextSettingsToggleRow(
                    title = "I have a front dashcam",
                    subtitle = if (prefsSnap.dashcamOwnership == DashcamOwnership.YES)
                        "Set up your dashcam below."
                    else "Turn this on if you want to track a Bluetooth dashcam alongside the radar.",
                    checked = prefsSnap.dashcamOwnership == DashcamOwnership.YES,
                    onCheckedChange = { on ->
                        prefs.dashcamOwnership = if (on) DashcamOwnership.YES else DashcamOwnership.NO
                        if (!on) {
                            prefs.dashcamMac = null
                            prefs.dashcamDisplayName = null
                            prefs.dashcamWarnWhenOff = false
                        }
                    },
                )
            }

            if (prefsSnap.dashcamOwnership == DashcamOwnership.YES) {
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
                                text = prefsSnap.dashcamDisplayName ?: "Not selected",
                                color = br.fg,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Dot-only status next to the device name —
                            // green solid for Live, amber pulse for No
                            // signal, grey hollow for Not paired. The
                            // word lives on Main; here it'd duplicate.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val notPaired = prefsSnap.dashcamMac == null
                                StatusDot(
                                    color = when {
                                        notPaired -> br.fgDim
                                        dashcamConnected -> br.safe
                                        else -> br.caution
                                    },
                                    pulse = !notPaired && !dashcamConnected,
                                    hollow = notPaired,
                                    size = 6.dp,
                                )
                                if (dashcamConnected) {
                                    BatteryChip(pct = dashcamBattery.pct)
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, br.hairline2, RoundedCornerShape(8.dp))
                                .clickable { navController.navigate("dashcam-picker") }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = if (prefsSnap.dashcamMac == null) "Pick" else "Change",
                                color = br.fg,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                NextSettingsSectionLabel("Behaviour")
                NextSettingsRowGroup {
                    NextSettingsToggleRow(
                        title = "Warn on overlay when dashcam is off",
                        subtitle = "Show a camera-off icon next to the rider when no Vue advert is seen.",
                        checked = prefsSnap.dashcamWarnWhenOff,
                        enabled = prefsSnap.dashcamMac != null,
                        onCheckedChange = { prefs.dashcamWarnWhenOff = it },
                    )
                }

                if (prefsSnap.dashcamMac != null && prefsSnap.dashcamWarnWhenOff) {
                    NextSettingsSectionLabel("Walk-away alarm")
                    NextSettingsRowGroup {
                        NextSettingsToggleRow(
                            title = "Alert if dashcam remains on",
                            subtitle = "Phone vibrates + beeps when you walk out of range with the dashcam still powered up (camera, light, or both).",
                            checked = prefsSnap.walkAwayAlarmEnabled,
                            onCheckedChange = { prefs.walkAwayAlarmEnabled = it },
                        )
                    }
                    if (prefsSnap.walkAwayAlarmEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        NextNestedCard {
                            NextSettingsSliderRow(
                                title = "Out-of-range threshold",
                                valueDisplay = "${walkAwayThreshold} s",
                                helper = "How long the dashcam must be unreachable before the alarm fires.",
                                value = walkAwayThreshold.toFloat(),
                                valueRange = 15f..120f,
                                steps = 6,
                                onValueChange = { walkAwayThreshold = it.toInt() },
                                onValueChangeFinished = { prefs.walkAwayAlarmThresholdSec = walkAwayThreshold },
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
