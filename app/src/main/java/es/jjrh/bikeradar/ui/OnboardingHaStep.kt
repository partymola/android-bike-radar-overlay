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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.HaIntent
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ── Step 2 - Home Assistant ──────────────────────────────────────────

@Composable
internal fun HaStep(onContinue: () -> Unit, onSkip: () -> Unit, prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val scope = rememberCoroutineScope()
    val creds = remember { HaCredentials(ctx) }

    var urlField by remember { mutableStateOf(creds.baseUrl) }
    var tokenField by remember { mutableStateOf(creds.token) }
    var tokenVisible by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<Result<String>?>(null) }
    var pinging by remember { mutableStateOf(false) }
    val canSubmit = urlField.isNotBlank() && tokenField.isNotBlank()
    val savedWithoutTestingMsg = stringResource(R.string.onboarding_ha_saved_without_testing)

    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    // Treat existing saved creds as implicit YES so legacy installs (or
    // anyone who configured HA in Settings) skip the chooser entirely.
    val effectiveIntent = when {
        prefsSnap.haIntent == HaIntent.NO -> HaIntent.NO
        prefsSnap.haIntent == HaIntent.YES || creds.isConfigured() -> HaIntent.YES
        else -> HaIntent.UNSET
    }
    val onContinueSaving: () -> Unit = {
        if (canSubmit) {
            creds.save(urlField.trim(), tokenField.trim())
            prefs.haIntent = HaIntent.YES
            // Confirm the silent save unless the user just tested - otherwise
            // a successful Test connection chip is followed by a redundant
            // "Saved without testing" toast.
            if (pingResult?.isSuccess != true) {
                android.widget.Toast.makeText(
                    ctx,
                    savedWithoutTestingMsg,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        onContinue()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // No "Optional" mark on this step - the chooser owns the
            // decision now, so duplicating optionality in the hero would
            // suggest a separate skip path that doesn't exist.
            StepHeroBlock(
                icon = Icons.Default.Home,
                tint = Color(0xFFFF8A3D),
                mark = stringResource(R.string.onboarding_step_2_of_5),
                title = stringResource(R.string.onboarding_ha_title),
                sub = stringResource(R.string.onboarding_ha_sub),
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (effectiveIntent) {
                    HaIntent.UNSET -> HaIntentChooser(
                        onUseHa = { prefs.haIntent = HaIntent.YES },
                        onNotForMe = { prefs.haIntent = HaIntent.NO },
                    )
                    HaIntent.YES -> HaFieldsBlock(
                        urlField = urlField,
                        onUrlChange = {
                            urlField = it
                            // A successful ping is no longer trustworthy
                            // once the URL changes - without invalidating
                            // it, onContinueSaving would suppress the
                            // "Saved without testing" toast on stale creds.
                            pingResult = null
                        },
                        tokenField = tokenField,
                        onTokenChange = {
                            tokenField = it
                            pingResult = null
                        },
                        tokenVisible = tokenVisible,
                        onToggleTokenVisible = { tokenVisible = !tokenVisible },
                        pingResult = pingResult,
                        pinging = pinging,
                        canSubmit = canSubmit,
                        onTest = {
                            pinging = true
                            scope.launch(Dispatchers.IO) {
                                val client = HaClient(urlField.trim(), tokenField.trim())
                                pingResult = client.ping()
                                if (pingResult?.isSuccess == true) {
                                    creds.save(urlField.trim(), tokenField.trim())
                                    prefs.haLastValidatedEpochMs = System.currentTimeMillis()
                                    prefs.haIntent = HaIntent.YES
                                }
                                pinging = false
                            }
                        },
                        onChangeIntent = {
                            // Tapping the "change" pill must actually return
                            // the user to the chooser. Without clearing creds
                            // + local field state, `effectiveIntent` keeps
                            // re-deriving YES via the implicit-creds rule and
                            // the pill looks inert for legacy installs.
                            creds.clear()
                            prefs.haLastValidatedEpochMs = 0L
                            urlField = ""
                            tokenField = ""
                            pingResult = null
                            prefs.haIntent = HaIntent.UNSET
                        },
                    )
                    HaIntent.NO -> HaSkippedCard(
                        onChangeMind = { prefs.haIntent = HaIntent.UNSET },
                    )
                }
                // Only disclose egress once the rider has opted into HA -
                // nothing is sent in the chooser or "not for me" states.
                if (effectiveIntent == HaIntent.YES) {
                    HaStepPrivacyNote()
                }
            }
        }
        // Footer is intent-aware. UNSET: chooser drives nav, no footer.
        // YES with empty/partial fields: dual CTA so the user can still
        // bail via Skip-for-now. YES with both fields filled: only
        // Continue - the user has clearly opted into HA, and Skip-for-now
        // would silently discard typed creds. The "change" pill remains
        // the bail path. NO: single Continue.
        when (effectiveIntent) {
            HaIntent.UNSET -> Unit
            HaIntent.YES -> if (canSubmit) {
                FooterCta(
                    label = stringResource(R.string.common_continue),
                    enabled = true,
                    onClick = onContinueSaving,
                )
            } else {
                FooterCtaDual(
                    primary = stringResource(R.string.common_continue),
                    secondary = stringResource(R.string.onboarding_skip_for_now),
                    primaryEnabled = false,
                    onPrimary = onContinueSaving,
                    onSecondary = onSkip,
                )
            }
            HaIntent.NO -> FooterCta(
                label = stringResource(R.string.common_continue),
                enabled = true,
                onClick = onContinue,
            )
        }
    }
}

@Composable
internal fun HaIntentChooser(onUseHa: () -> Unit, onNotForMe: () -> Unit) {
    val br = LocalBrColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IntentCard(
            title = stringResource(R.string.onboarding_ha_use_title),
            subtitle = stringResource(R.string.onboarding_ha_use_sub),
            filled = true,
            onClick = onUseHa,
        )
        IntentCard(
            title = stringResource(R.string.onboarding_ha_notforme_title),
            subtitle = stringResource(R.string.onboarding_ha_notforme_sub),
            filled = false,
            onClick = onNotForMe,
        )
        Text(
            text = stringResource(R.string.onboarding_ha_change_later),
            color = br.fgDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
    }
}

@Composable
internal fun IntentCard(
    title: String,
    subtitle: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) br.brand.copy(alpha = 0.12f) else br.bgElev1)
            .border(
                1.dp,
                if (filled) br.brand.copy(alpha = 0.40f) else br.hairline,
                RoundedCornerShape(12.dp),
            )
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = br.fg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = br.fgMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            // Trailing chevron makes the tappability obvious - two stacked
            // cards with similar weight read as ambient panels otherwise.
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = br.fgMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun HaFieldsBlock(
    urlField: String,
    onUrlChange: (String) -> Unit,
    tokenField: String,
    onTokenChange: (String) -> Unit,
    tokenVisible: Boolean,
    onToggleTokenVisible: () -> Unit,
    pingResult: Result<String>?,
    pinging: Boolean,
    canSubmit: Boolean,
    onTest: () -> Unit,
    onChangeIntent: () -> Unit,
) {
    val br = LocalBrColors.current
    // Selected-state pill: keeps the user's pick visible and one tap away
    // from reverting, without taking the screen space a full chooser card
    // would. heightIn ensures a reasonable tap target.
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(br.brand.copy(alpha = 0.12f))
            .border(1.dp, br.brand.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onChangeIntent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_ha_using_pill),
            color = br.fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    Field(
        label = stringResource(R.string.onboarding_ha_url_label),
        value = urlField,
        onChange = onUrlChange,
        // Example value, not prose: left literal (translators keep the URL).
        placeholder = "https://homeassistant.local:8123",
        mono = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        ),
    )
    Field(
        label = stringResource(R.string.onboarding_ha_token_label),
        value = tokenField,
        onChange = onTokenChange,
        // Example value, not prose: a sample JWT prefix, left literal.
        placeholder = "eyJ0eXAiOiJKV1QiLCJh…",
        mono = true,
        visualTransformation = if (tokenVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = onToggleTokenVisible) {
                Icon(
                    imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription =
                    if (tokenVisible) {
                        stringResource(R.string.onboarding_ha_hide_token)
                    } else {
                        stringResource(R.string.onboarding_ha_show_token)
                    },
                    tint = br.fgMuted,
                )
            }
        },
        hint = stringResource(R.string.onboarding_ha_token_hint),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
    )
    val testEnabled = canSubmit && !pinging
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(br.bgElev2)
            .clickable(enabled = testEnabled, onClick = onTest),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = null,
                tint = if (testEnabled) br.brand else br.fgDim,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text =
                if (pinging) {
                    stringResource(R.string.onboarding_ha_testing)
                } else {
                    stringResource(R.string.onboarding_ha_test_connection)
                },
                color = if (testEnabled) br.fg else br.fgDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
    pingResult?.let { r ->
        BrChip(
            text =
            if (r.isSuccess) {
                stringResource(R.string.onboarding_ha_connected)
            } else {
                stringResource(
                    R.string.onboarding_ha_error,
                    r.exceptionOrNull()?.message ?: stringResource(R.string.onboarding_ha_error_generic),
                )
            },
            color = if (r.isSuccess) br.safe else br.danger,
        )
    }
    // When fields are incomplete, the dual footer shows a disabled
    // Continue with no inline reason. A muted hint here makes the
    // gating explicit without crowding the populated state.
    if (!canSubmit && pingResult == null) {
        Text(
            text = stringResource(R.string.onboarding_ha_enter_to_continue),
            color = br.fgDim,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun HaSkippedCard(onChangeMind: () -> Unit) {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_ha_skipped_title),
            color = br.fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.onboarding_ha_skipped_body),
            color = br.fgMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, br.hairline2, RoundedCornerShape(8.dp))
                .semantics { role = Role.Button }
                .clickable(onClick = onChangeMind)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_ha_use_ha_button),
                color = br.fg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The HA step's "What's sent" disclosure. Extracted (unlike the other steps'
 * inline notes) because [HaStepSnapshotTest] renders the HA leaf composables
 * directly rather than the stateful [HaStep], so this keeps the golden's copy
 * identical to what the real screen shows. Only rendered in the HaIntent.YES
 * state - nothing is sent before the rider opts in.
 */
@Composable
internal fun HaStepPrivacyNote() {
    StepPrivacyNote(
        heading = stringResource(R.string.onboarding_ha_privacy_heading),
        bullets = listOf(
            stringResource(R.string.onboarding_ha_bullet_sends),
            stringResource(R.string.onboarding_ha_bullet_only_ha),
            stringResource(R.string.onboarding_ha_bullet_private),
        ),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    hint: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val br = LocalBrColors.current
    Column {
        Text(
            text = label,
            color = br.fgMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = br.fgDim) },
            singleLine = true,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            keyboardOptions = keyboardOptions,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = br.fg,
                unfocusedTextColor = br.fg,
                focusedBorderColor = br.brand,
                unfocusedBorderColor = br.hairline2,
                cursorColor = br.brand,
                focusedContainerColor = br.bgElev1,
                unfocusedContainerColor = br.bgElev1,
            ),
            textStyle = TextStyle(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 13.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) {
            Spacer(modifier = Modifier.height(5.dp))
            Text(text = hint, color = br.fgDim, fontSize = 11.sp)
        }
    }
}
