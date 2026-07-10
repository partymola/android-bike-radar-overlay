// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import es.jjrh.bikeradar.R

// ── Step 0 - Permissions ─────────────────────────────────────────────

@Composable
internal fun PermissionsStep(onContinue: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val states = remember(refresh) {
        PERMISSIONS.map { it to isSpecGranted(ctx, it) }
    }
    val requiredGranted = states.all { (spec, granted) -> !spec.required || granted }

    PermissionsStepContent(
        states = states,
        requiredGranted = requiredGranted,
        onContinue = onContinue,
        onPermissionChanged = { refresh++ },
    )
}

/**
 * Stateless leaf for the onboarding permissions step. Body of
 * [PermissionsStep] forwards a pre-resolved list of (spec, granted)
 * pairs and a refresh callback, keeping the lifecycle/permission-launcher
 * plumbing out of this composable so snapshot tests can render the
 * step without an Activity or [LocalContext].
 */
@Composable
internal fun PermissionsStepContent(
    states: List<Pair<PermissionSpec, Boolean>>,
    requiredGranted: Boolean,
    onContinue: () -> Unit,
    onPermissionChanged: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.Default.Shield,
                tint = br.brand,
                mark = stringResource(R.string.onboarding_step_1_of_5),
                title = stringResource(R.string.onboarding_perm_title),
                sub = stringResource(R.string.onboarding_perm_sub),
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((spec, granted) in states) {
                    PermissionCard(spec = spec, granted = granted, onChanged = onPermissionChanged)
                }
                StepPrivacyNote(
                    heading = stringResource(R.string.onboarding_perm_privacy_heading),
                    bullets = listOf(
                        stringResource(R.string.onboarding_perm_bullet_bluetooth),
                        stringResource(R.string.onboarding_perm_bullet_notifications),
                        stringResource(R.string.onboarding_perm_bullet_location),
                    ),
                )
            }
        }
        FooterCta(
            label = stringResource(R.string.common_continue),
            enabled = requiredGranted,
            onClick = onContinue,
        )
    }
}
