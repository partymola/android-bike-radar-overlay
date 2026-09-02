// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BuildConfig
import es.jjrh.bikeradar.R

internal const val REPO_URL = "https://github.com/partymola/android-bike-radar-overlay"
internal const val RELEASES_URL = "$REPO_URL/releases"

/** The licence text itself, which is the thing a reader of "GPL-3.0" wants. */
internal const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

@Composable
fun SettingsAbout(navController: NavController) {
    val ctx = LocalContext.current
    UiTheme {
        SettingsAboutBody(navController) { openLink(ctx, it) }
    }
}

/**
 * [onOpenUrl] is injected so which address each row opens can be pinned.
 * Reading it off a launched intent cannot be done from a Compose test here,
 * and the goldens see only the labels, so swapping two of these arguments
 * would otherwise pass every gate while "This app's licence" opened the
 * release notes.
 */
@Composable
internal fun SettingsAboutBody(
    navController: NavController,
    onOpenUrl: (String) -> Unit,
) {
    val br = LocalBrColors.current

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.settings_about_title), onBack = { navController.popBackStack() })

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // BR mark - same launcher foreground used in the top bar
                // BrMark, scaled up. Blue letters render directly on the
                // screen surface (the asset background is transparent).
                BrMark(size = 88.dp)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.settings_about_app_name),
                    color = br.fg,
                    fontWeight = FontWeight.Medium,
                    fontSize = 24.sp,
                    letterSpacing = (-0.3).sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_version_line, BuildConfig.VERSION_NAME),
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.settings_about_tagline),
                    color = br.fgMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(br.bgElev1)
                        .border(1.dp, br.hairline, RoundedCornerShape(999.dp))
                        .clickable { onOpenUrl(REPO_URL) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = REPO_URL.removePrefix("https://"),
                        color = br.fgMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    )
                }
            }

            SettingsSectionLabel(stringResource(R.string.settings_about_section_release))
            SettingsRowGroup {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Article,
                    iconTint = br.fgMuted,
                    title = stringResource(R.string.settings_about_changelog_title),
                    subtitle = stringResource(R.string.settings_about_changelog_subtitle),
                    onClick = { onOpenUrl(RELEASES_URL) },
                    chevron = false,
                    rightContent = { LeavesAppGlyph() },
                    isLast = true,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_about_section_legal))
            SettingsRowGroup {
                SettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = br.fgMuted,
                    title = stringResource(R.string.settings_about_licences_title),
                    subtitle = stringResource(R.string.settings_about_licences_subtitle),
                    onClick = { navController.navigate("settings/licenses") },
                )
                SettingsRow(
                    icon = Icons.Default.Description,
                    iconTint = br.fgMuted,
                    title = stringResource(R.string.settings_about_licence_title),
                    subtitle = stringResource(R.string.settings_about_licence_subtitle),
                    onClick = { onOpenUrl(GPL_URL) },
                    chevron = false,
                    rightContent = { LeavesAppGlyph() },
                )
                SettingsRow(
                    icon = Icons.Default.Lock,
                    iconTint = br.fgMuted,
                    title = stringResource(R.string.settings_about_privacy_title),
                    subtitle = stringResource(R.string.settings_about_privacy_subtitle),
                    onClick = { navController.navigate("settings/privacy") },
                )
                SettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = br.fgMuted,
                    title = stringResource(R.string.settings_about_unaffiliated_title),
                    subtitle = stringResource(R.string.settings_about_unaffiliated_subtitle),
                    onClick = {},
                    chevron = false,
                    clickable = false,
                    isLast = true,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
