// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.jjrh.bikeradar.AlertBeeper
import es.jjrh.bikeradar.CuePlayer
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.Prefs

/**
 * Setting the alert volume as a screen of its own: after the demo in
 * onboarding, and from the Alert sounds page. The slider is the same saved
 * alert volume Settings -> Alerts shows, so the ride follows it.
 */
@Composable
internal fun AlertVolumeScreen(
    prefs: Prefs,
    @StringRes mark: Int,
    onDone: () -> Unit,
    @StringRes doneLabel: Int = R.string.common_continue,
    create: (Context) -> AlertBeeper? = { newDemoBeeper(it, prefs) },
) {
    UiTheme {
        VolumeKeysFollowCues(prefs)
        val br = LocalBrColors.current
        val player = rememberDemoCuePlayer(prefs, create)
        Column(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
            AlertVolumeStep(
                prefs = prefs,
                player = player,
                setGain = player::setVolumePct,
                canPlay = !rememberRadarStreaming(),
                mark = mark,
                onContinue = onDone,
                doneLabel = doneLabel,
            )
        }
    }
}

/** The step with its state; the test sound is three beeps, the everyday close-car sound. */
@Composable
internal fun AlertVolumeStep(
    prefs: Prefs,
    player: CuePlayer?,
    setGain: (Int) -> Unit,
    canPlay: Boolean,
    @StringRes mark: Int,
    onContinue: () -> Unit,
    @StringRes doneLabel: Int = R.string.common_continue,
) {
    // Saved on every change, not when a drag finishes: a drag cut short by a
    // rotation or by leaving the screen never reports that it finished.
    var volume by remember { mutableIntStateOf(prefs.alertVolume) }
    AlertVolumeStepContent(
        volume = volume,
        canPlay = canPlay,
        mark = mark,
        onVolumeChange = {
            volume = it
            prefs.alertVolume = it
            setGain(it)
        },
        onPlay = { player?.play(3) },
        onContinue = onContinue,
        doneLabel = doneLabel,
    )
}

/** Stateless leaf so the goldens can render it. */
@Composable
internal fun AlertVolumeStepContent(
    volume: Int,
    canPlay: Boolean,
    @StringRes mark: Int,
    onVolumeChange: (Int) -> Unit,
    onPlay: () -> Unit,
    onContinue: () -> Unit,
    @StringRes doneLabel: Int = R.string.common_continue,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                tint = br.brand,
                mark = stringResource(mark),
                title = stringResource(R.string.alert_volume_title),
                sub = stringResource(R.string.alert_volume_sub),
            )
            AlertVolumeSliderRow(volume = volume, onChange = onVolumeChange)
            if (!canPlay) {
                Text(
                    text = stringResource(R.string.alert_volume_radar_streaming),
                    color = br.fgDim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            AlarmVolumeNote()
        }
        FooterCtaDual(
            primary = stringResource(R.string.alert_volume_play),
            secondary = stringResource(doneLabel),
            primaryEnabled = canPlay,
            onPrimary = onPlay,
            onSecondary = onContinue,
        )
    }
}

/** The one Alert volume slider, on this step and on Settings -> Alerts. */
@Composable
internal fun AlertVolumeSliderRow(volume: Int, onChange: (Int) -> Unit, onFinished: () -> Unit = {}) {
    SettingsSliderRow(
        title = stringResource(R.string.settings_radar_alert_volume_title),
        valueDisplay = stringResource(R.string.settings_radar_percent_value, volume),
        helper = stringResource(R.string.settings_radar_alert_volume_helper),
        value = volume.toFloat(),
        valueRange = 0f..100f,
        onValueChange = { onChange(it.toInt()) },
        onValueChangeFinished = onFinished,
    )
}
