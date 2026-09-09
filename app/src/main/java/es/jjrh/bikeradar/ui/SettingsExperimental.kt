// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.Prefs
import kotlin.math.roundToInt

@Composable
fun SettingsExperimental(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsExperimentalBody(navController, prefs)
    }
}

/** Internal rather than private so `SettingsExperimentalWindowTest` can compose
 *  the shipped screen: everything between the slider and [Prefs] lives here, and
 *  the snapshot tests render the stateless leaf below with literal values. */
@Composable
internal fun SettingsExperimentalBody(navController: NavController, prefs: Prefs) {
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    // The drag lives here and only the release commits, matching the other
    // slider screens. Writing per frame would rewrite the prefs file on every
    // rung and rebuild the snapshot in the running service too, and it would
    // drive the thumb through a store-and-flow round trip rather than the
    // finger.
    var dropWindowSec by rememberSaveable { mutableIntStateOf(prefs.radarDropTrackWindowSec) }
    SettingsExperimentalContent(
        navController = navController,
        precogEnabled = prefsSnap.precogEnabled,
        onPrecogChange = { prefs.precogEnabled = it },
        radarDropTrackFallbackEnabled = prefsSnap.radarDropTrackFallbackEnabled,
        onRadarDropTrackFallbackChange = { prefs.radarDropTrackFallbackEnabled = it },
        radarDropTrackWindowSec = dropWindowSec,
        onRadarDropTrackWindowChange = { dropWindowSec = it },
        onRadarDropTrackWindowFinished = { prefs.radarDropTrackWindowSec = dropWindowSec },
    )
}

/**
 * Stateless leaf — visible to snapshot tests so the visual contract can
 * be locked without Prefs scaffolding.
 */
@Composable
internal fun SettingsExperimentalContent(
    navController: NavController,
    precogEnabled: Boolean,
    onPrecogChange: (Boolean) -> Unit,
    radarDropTrackFallbackEnabled: Boolean,
    onRadarDropTrackFallbackChange: (Boolean) -> Unit,
    radarDropTrackWindowSec: Int,
    onRadarDropTrackWindowChange: (Int) -> Unit,
    onRadarDropTrackWindowFinished: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.settings_exp_title), onBack = { navController.popBackStack() })

            Text(
                text = stringResource(R.string.settings_exp_intro),
                color = br.fgDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))
            SettingsRowGroup {
                SettingsToggleRow(
                    leadingIcon = Icons.Default.FlashOn,
                    leadingTint = br.brand,
                    title = stringResource(R.string.settings_exp_precog_title),
                    subtitle = stringResource(R.string.settings_exp_precog_subtitle),
                    checked = precogEnabled,
                    onCheckedChange = onPrecogChange,
                    isLast = false,
                )
                SettingsToggleRow(
                    leadingIcon = Icons.AutoMirrored.Filled.VolumeUp,
                    leadingTint = br.brand,
                    title = stringResource(R.string.settings_exp_drop_fallback_title),
                    subtitle = stringResource(R.string.settings_exp_drop_fallback_subtitle),
                    checked = radarDropTrackFallbackEnabled,
                    onCheckedChange = onRadarDropTrackFallbackChange,
                )
            }

            // Shown only while the toggle above it is on, matching the two
            // screens that already nest a slider under the switch it
            // configures. The setting reaches that one path and nothing else,
            // so with the toggle off this is a live control writing a value
            // nothing reads. The cost it warns about is stated on the toggle's
            // own subtitle, so hiding it costs the rider nothing they need
            // before opting in.
            if (radarDropTrackFallbackEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                NestedCard {
                    val rung = RadarDropWindowLadder.indexOf(radarDropTrackWindowSec)
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_exp_drop_window_title),
                        // The rung the thumb is on, not the stored seconds. A
                        // value off the ladder would otherwise label itself one
                        // window while the thumb sat on another.
                        valueDisplay = dropWindowLabel(RadarDropWindowLadder.secondsAt(rung)),
                        helper = stringResource(R.string.settings_exp_drop_window_helper),
                        value = rung.toFloat(),
                        valueRange = 0f..(RadarDropWindowLadder.RUNGS_SEC.size - 1).toFloat(),
                        steps = RadarDropWindowLadder.sliderSteps,
                        onValueChange = {
                            onRadarDropTrackWindowChange(RadarDropWindowLadder.secondsAt(it.roundToInt()))
                        },
                        onValueChangeFinished = onRadarDropTrackWindowFinished,
                        paddingHorizontal = 0.dp,
                        paddingBottom = 0.dp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** Seconds below a minute, whole minutes above it. Reuses the unit formats the
 *  radar screen already ships rather than adding a fourth byte-identical copy:
 *  a change to how this app writes units should be one edit per locale, not
 *  four. Every rung above the first is a whole number of minutes, which
 *  `theRungsAreTheLadderThatShipped` pins, so the division is exact. */
@Composable
private fun dropWindowLabel(sec: Int): String = if (sec < 60) {
    stringResource(R.string.settings_radar_seconds_value, sec)
} else {
    stringResource(R.string.settings_radar_minutes_value, sec / 60)
}
