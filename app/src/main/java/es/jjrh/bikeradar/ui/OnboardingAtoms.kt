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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.jjrh.bikeradar.R

/**
 * Per-step privacy disclosure card. A calm informational block (NOT an
 * alert - plain [LocalBrColors.bgElev1], no warning colour) placed as the
 * last item in a step body, above the footer: a short mono [heading], up to
 * ~3 one-line [bullets], and a fixed "Full detail: Settings -> Privacy"
 * pointer.
 *
 * The pointer is hardcoded, not a parameter, so a step cannot ship the
 * disclosure without it. [heading] varies per step ("What these allow" /
 * "What's sent" / "What this can access" / "What's collected") so four
 * disclosures don't read as boilerplate, while the chrome + pointer stay
 * identical so they read as one system. Callers must only render this in
 * states where the bullets are true - e.g. the HA "What's sent" card is
 * gated on the rider having opted into HA, since nothing is sent otherwise.
 */
@Composable
internal fun StepPrivacyNote(
    heading: String,
    bullets: List<String>,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mark(text = heading)
        Text(
            text = bullets.joinToString("\n") { "•  $it" },
            color = br.fgMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Text(
            text = stringResource(R.string.onboarding_privacy_full_detail),
            color = br.fgDim,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun StepHeroBlock(
    icon: ImageVector,
    tint: Color,
    mark: String,
    title: String,
    sub: String,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        HeroIcon(icon = icon, tint = tint)
        Spacer(modifier = Modifier.height(16.dp))
        Mark(text = mark)
        Spacer(modifier = Modifier.height(8.dp))
        H1(text = title)
        Spacer(modifier = Modifier.height(6.dp))
        Sub(text = sub)
    }
}

@Composable
internal fun FooterCta(label: String, enabled: Boolean, onClick: () -> Unit) {
    val br = LocalBrColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(br.bg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(br.hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) br.brand else br.bgElev2)
                    .clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (enabled) br.bg else br.fgDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun FooterCtaDual(
    primary: String,
    secondary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxWidth().background(br.bg)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(br.hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, br.hairline2, RoundedCornerShape(12.dp))
                    .clickable(onClick = onSecondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = secondary, color = br.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (primaryEnabled) br.brand else br.bgElev2)
                    .clickable(enabled = primaryEnabled, onClick = onPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = primary,
                    color = if (primaryEnabled) br.bg else br.fgDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
