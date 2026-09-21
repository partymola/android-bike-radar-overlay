// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.jjrh.bikeradar.R

/**
 * The riding-aid notice, shown once on the next launch of the app.
 *
 * Not "before a rider can reach the app": `BootReceiver` restarts the service
 * on `MY_PACKAGE_REPLACED`, so an upgrading rider's radar comes back without
 * them opening anything, and they can ride a whole commute before they see
 * this. The notice gates the UI, never the service, and
 * `MainActivitySmokeTest.theServiceStillStartsWhileTheNoticeIsUp` pins that:
 * withholding the radar until the tap would silence a rider who opens the app
 * mid-ride.
 *
 * One screen with one button, deliberately: it is a gate rather than a
 * settings page, and the text has to be readable at a glance by someone who
 * wants to get on with it.
 *
 * Settings, About re-opens THIS composable rather than a read-only copy of
 * it, and the button there only closes the screen. Do not reintroduce a
 * second version for that route: a variant is free to render the title twice,
 * to carry no golden of its own, and to let its copy drift from this one, and
 * nothing would report any of it.
 *
 * No other ACTIVITY of this app reaches a rider before the tap.
 * `NoScreenBeforeTheNoticeTest` pins the manifest's activity set and gates the
 * exported consent activity another app can start; this launcher's own gating
 * is pinned by `SafetyNoticeGateTest` and `SafetyNoticeAcknowledgeTest`.
 *
 * ACTIVITY rather than "screen", and the word carries the whole claim: the
 * service described above keeps drawing the overlay, posting its notification
 * and sounding its cues for a rider who has not tapped. That is the exception,
 * and it is the design rather than an oversight.
 *
 * It is NOT a step in the onboarding pager. Sitting in front of the pager is
 * what lets one flag serve both a new install and an upgrading one without a
 * rider ever seeing it twice; see [es.jjrh.bikeradar.data.Prefs.safetyNoticeAcknowledged].
 */
@Composable
fun SafetyNoticeScreen(onAcknowledge: () -> Unit) {
    UiTheme {
        val br = LocalBrColors.current
        Column(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SafetyNoticeBody()
            }
            FooterCta(
                label = stringResource(R.string.safety_notice_ack),
                enabled = true,
                onClick = onAcknowledge,
            )
        }
    }
}

@Composable
private fun SafetyNoticeBody() {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The glyph carries what the words cannot: that this is a warning and
        // not a settings page, before a line has been read.
        HeroIcon(icon = Icons.Default.Warning, tint = br.caution, size = 72.dp)
        H1(text = stringResource(R.string.safety_notice_title))
        Text(
            text = stringResource(R.string.safety_notice_intro),
            color = br.fg,
            fontSize = 15.sp,
            lineHeight = 23.sp,
        )
        Text(
            text = listOf(
                stringResource(R.string.safety_notice_bullet_miss),
                stringResource(R.string.safety_notice_bullet_drop),
                stringResource(R.string.safety_notice_bullet_clear),
            ).joinToString("\n") { "•  $it" },
            color = br.fgMuted,
            fontSize = 14.sp,
            lineHeight = 24.sp,
        )
        Text(
            text = stringResource(R.string.safety_notice_closing),
            color = br.fg,
            fontSize = 15.sp,
            lineHeight = 23.sp,
        )
    }
}
