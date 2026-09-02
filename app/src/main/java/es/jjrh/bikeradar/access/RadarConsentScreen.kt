// SPDX-License-Identifier: GPL-3.0-or-later
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.ui.BrOutlinedButton
import es.jjrh.bikeradar.ui.LocalBrColors
import es.jjrh.bikeradar.ui.SettingsRowGroup
import es.jjrh.bikeradar.ui.SettingsToggleRow

/** The question, with whatever the rider already answered pre-filled. */
@Composable
fun RadarConsentAsk(
    request: ConsentRequest.Ask,
    onCancel: () -> Unit,
    onSave: (read: Boolean, control: Boolean) -> Unit,
) {
    var read by rememberSaveable { mutableStateOf(request.current?.read ?: false) }
    var control by rememberSaveable { mutableStateOf(request.current?.control ?: false) }
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
                stringResource(R.string.radar_consent_title),
                color = br.fg,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.radar_consent_body, request.label),
                color = br.fgMuted,
                // An app chooses its own label, so a lookalike names itself
                // whatever it likes. The package name is the half it cannot pick,
                // and an unbounded label would otherwise push the buttons off screen.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(request.packageName, color = br.fgDim)

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
                val action = consentPrimaryAction(request.current != null, read, control)
                if (action == ConsentPrimaryAction.NOTHING) {
                    // A first ask LANDS here with both switches off, so the
                    // button is dimmed before the rider has touched anything.
                    // Without a line saying why, the disabled state reads as
                    // secondary emphasis and the tap goes nowhere.
                    Text(
                        stringResource(R.string.radar_consent_choose_something),
                        // Not fgDim: that is the colour of the disabled button
                        // right below it, so the instruction would read as part
                        // of the thing it is explaining.
                        color = br.fgMuted,
                    )
                }
                BrOutlinedButton(
                    label = when (action) {
                        ConsentPrimaryAction.REVOKE -> stringResource(R.string.settings_radar_access_revoke)
                        ConsentPrimaryAction.ALLOW, ConsentPrimaryAction.NOTHING ->
                            stringResource(R.string.radar_consent_save)
                    },
                    onClick = { onSave(read, control) },
                    enabled = action != ConsentPrimaryAction.NOTHING,
                )
                BrOutlinedButton(
                    label = stringResource(R.string.radar_consent_cancel),
                    onClick = onCancel,
                )
            }
        }
    }
}
