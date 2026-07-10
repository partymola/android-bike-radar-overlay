// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.EBikeStateBus
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.eBikeDataIsFresh

// ── Step 5: eBike ────────────────────────────────────────────────────

/**
 * Onboarding's final step: connect your eBike. Tri-state by [EBikeOwnership]:
 *  - UNANSWERED / NO: chooser with two balanced [IntentCard]s.
 *  - YES: a status hero (green = receiving, amber = action needed) over an
 *    adaptive CTA (Install Bosch Flow / Open Bosch Flow / "✓ Receiving"
 *    confirmation), plus skip + go-back escape and a "What's collected"
 *    privacy note.
 *
 * Snapshot freshness is consumed via [EBikeStateBus], a process-wide
 * singleton that [BikeRadarService] mirrors from its service-owned eBike
 * status reader. This decoupling keeps the composable testable without a
 * service binder; the tradeoff is that the bus reads as "no frame ever"
 * when the service isn't running, so we fire
 * [BikeRadarService.ACTION_START_EBIKE_READER] from the chooser's "I have one"
 * branch to bring the subsystem up.
 */
@Composable
internal fun EBikeStep(prefs: Prefs, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-check Flow's presence on resume: a rider who taps "Install Bosch
    // Flow", installs it, and returns should see the CTA flip to "Open".
    var resume by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resume++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val lastUpdated by EBikeStateBus.lastUpdatedElapsedMs.collectAsState()
    val receiving = eBikeDataIsFresh(lastUpdated)
    val flowInstalled = remember(resume) {
        ctx.packageManager.getLaunchIntentForPackage(EBIKE_FLOW_PACKAGE) != null
    }

    EBikeStepContent(
        ownership = prefsSnap.eBikeOwnership,
        receiving = receiving,
        flowInstalled = flowInstalled,
        onChooseHave = {
            // Promotion -> YES. Enabling the feature + starting the service
            // brings up the read-only status reader; there is no pairing
            // step (the bike's link is owned by Flow, we just listen).
            prefs.eBikeOwnership = EBikeOwnership.YES
            prefs.eBikeDataEnabled = true
            ctx.startService(
                Intent(ctx, BikeRadarService::class.java).setAction(BikeRadarService.ACTION_START_EBIKE_READER),
            )
        },
        onChooseDontHave = {
            prefs.eBikeOwnership = EBikeOwnership.NO
            prefs.eBikeDataEnabled = false
            onFinish()
        },
        onOpenFlow = { openFlowFromOnboarding(ctx) },
        onBack = {
            // Escape hatch from the YES branch back to the chooser - for a
            // rider who picked "I have one" but can't get data working, or
            // chose it by mistake. Disable until they answer again.
            prefs.eBikeOwnership = EBikeOwnership.UNANSWERED
            prefs.eBikeDataEnabled = false
        },
        onFinish = onFinish,
    )
}

/**
 * Stateless leaf. Snapshot-friendly; renders the eBike step from
 * already-derived state. Side effects (Prefs writes, service intents)
 * live in the body above.
 */
@Composable
internal fun EBikeStepContent(
    ownership: EBikeOwnership,
    receiving: Boolean,
    flowInstalled: Boolean,
    onChooseHave: () -> Unit,
    onChooseDontHave: () -> Unit,
    onOpenFlow: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when (ownership) {
                EBikeOwnership.UNANSWERED, EBikeOwnership.NO -> {
                    StepHeroBlock(
                        icon = Icons.AutoMirrored.Filled.DirectionsBike,
                        tint = br.brand,
                        mark = stringResource(R.string.onboarding_last_step),
                        title = stringResource(R.string.onboarding_ebike_title),
                        sub = stringResource(R.string.onboarding_ebike_sub),
                    )
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        EBikeChooser(onHaveOne = onChooseHave, onDontHaveOne = onChooseDontHave)
                    }
                }
                // The YES body is status-first: it renders its own state-driven
                // hero (see EBikeHowItWorks) instead of the generic one above.
                EBikeOwnership.YES -> EBikeHowItWorks(
                    receiving = receiving,
                    flowInstalled = flowInstalled,
                    onOpenFlow = onOpenFlow,
                    onBack = onBack,
                )
            }
        }
        // Footer: the chooser drives its own nav (no footer); once the rider
        // has said YES, a single Finish completes onboarding - the eBike status
        // shows on the home screen later whenever Flow is open.
        if (ownership == EBikeOwnership.YES) {
            FooterCta(label = stringResource(R.string.onboarding_finish), enabled = true, onClick = onFinish)
        }
    }
}

