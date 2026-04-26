// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bike Radar theme. Wraps content in a Material 3 dark color scheme
 * derived from the mockup's `tokens.js` palette and exposes the full
 * set of mockup tokens through [LocalBrColors] for code that needs
 * tokens M3 doesn't have a slot for (fgMuted, fgDim, brandGlow, status
 * colours, dashcam tint, etc).
 */

/**
 * Mockup token bundle. Field names mirror the design handoff's named
 * tokens so mapping from the design source to a Compose call-site is
 * mechanical: read the design source, find `T.<name>`, write
 * `BrColors.<name>` here.
 */
@Immutable
data class BrColors(
    val bg: Color,
    val bgElev1: Color,
    val bgElev2: Color,
    val bgElev3: Color,
    val hairline: Color,
    val hairline2: Color,
    val fg: Color,
    val fgMuted: Color,
    val fgDim: Color,
    val fgFaint: Color,
    val brand: Color,
    val brandDim: Color,
    val brandGlow: Color,
    val brand2: Color,
    val safe: Color,
    val caution: Color,
    val danger: Color,
    val dangerHot: Color,
    val dashcam: Color,
) {
    companion object {
        val Default = BrColors(
            bg        = Color(0xFF0B0F14),
            bgElev1   = Color(0xFF121821),
            bgElev2   = Color(0xFF1A222E),
            bgElev3   = Color(0xFF232D3B),
            // rgba(255,255,255,0.08) and 0.14
            hairline  = Color(0x14FFFFFF),
            hairline2 = Color(0x24FFFFFF),
            fg        = Color(0xFFE8ECF2),
            fgMuted   = Color(0xFF9CA6B4),
            fgDim     = Color(0xFF6B7585),
            fgFaint   = Color(0xFF424B59),
            brand     = Color(0xFF1FA6FF),
            brandDim  = Color(0xFF1476C4),
            // rgba(31,166,255,0.18)
            brandGlow = Color(0x2E1FA6FF),
            brand2    = Color(0xFF2BDAFF),
            safe      = Color(0xFF3DD68C),
            caution   = Color(0xFFF5B53C),
            danger    = Color(0xFFFF4D5E),
            dangerHot = Color(0xFFFF6B3D),
            dashcam   = Color(0xFFC48BFF),
        )
    }
}

val LocalBrColors = staticCompositionLocalOf { BrColors.Default }

/** Mockup's `T.r1..r5` corner radii. */
@Immutable
data class BrShapes(
    val r1: Dp,
    val r2: Dp,
    val r3: Dp,
    val r4: Dp,
    val r5: Dp,
) {
    companion object {
        val Default = BrShapes(r1 = 6.dp, r2 = 10.dp, r3 = 14.dp, r4 = 18.dp, r5 = 24.dp)
    }
}
val LocalBrShapes = staticCompositionLocalOf { BrShapes.Default }

/**
 * Compose Typography tuned to the mockup's font declarations. The
 * mockup's `T.sans` (Inter) and `T.mono` (JetBrains Mono) are not
 * bundled — Android substitutes the system sans (Roboto Flex) and
 * system mono (Droid Sans Mono), which matches the README's
 * "system-ui" framing. Sizes are the ones the JSX uses inline.
 */
private val nextTypography: Typography
    get() {
        val sans = FontFamily.Default
        val mono = FontFamily.Monospace
        return Typography(
            // 24sp / 600 / -0.4 letterSpacing — onboarding H1 + settings header
            headlineSmall = TextStyle(
                fontFamily = sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.4).sp,
            ),
            // 22sp / 500 / -0.3 — settings sub-screen header
            headlineMedium = TextStyle(
                fontFamily = sans,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                letterSpacing = (-0.3).sp,
            ),
            // 17sp / 500 — main status hero title
            titleLarge = TextStyle(
                fontFamily = sans,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                lineHeight = 22.sp,
            ),
            // 16sp / 600 — top bar wordmark
            titleMedium = TextStyle(
                fontFamily = sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                letterSpacing = (-0.2).sp,
            ),
            // 14sp / 500 — settings row title, button title
            titleSmall = TextStyle(
                fontFamily = sans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
            // 14sp body
            bodyLarge = TextStyle(
                fontFamily = sans,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            // 13sp body / button label
            bodyMedium = TextStyle(
                fontFamily = sans,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            // 12sp helper / sub-text
            bodySmall = TextStyle(
                fontFamily = sans,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            // 11sp mono — mac addresses, timestamps
            labelMedium = TextStyle(
                fontFamily = mono,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.3.sp,
            ),
            // 10sp mono UPPERCASE — section labels in cards
            labelSmall = TextStyle(
                fontFamily = mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                letterSpacing = 1.4.sp,
            ),
        )
    }

@Composable
fun NextTheme(content: @Composable () -> Unit) {
    val br = BrColors.Default
    // Map mockup tokens onto M3 slots so Material 3 components (Button,
    // Card, etc) inherit the right colours. Slot meanings:
    //   primary           = brand accent (cyan)
    //   onPrimary         = dark text drawn on top of a primary button
    //   primaryContainer  = card background (bgElev1)
    //   onPrimaryContainer= primary text on a card
    //   secondary         = safe (success green) for chips and dots
    //   tertiary          = caution amber for warnings
    //   error             = danger red
    //   surface/background= bg (the screen background)
    //   surfaceVariant    = bgElev2 (nested cards / subdued surfaces)
    //   outline           = hairline2 (visible edge)
    //   outlineVariant    = hairline (subtle edge)
    val cs = darkColorScheme(
        primary             = br.brand,
        onPrimary           = br.bg,
        primaryContainer    = br.bgElev1,
        onPrimaryContainer  = br.fg,
        secondary           = br.safe,
        onSecondary         = br.bg,
        secondaryContainer  = br.bgElev1,
        onSecondaryContainer= br.fg,
        tertiary            = br.caution,
        onTertiary          = br.bg,
        tertiaryContainer   = br.bgElev1,
        onTertiaryContainer = br.fg,
        error               = br.danger,
        onError             = br.bg,
        errorContainer      = br.bgElev1,
        onErrorContainer    = br.fg,
        background          = br.bg,
        onBackground        = br.fg,
        surface             = br.bg,
        onSurface           = br.fg,
        surfaceVariant      = br.bgElev2,
        onSurfaceVariant    = br.fgMuted,
        outline             = br.hairline2,
        outlineVariant      = br.hairline,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalBrColors provides br,
        LocalBrShapes provides BrShapes.Default,
    ) {
        MaterialTheme(
            colorScheme = cs,
            typography = nextTypography,
            content = content,
        )
    }
}
