// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.Prefs

// ── Step 4: radar position ───────────────────────────────────────────

/**
 * Onboarding's radar-position step: the mount-offset slider from
 * Settings -> Radar, offered up front per the "user-specific config
 * belongs in onboarding" rule. Defaults to centred, so Continue without
 * touching the slider is the skip path (the top-bar Skip also works);
 * the pref is committed on slider release, exactly like the Settings
 * screen, so backing out of onboarding keeps a deliberate setting.
 */
@Composable
internal fun RadarPositionStep(prefs: Prefs, onContinue: () -> Unit) {
    var offsetCm by rememberSaveable { mutableIntStateOf(prefs.radarLateralOffsetCm) }
    RadarPositionStepContent(
        offsetCm = offsetCm,
        onOffsetChange = { offsetCm = it },
        onOffsetCommit = { prefs.radarLateralOffsetCm = offsetCm },
        onContinue = onContinue,
    )
}

/** Stateless leaf so snapshot tests can pin the centred and offset
 *  renders without a [Prefs]. Slider semantics (snap set, labels,
 *  helper) are shared with the Settings screen via [SettingsSliderRow]
 *  and [snapOffsetCm]. */
@Composable
internal fun RadarPositionStepContent(
    offsetCm: Int,
    onOffsetChange: (Int) -> Unit,
    onOffsetCommit: () -> Unit,
    onContinue: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.Default.Sensors,
                tint = br.brand,
                mark = stringResource(R.string.onboarding_step_4_of_5),
                title = stringResource(R.string.onboarding_radarpos_title),
                sub = stringResource(R.string.onboarding_radarpos_sub),
            )
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
                onValueChange = { onOffsetChange(snapOffsetCm(it)) },
                onValueChangeFinished = onOffsetCommit,
            )
        }
        FooterCta(label = stringResource(R.string.common_continue), enabled = true, onClick = onContinue)
    }
}