@Composable
private fun EBikeChooser(onHaveOne: () -> Unit, onDontHaveOne: () -> Unit) {
    val br = LocalBrColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = listOf(
                stringResource(R.string.onboarding_ebike_bullet_status),
                stringResource(R.string.onboarding_ebike_bullet_climb),
                stringResource(R.string.onboarding_ebike_bullet_timing),
            ).joinToString("\n") { "•  $it" },
            color = br.fgMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        IntentCard(
            title = stringResource(R.string.onboarding_ebike_have_title),
            subtitle = stringResource(R.string.onboarding_ebike_have_sub),
            filled = true,
            onClick = onHaveOne,
        )
        IntentCard(
            title = stringResource(R.string.onboarding_ebike_donthave_title),
            subtitle = stringResource(R.string.onboarding_ebike_donthave_sub),
            filled = false,
            onClick = onDontHaveOne,
        )
        Text(
            text = stringResource(R.string.onboarding_ebike_change_later),
            color = br.fgDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
    }
}

/**
 * YES-branch body, status-first. The hero IS the status: a state-tinted bike
 * icon (green [LocalBrColors.safe] = receiving, amber [LocalBrColors.caution] =
 * action needed - the same colours the home-screen eBike card uses) over a big
 * title and a one-line subline that states the one thing to do, if anything:
 *  - receiving     -> "You're all set" / no action, no button.
 *  - Flow present   -> "Almost there" / open Flow, + an Open button.
 *  - Flow absent    -> "Almost there" / install Flow, + an Install button.
 * There is deliberately no "how it works" explainer - the mechanism doesn't
 * affect the act/don't-act decision; it lives on the Settings -> eBike screen.
 * When not receiving the rider can finish now and set up later (the Finish
 * footer) or [onBack] to the chooser. The "What's collected" disclosure matches
 * the other onboarding steps.
 */
@Composable
private fun EBikeHowItWorks(
    receiving: Boolean,
    flowInstalled: Boolean,
    onOpenFlow: () -> Unit,
    onBack: () -> Unit,
) {
    val br = LocalBrColors.current
    StepHeroBlock(
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        tint = if (receiving) br.safe else br.caution,
        mark = stringResource(R.string.onboarding_last_step),
        title =
        if (receiving) {
            stringResource(R.string.onboarding_ebike_allset)
        } else {
            stringResource(R.string.onboarding_ebike_almost)
        },
        sub = when {
            receiving -> stringResource(R.string.onboarding_ebike_sub_receiving)
            flowInstalled -> stringResource(R.string.onboarding_ebike_sub_open)
            else -> stringResource(R.string.onboarding_ebike_sub_install)
        },
    )
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!receiving) {
            // One action: install Flow if it's missing, else open it.
            // openFlowFromOnboarding falls back to the Play Store when Flow is
            // absent, so the same handler serves both labels.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(br.brand)
                    .clickable(onClick = onOpenFlow),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (flowInstalled) {
                            Icons.AutoMirrored.Filled.OpenInNew
                        } else {
                            Icons.Default.Download
                        },
                        contentDescription = null,
                        tint = br.bg,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text =
                        if (flowInstalled) {
                            stringResource(R.string.onboarding_ebike_open_flow)
                        } else {
                            stringResource(R.string.onboarding_ebike_install_flow)
                        },
                        color = br.bg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // Escape hatch: not everyone can get data flowing during setup.
            Text(
                text = stringResource(R.string.onboarding_ebike_finish_later),
                color = br.fgMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Text(
                text = stringResource(R.string.onboarding_ebike_go_back),
                color = br.brand,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 4.dp),
            )
        }

        StepPrivacyNote(
            heading = stringResource(R.string.onboarding_ebike_privacy_heading),
            bullets = listOf(
                stringResource(R.string.onboarding_ebike_bullet_reads),
                stringResource(R.string.onboarding_ebike_bullet_phone),
                stringResource(R.string.onboarding_ebike_bullet_ha),
            ),
        )
    }
}

private const val EBIKE_FLOW_PACKAGE = "com.bosch.ebike.onebikeapp"

private fun openFlowFromOnboarding(ctx: Context) {
    val pm = ctx.packageManager
    val launch = pm.getLaunchIntentForPackage(EBIKE_FLOW_PACKAGE)
    val intent = launch?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$EBIKE_FLOW_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            ctx,
            ctx.getString(R.string.onboarding_ebike_flow_open_failed),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}
