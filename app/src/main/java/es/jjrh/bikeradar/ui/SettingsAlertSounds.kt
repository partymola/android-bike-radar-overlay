// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.CuePlayer
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.Prefs

/**
 * Every alert cue a ride plays, in the order the glossary lists them. Each row
 * names its sound directly rather than through [es.jjrh.bikeradar.playCue],
 * since the radar status cues have no [es.jjrh.bikeradar.AlertCue];
 * `SettingsAlertSoundsTest.eachRowPlaysItsOwnSound` pins the table.
 */
internal enum class AlertSound(
    @StringRes val title: Int,
    @StringRes val meaning: Int,
    val aboutTheRadar: Boolean,
    val play: (CuePlayer) -> Unit,
) {
    ONE_BEEP(R.string.alert_sound_one_title, R.string.alert_sound_one_meaning, false, { it.play(1) }),
    TWO_BEEPS(R.string.alert_sound_two_title, R.string.alert_sound_two_meaning, false, { it.play(2) }),
    THREE_BEEPS(R.string.alert_sound_three_title, R.string.alert_sound_three_meaning, false, { it.play(3) }),
    ALL_CLEAR(R.string.alert_sound_clear_title, R.string.alert_sound_clear_meaning, false, { it.playClear() }),
    URGENT(R.string.alert_sound_urgent_title, R.string.alert_sound_urgent_meaning, false, { it.playUrgent() }),
    RADAR_LOST(R.string.alert_sound_lost_title, R.string.alert_sound_lost_meaning, true, { it.playRadarDropped() }),
    RADAR_BACK(R.string.alert_sound_back_title, R.string.alert_sound_back_meaning, true, { it.playRadarReconnected() }),
}

@Composable
fun SettingsAlertSounds(navController: NavController, prefs: Prefs) {
    UiTheme {
        VolumeKeysFollowCues(prefs)
        SettingsAlertSoundsBody(
            player = rememberDemoCuePlayer(prefs),
            riding = rememberRadarStreaming(),
            onBack = { navController.popBackStack() },
            onWatchDemo = { navController.navigate("settings/sound-demo") },
            onSetVolume = { navController.navigate("settings/alert-volume") },
        )
    }
}

/** The glossary with its player. Nothing plays while the radar is streaming. */
@Composable
internal fun SettingsAlertSoundsBody(
    player: CuePlayer?,
    riding: Boolean,
    onBack: () -> Unit,
    onWatchDemo: () -> Unit,
    onSetVolume: () -> Unit,
) {
    SettingsAlertSoundsContent(
        canPlay = !riding,
        onBack = onBack,
        onPlay = { sound -> player?.let(sound.play) },
        onWatchDemo = onWatchDemo,
        onSetVolume = onSetVolume,
    )
}

/**
 * Stateless leaf so the goldens can render it. The volume row stays enabled
 * during a ride: its slider works then, and only its test sound is off.
 */
@Composable
internal fun SettingsAlertSoundsContent(
    canPlay: Boolean,
    onBack: () -> Unit,
    onPlay: (AlertSound) -> Unit,
    onWatchDemo: () -> Unit,
    onSetVolume: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader(stringResource(R.string.alert_sounds_title), onBack = onBack)
            if (!canPlay) {
                Text(
                    text = stringResource(R.string.alert_sounds_while_riding),
                    color = br.fgDim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            AlarmVolumeNote()
            SettingsSectionLabel(stringResource(R.string.alert_sounds_section_traffic))
            SoundRows(AlertSound.entries.filterNot { it.aboutTheRadar }, canPlay, onPlay)
            SettingsSectionLabel(stringResource(R.string.alert_sounds_section_radar))
            SoundRows(AlertSound.entries.filter { it.aboutTheRadar }, canPlay, onPlay)
            Spacer(modifier = Modifier.height(16.dp))
            SettingsRowGroup {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.DirectionsBike,
                    iconTint = if (canPlay) br.brand else br.fgFaint,
                    title = stringResource(R.string.alert_sounds_watch_demo),
                    subtitle = null,
                    onClick = onWatchDemo,
                    clickable = canPlay,
                    enabled = canPlay,
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    iconTint = br.brand,
                    title = stringResource(R.string.alert_volume_row),
                    subtitle = null,
                    onClick = onSetVolume,
                    isLast = true,
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SoundRows(sounds: List<AlertSound>, canPlay: Boolean, onPlay: (AlertSound) -> Unit) {
    val br = LocalBrColors.current
    SettingsRowGroup {
        sounds.forEachIndexed { i, sound ->
            SettingsRow(
                icon = Icons.Default.PlayArrow,
                iconTint = when {
                    !canPlay -> br.fgFaint
                    sound == AlertSound.URGENT -> br.danger
                    else -> br.brand
                },
                title = stringResource(sound.title),
                subtitle = stringResource(sound.meaning),
                onClick = { onPlay(sound) },
                chevron = false,
                clickable = canPlay,
                enabled = canPlay,
                isLast = i == sounds.lastIndex,
            )
        }
    }
}
