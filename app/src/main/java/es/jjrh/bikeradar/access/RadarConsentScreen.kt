// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.access

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.ui.BrOutlinedButton
import es.jjrh.bikeradar.ui.LocalBrColors
import es.jjrh.bikeradar.ui.SettingsRowGroup
import es.jjrh.bikeradar.ui.SettingsToggleRow

/**
 * The question, with whatever the rider already answered pre-filled.
 *
 * [bikeRadarSetUp] is false until onboarding is done, and the rider is then
 * pointed back to finish it.
 */
@Composable
fun RadarConsentAsk(
    request: ConsentRequest.Ask,
    bikeRadarSetUp: Boolean,
    onCancel: () -> Unit,
    onSave: (read: Boolean, control: Boolean) -> Unit,
) {
    var read by rememberSaveable { mutableStateOf(request.current?.read ?: false) }
    var control by rememberSaveable { mutableStateOf(request.current?.control ?: false) }
    val revisit = request.current != null
    val br = LocalBrColors.current

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (revisit) R.string.radar_consent_title_again else R.string.radar_consent_title),
                color = br.fg,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(if (revisit) R.string.radar_consent_body_again else R.string.radar_consent_body, request.label),
                color = br.fgMuted,
                // An app chooses its own label, so a lookalike names itself
                // whatever it likes. The package name is the half it cannot pick,
                // and an unbounded label would otherwise push the buttons off screen.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(request.packageName, color = br.fgDim)
            if (!bikeRadarSetUp) {
                Text(stringResource(R.string.radar_consent_not_set_up), color = br.caution)
            }

            SettingsRowGroup {
                SettingsToggleRow(
                    title = stringResource(R.string.radar_consent_read),
                    subtitle = stringResource(R.string.radar_consent_read_detail),
                    checked = read,
                    onCheckedChange = { read = it },
                    isLast = false,
                )
                SettingsToggleRow(
                    title = stringResource(R.string.radar_consent_control),
                    subtitle = stringResource(R.string.radar_consent_control_detail),
                    checked = control,
                    onCheckedChange = { control = it },
                )
            }

            Text(stringResource(R.string.radar_consent_backup_note), color = br.fgMuted)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val action = consentPrimaryAction(revisit, read, control)
                // A first ask LANDS here with both switches off, so the button
                // is dimmed before the rider has touched anything. Without a
                // line saying why, the disabled state reads as secondary
                // emphasis and the tap goes nowhere. Over an existing grant,
                // while a switch is on, the same slot says how to stop from
                // here; the note above only points to Settings.
                //
                // The slot keeps one height whatever it shows. If it shrank,
                // both buttons would move up as the rider flips a switch, the
                // moment their finger is already travelling towards the primary
                // button, and it would land on the second one. It sits on an
                // invisible copy of the one line this request can show, styled
                // like it, so that holds at any width or font size
                // (RadarConsentAskButtonTest).
                val chooseSomething = stringResource(R.string.radar_consent_choose_something)
                val howToStop = stringResource(R.string.radar_consent_how_to_stop)
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (revisit) howToStop else chooseSomething,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                    )
                    Text(
                        text = when {
                            action == ConsentPrimaryAction.NOTHING -> chooseSomething
                            action == ConsentPrimaryAction.ALLOW && revisit -> howToStop
                            else -> ""
                        },
                        textAlign = TextAlign.Center,
                        // Not fgDim: that is the colour of the disabled button right
                        // below it, so the instruction would read as part of the
                        // thing it is explaining.
                        color = br.fgMuted,
                        // It changes under a switch the rider just flipped, somewhere
                        // a screen reader's focus is not.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                BrOutlinedButton(
                    label = when (action) {
                        ConsentPrimaryAction.REVOKE -> stringResource(R.string.settings_radar_access_revoke)
                        // Over a grant "Allow" would read as a new request.
                        ConsentPrimaryAction.ALLOW, ConsentPrimaryAction.NOTHING ->
                            stringResource(if (revisit) R.string.radar_consent_save_change else R.string.radar_consent_save)
                    },
                    onClick = { onSave(read, control) },
                    enabled = action != ConsentPrimaryAction.NOTHING,
                )
                // Changes nothing, so over an existing grant it must not read
                // "Don't allow": stopping is the primary button, switches off
                // (RadarConsentAskButtonTest).
                BrOutlinedButton(
                    label = if (revisit) {
                        stringResource(R.string.common_cancel)
                    } else {
                        stringResource(R.string.radar_consent_cancel)
                    },
                    onClick = onCancel,
                )
            }
        }
    }
}
