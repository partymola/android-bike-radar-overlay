// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Shared primitives for the redesigned UX. Each Composable here is a
 * port of an `ui.jsx` / `main-screens.jsx` shared atom from the design
 * handoff bundle. Structure mirrors the JSX so cross-referencing is
 * straightforward; rendering uses idiomatic Compose / Material 3 APIs.
 *
 * Tokens come from [LocalBrColors] (set up by [UiTheme]). Don't
 * hardcode hex values here — read from the composition local.
 */

// ── BrCard — the canonical surface in the redesign ──────────────────
//
// JSX `Card` (ui.jsx): solid background T.bgElev1, 14dp radius,
// 1px hairline border, optional 3dp left accent strip. Used everywhere
// for the cards that organise content. Prefixed `Br` to avoid colliding
// with `androidx.compose.material3.Card` on auto-import.

@Composable
fun BrCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val br = LocalBrColors.current
    val shapes = LocalBrShapes.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(shapes.r3))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(shapes.r3)),
    ) {
        if (accent != null) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
        }
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

// ── StatusDot ─────────────────────────────────────────────────────────
//
// JSX `StatusDot` (ui.jsx): solid coloured dot with optional pulsing
// halo. Used in the system card, hero, and dashcam picker.

@Composable
fun StatusDot(
    color: Color,
    pulse: Boolean = false,
    hollow: Boolean = false,
    size: Dp = 8.dp,
) {
    Box(modifier = Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        if (pulse) {
            val transition = rememberInfiniteTransition(label = "br-pulse")
            val pulseScale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 2.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1_800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "br-pulse-scale",
            )
            val pulseAlpha by transition.animateFloat(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1_800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "br-pulse-alpha",
            )
            Box(
                modifier = Modifier
                    .size(size * pulseScale)
                    .clip(CircleShape)
                    .background(color.copy(alpha = pulseAlpha)),
            )
        }
        if (hollow) {
            // Hollow ring: a solid-colour circle with a slightly smaller
            // background-coloured circle inside. Reads as "outline only"
            // and visually distinguishes setup-states ("Not paired")
            // from runtime states.
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color),
            ) {
                Box(
                    modifier = Modifier
                        .padding(1.5.dp)
                        .size(size - 3.dp)
                        .clip(CircleShape)
                        .background(LocalBrColors.current.bgElev1),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

// ── BatteryChip ───────────────────────────────────────────────────────
//
// JSX `BatteryChip` (ui.jsx): tiny battery icon shape (rounded rect with
// a small terminal nub) + percentage in mono tabular-nums. Colour is
// caution at <=20%, danger at <=10%, fg otherwise. Used in the system
// card and dashcam pairing card.

@Composable
fun BatteryChip(
    pct: Int,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    val color = when {
        pct <= 10 -> br.danger
        pct <= 20 -> br.caution
        else -> br.fg
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Battery body: 22 x 10 with 2dp radius and a 1dp border.
            Box(
                modifier = Modifier
                    .size(width = 22.dp, height = 10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .border(1.dp, color, RoundedCornerShape(2.dp))
                    .padding(1.dp),
            ) {
                val fillFraction = max(pct / 100f, 0.04f)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fillFraction)
                        .background(color),
                )
            }
            // Battery terminal nub.
            Box(
                modifier = Modifier
                    .padding(start = 1.dp)
                    .size(width = 1.5.dp, height = 4.dp)
                    .clip(RoundedCornerShape(0.5.dp))
                    .background(color),
            )
        }
        Text(
            text = "$pct%",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
        )
        if (label != null) {
            Text(
                text = label,
                color = br.fgDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

// ── BrChip ───────────────────────────────────────────────────────────
//
// JSX `Chip`: pill with mono uppercase text. Two variants: `solid` (fg
// on a coloured background) and ghost (coloured fg on a 14% tinted
// background). Used for "PAIRED", "OPTIONAL", "VUE", etc. Prefixed `Br`
// to avoid colliding with `androidx.compose.material3` chip APIs on
// auto-import.

@Composable
fun BrChip(
    text: String,
    color: Color,
    solid: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    val bg = if (solid) color else color.copy(alpha = 0.08f)
    val fg = if (solid) br.bg else color
    val borderColor = if (solid) Color.Transparent else color.copy(alpha = 0.25f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
        )
    }
}

// ── SectionLabel ──────────────────────────────────────────────────────
//
// JSX `SectionLabel` / `SectionHeader`: tiny mono uppercase letterspace
// `1.4`. Used to delimit groups inside cards and on the settings home.

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    Text(
        text = text.uppercase(),
        color = br.fgDim,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}

// ── BrToggle ─────────────────────────────────────────────────────────
//
// JSX `Toggle` (settings-screens.jsx): 40x24 pill with brand-coloured
// background when on, bgElev3 when off, 20x20 white knob. Anim 150ms.
// Prefixed `Br` to avoid colliding with any future `material3` toggle
// API on auto-import.

@Composable
fun BrToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    val bg = when {
        !enabled -> br.bgElev3.copy(alpha = 0.5f)
        checked -> br.brand
        else -> br.bgElev3
    }
    Box(
        // toggleable wires up Role.Switch + on/off state for TalkBack;
        // a plain clickable box gets announced as a generic button.
        modifier = modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

// ── BrSlider ─────────────────────────────────────────────────────────
//
// JSX `Slider` (settings-screens.jsx): 3dp track in bgElev3, brand-fill
// up to value, 20dp white thumb with shadow. We render Material 3's
// Slider with custom SliderColors so it looks like the mockup but
// keeps Material's gesture handling (which V1 relied on inside a
// vertically-scrolling Column to avoid horizontal-drag conflicts).
// Prefixed `Br` to avoid colliding with `androidx.compose.material3.Slider`
// on auto-import.

@Composable
fun BrSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    steps: Int = 0,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = br.brand,
            activeTickColor = br.brand,
            inactiveTrackColor = br.bgElev3,
            inactiveTickColor = br.bgElev3,
        ),
        modifier = modifier,
    )
}

// ── Sparkline ─────────────────────────────────────────────────────────
//
// JSX `Spark` (main-screens.jsx): mini bar chart, bars above 75% of
// local max highlighted in caution colour. Used on the close-pass
// stats card.

@Composable
fun Sparkline(
    data: List<Int>,
    height: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    val max = (data.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(
        modifier = modifier.height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (v in data) {
            val barFraction = (v.toFloat() / max).coerceIn(0f, 1f)
            val hot = v > max * 0.75
            val color = if (hot) br.caution else br.hairline2
            val alpha = if (v == 0) 0.25f else 1f
            val barHeight = (barFraction * height.value).coerceAtLeast(2f).dp
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

// ── BrMark — mockup top-bar logo ──────────────────────────────────────
//
// Renders the project's existing launcher foreground PNG, which is the
// authoritative BR mark (blue letters, white background, radar swoosh
// on the R). The mipmap launcher is an adaptive-icon XML which
// painterResource refuses to load; the per-density PNGs under
// `drawable-*dpi/ic_launcher_foreground.png` are real raster assets
// and work directly.

@Composable
fun BrMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    // Render the launcher's adaptive-icon layers manually: a white
    // background tile (rounded), with the foreground PNG (the cyan-
    // blue BR letters) on top. The mipmap adaptive XML can't be
    // loaded by painterResource, so we compose the layers ourselves.
    //
    // The foreground PNG follows Android's adaptive-icon convention
    // (108 dp canvas, 72 dp visible safe zone) so its drawn content
    // only occupies ~66 % of the bitmap. To make the letters fill the
    // tile we render the PNG at 1.5× the tile and let the rounded clip
    // absorb the transparent overflow.
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(
                id = es.jjrh.bikeradar.R.drawable.ic_launcher_foreground,
            ),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}

// ── Telemetry — big mono number (used on close-pass stats card) ──────
//
// JSX `Telemetry` (ui.jsx): big number + optional unit + optional
// uppercase label below.

@Composable
fun Telemetry(
    value: String,
    unit: String? = null,
    label: String? = null,
    color: Color? = null,
    size: androidx.compose.ui.unit.TextUnit = 34.sp,
) {
    val br = LocalBrColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = color ?: br.fg,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = size,
                letterSpacing = (-0.5).sp,
            )
            if (unit != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unit,
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
            }
        }
        if (label != null) {
            Text(
                text = label.uppercase(),
                color = br.fgDim,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
        }
    }
}

// ── BrSegmentedControl ───────────────────────────────────────────────
//
// JSX uses a flex row of buttons inside a small bg-tinted frame. The
// selected option has bgElev2 fill, others are transparent. Used for
// the year / month / week selector on the close-pass stats card.
// Prefixed `Br` for symmetry with the rest of the Br* primitive set
// even though no current M3 API shadows the bare name.

@Composable
fun BrSegmentedControl(
    options: List<Pair<String, String>>, // value -> label
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(br.bg)
            .border(1.dp, br.hairline, RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for ((value, label) in options) {
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSelected) br.bgElev2 else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) br.fg else br.fgMuted,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

// ── Mark — small uppercase mono caption used in onboarding ───────────
//
// JSX `Mark` (onboarding.jsx): "STEP 1 OF 3 · OPTIONAL" style label.

@Composable
fun Mark(text: String, modifier: Modifier = Modifier) {
    val br = LocalBrColors.current
    Text(
        text = text.uppercase(),
        color = br.fgDim,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        modifier = modifier,
    )
}

@Composable
fun H1(text: String, modifier: Modifier = Modifier) {
    val br = LocalBrColors.current
    Text(
        text = text,
        color = br.fg,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp,
        modifier = modifier,
    )
}

@Composable
fun Sub(text: String, modifier: Modifier = Modifier) {
    val br = LocalBrColors.current
    Text(
        text = text,
        color = br.fgMuted,
        fontFamily = FontFamily.Default,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = modifier,
    )
}

// ── HeroIcon — large tinted-bg icon in onboarding step heroes ────────
//
// JSX `StepHero` (onboarding.jsx): 52dp rounded square in `tint`-tinted
// background with the icon centred. Used at the top of each onboarding
// step.

@Composable
fun HeroIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    size: Dp = 52.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

// ── PairedChip — green status pill used in onboarding & system card ─
//
// Small `PAIRED` green pill with a leading dot, drawn beside a device
// row when it has a system Bluetooth bond.

@Composable
fun PairedChip(modifier: Modifier = Modifier) {
    val br = LocalBrColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(br.safe.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(br.safe),
        )
        Text(
            text = "PAIRED",
            color = br.safe,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
        )
    }
}

// ── ContentColorEnsurer (helper to keep MaterialTheme content colour
// in sync with our overrides for nested composables) ─────────────────

@Composable
fun WithContentColor(color: Color, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalContentColor provides color,
        content = content,
    )
}

// Shared helper: theme-aware horizontal divider line used inside cards.
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val br = LocalBrColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(br.hairline),
    )
}

