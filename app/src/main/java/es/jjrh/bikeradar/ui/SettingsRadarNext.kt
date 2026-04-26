// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import java.util.Locale

@Composable
fun SettingsRadarNext(navController: NavController, prefs: Prefs) {
    NextTheme {
        SettingsRadarNextBody(navController, prefs)
    }
}

@Composable
private fun SettingsRadarNextBody(navController: NavController, prefs: Prefs) {
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
    var adaptive by rememberSaveable { mutableStateOf(prefs.adaptiveAlertsEnabled) }
    var batteryThreshold by rememberSaveable { mutableIntStateOf(prefs.batteryLowThresholdPct) }
    var batteryShowLabels by rememberSaveable { mutableStateOf(prefs.batteryShowLabels) }
    var closePassLogging by rememberSaveable { mutableStateOf(prefs.closePassLoggingEnabled) }
    var closePassEmitMinX by rememberSaveable { mutableFloatStateOf(prefs.closePassEmitMinRangeXM) }
    var closePassRiderFloor by rememberSaveable { mutableIntStateOf(prefs.closePassRiderSpeedFloorKmh) }
    var closePassClosingFloor by rememberSaveable { mutableIntStateOf(prefs.closePassClosingSpeedFloorMs) }

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            NextSettingsHeader("Radar & alerts", onBack = { navController.popBackStack() })

            // Alerts group — sliders sit directly on the screen background
            // matching the JSX which puts them outside any card.
            NextSettingsSectionLabel("Alerts")
            NextSettingsSliderRow(
                title = "Alert volume",
                valueDisplay = "$alertVol%",
                helper = "Beep volume for approach alerts. 0 silences audio; the overlay still flashes.",
                value = alertVol.toFloat(),
                valueRange = 0f..100f,
                onValueChange = { alertVol = it.toInt() },
                onValueChangeFinished = { prefs.alertVolume = alertVol },
            )
            NextSettingsSliderRow(
                title = "Alert distance",
                valueDisplay = "$alertDist m",
                helper = "Start beeping when a vehicle is this close. Vehicles farther away appear on the overlay but stay silent. Scaled by bike speed when adaptive alerts are on.",
                value = alertDist.toFloat(),
                valueRange = 10f..40f,
                onValueChange = { alertDist = it.toInt() },
                onValueChangeFinished = { prefs.alertMaxDistanceM = alertDist },
            )
            NextSettingsSliderRow(
                title = "Visual distance",
                valueDisplay = "$visualDist m",
                helper = "Farthest vehicle drawn on the overlay. Beyond this, approaching traffic is ignored on screen.",
                value = visualDist.toFloat(),
                valueRange = 10f..80f,
                onValueChange = { visualDist = it.toInt() },
                onValueChangeFinished = { prefs.visualMaxDistanceM = visualDist },
            )

            NextSettingsSectionLabel("Adaptive")
            NextSettingsRowGroup {
                NextSettingsToggleRow(
                    leadingIcon = Icons.Default.Speed,
                    leadingTint = br.brand,
                    title = "Adaptive alert colours",
                    subtitle = "Scale amber / red thresholds by your bike speed: more sensitive when stopped, less when cruising.",
                    checked = adaptive,
                    onCheckedChange = { adaptive = it; prefs.adaptiveAlertsEnabled = it },
                )
            }

            NextSettingsSectionLabel("Battery warnings")
            NextNestedCard {
                NextSettingsSliderRow(
                    title = "Low-battery threshold",
                    valueDisplay = "$batteryThreshold%",
                    helper = "Show an amber warning beside the rider when any paired device drops below this level.",
                    value = batteryThreshold.toFloat(),
                    valueRange = 10f..50f,
                    onValueChange = { batteryThreshold = it.toInt() },
                    onValueChangeFinished = { prefs.batteryLowThresholdPct = batteryThreshold },
                    paddingHorizontal = 0.dp,
                    paddingBottom = 0.dp,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            NextSettingsRowGroup {
                NextSettingsToggleRow(
                    title = "Show device labels",
                    subtitle = "Show 'RADAR 12%' or 'DASHCAM 8%' on screen instead of a silent warning tint.",
                    checked = batteryShowLabels,
                    onCheckedChange = { batteryShowLabels = it; prefs.batteryShowLabels = it },
                )
            }

            NextSettingsSectionLabel("Close-pass logging")
            NextSettingsRowGroup {
                NextSettingsToggleRow(
                    leadingIcon = Icons.Default.Home,
                    leadingTint = br.safe,
                    title = "Log to Home Assistant",
                    subtitle = if (haConfigured)
                        "Publish close passes to HA when a vehicle overtakes inside the lateral distance below."
                    else "Requires Home Assistant — set it up below.",
                    checked = closePassLogging,
                    enabled = haConfigured,
                    onCheckedChange = { closePassLogging = it; prefs.closePassLoggingEnabled = it },
                )
            }

            if (closePassLogging) {
                Spacer(modifier = Modifier.height(8.dp))
                NextNestedCard {
                    Column {
                        NextSettingsSliderRow(
                            title = "Lateral clearance threshold",
                            valueDisplay = "${String.format(Locale.US, "%.1f", closePassEmitMinX)} m",
                            helper = "Only publish when the minimum lateral clearance drops below this distance.",
                            value = closePassEmitMinX,
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            onValueChange = { closePassEmitMinX = it },
                            onValueChangeFinished = { prefs.closePassEmitMinRangeXM = closePassEmitMinX },
                            paddingHorizontal = 0.dp,
                            paddingBottom = 14.dp,
                        )
                        NextSettingsSliderRow(
                            title = "Minimum rider speed",
                            valueDisplay = "$closePassRiderFloor km/h",
                            helper = "Detector ignores stationary-rider situations (red lights, pushing the bike).",
                            value = closePassRiderFloor.toFloat(),
                            valueRange = 5f..30f,
                            steps = 4,
                            onValueChange = { closePassRiderFloor = it.toInt() },
                            onValueChangeFinished = { prefs.closePassRiderSpeedFloorKmh = closePassRiderFloor },
                            paddingHorizontal = 0.dp,
                            paddingBottom = 14.dp,
                        )
                        NextSettingsSliderRow(
                            title = "Minimum closing speed",
                            valueDisplay = "$closePassClosingFloor m/s",
                            helper = "Roughly ${(closePassClosingFloor * 3.6).toInt()} km/h of relative approach speed.",
                            value = closePassClosingFloor.toFloat(),
                            valueRange = 3f..15f,
                            steps = 11,
                            onValueChange = { closePassClosingFloor = it.toInt() },
                            onValueChangeFinished = { prefs.closePassClosingSpeedFloorMs = closePassClosingFloor },
                            paddingHorizontal = 0.dp,
                            paddingBottom = 0.dp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
