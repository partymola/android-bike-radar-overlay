// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * Shared atoms for the redesigned Settings flow. Mirrors the JSX
 * `SettingsHeader`, `Row`, `SectionLabel`, `Toggle` patterns in
 * `settings-screens.jsx`. Used by SettingsHome + every sub-screen.
 */

@Composable
fun NextSettingsHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = br.fg,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = title,
            color = br.fg,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.3).sp,
        )
    }
}

/**
 * Section label with the JSX `SectionLabel`'s padding. A Spacer is
 * NOT included — call sites add their own vertical breathing room.
 */
@Composable
fun NextSettingsSectionLabel(text: String) {
    val br = LocalBrColors.current
    Text(
        text = text.uppercase(),
        color = br.fgDim,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
    )
}

/**
 * Tappable settings home row: icon (in tinted-bg square), title +
 * subtitle, chevron-right at the end. Mirrors JSX `Row` in
 * settings-screens.jsx.
 */
@Composable
fun NextSettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    isLast: Boolean = false,
    rightContent: @Composable (() -> Unit)? = null,
    chevron: Boolean = true,
    clickable: Boolean = true,
) {
    val br = LocalBrColors.current
    Column(
        modifier = if (clickable) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = br.fg, fontSize = 14.sp)
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = br.fgDim,
                        fontSize = 12.sp,
                    )
                }
            }
            if (rightContent != null) rightContent()
            // A chevron implies the row navigates somewhere, so suppress
            // it on non-clickable rows even if the caller forgot to pair
            // the two flags.
            if (chevron && clickable) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = br.fgFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 20.dp, end = 20.dp)
                    .background(br.hairline),
            )
        }
    }
}

/**
 * Card that wraps a group of NextSettingsRow children. Shared frame
 * with the mockup's GENERAL/ADVANCED row groupings.
 */
@Composable
fun NextSettingsRowGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val br = LocalBrColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(br.bgElev1),
    ) {
        content()
    }
}

/**
 * Toggle row used inside sub-screens — title + sub + Switch on the
 * right. Border-bottom hairline unless `isLast`. Matches the JSX `Row`
 * variant with a `right={<Toggle/>}` and `chevron={false}`.
 */
@Composable
fun NextSettingsToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    isLast: Boolean = true,
    leadingIcon: ImageVector? = null,
    leadingTint: Color? = null,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background((leadingTint ?: br.fgMuted).copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = leadingTint ?: br.fgMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = if (enabled) br.fg else br.fgMuted, fontSize = 14.sp)
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = br.fgDim,
                        fontSize = 12.sp,
                    )
                }
            }
            NextToggle(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 20.dp, end = 20.dp)
                    .background(br.hairline),
            )
        }
    }
}

/**
 * Slider-with-helper card used in radar/alerts and dashcam sub-screens.
 * Mockup shows: title row + "value" right-aligned in mono brand colour,
 * helper subtitle below in `fgDim`, then NextSlider, all inside the
 * surrounding card.
 */
@Composable
fun NextSettingsSliderRow(
    title: String,
    valueDisplay: String,
    helper: String?,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    steps: Int = 0,
    paddingHorizontal: Dp = 20.dp,
    paddingBottom: Dp = 18.dp,
) {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = paddingHorizontal)
            .padding(bottom = paddingBottom),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = title, color = br.fg, fontSize = 14.sp)
            Text(
                text = valueDisplay,
                color = br.brand,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
        }
        if (helper != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = helper,
                color = br.fgDim,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        NextSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            steps = steps,
        )
    }
}

/** A simple framed nested card (for slider groupings). */
@Composable
fun NextNestedCard(
    modifier: Modifier = Modifier,
    paddingHorizontal: Dp = 16.dp,
    paddingVertical: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val br = LocalBrColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(br.bgElev1),
    ) {
        Column(modifier = Modifier.padding(horizontal = paddingHorizontal, vertical = paddingVertical)) {
            content()
        }
    }
}
