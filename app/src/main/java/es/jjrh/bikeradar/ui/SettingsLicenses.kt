// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import es.jjrh.bikeradar.R

/**
 * Open-source licences for the app's direct dependencies. Hand-
 * curated rather than via Google's `OssLicensesPlugin` because adding
 * the plugin pulls Play Services as a transitive dependency, which is
 * something an open-source app deliberately avoiding tracking should
 * not be doing. The list below mirrors `app/build.gradle.kts`'s
 * implementation deps, plus the language and platform.
 *
 * Scope is the app's DIRECT dependencies, and the screen says so. Build and
 * test tooling is absent because it is never combined into the distributed
 * work, and listing it would put the screen's own "all compatible with
 * GPL-3.0" claim in the wrong - JUnit 4 is EPL 1.0, which the FSF holds
 * GPL-incompatible.
 *
 * `SettingsLicencesCoverageTest` pins both halves of the claim, but only
 * over what `app/build.gradle.kts` DECLARES, and only over the lists
 * below - an entry added inline in the composable body would render
 * unchecked. The APK also packages transitive libraries no entry names:
 * androidx.emoji2, recyclerview and kotlinx-serialization among them. So
 * do NOT widen the wording to everything the app ships, which is a claim
 * nothing here can back.
 *
 * Closing that gap needs the built artifact, not a longer hand-curated
 * list. Note the obvious route is only a partial one: the per-library
 * version files AGP packages into META-INF cover AndroidX-style libraries
 * and miss others, kotlinx-serialization included.
 */
@Composable
fun SettingsLicenses(navController: NavController) {
    UiTheme {
        SettingsLicensesBody(navController)
    }
}

@Composable
private fun SettingsLicensesBody(navController: NavController) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.settings_licenses_title), onBack = { navController.popBackStack() })

            Text(
                text = stringResource(R.string.settings_licenses_intro),
                color = br.fgMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            SettingsSectionLabel(stringResource(R.string.settings_licenses_section_language_runtime))
            LicenseGroup(LANGUAGE_RUNTIME_LICENCES)

            SettingsSectionLabel(stringResource(R.string.settings_licenses_section_android_platform))
            LicenseGroup(ANDROID_PLATFORM_LICENCES)

            SettingsSectionLabel(stringResource(R.string.settings_licenses_section_ui))
            LicenseGroup(UI_LICENCES)

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/**
 * [url] is the licence text, so a row can answer "what am I agreeing to".
 *
 * It has no default deliberately. A default would be one licence's text, and
 * an entry added under a different licence without its own url would then link
 * the rider to the wrong text on the one screen where that is a legal
 * statement, with nothing failing.
 */
internal data class LicenseEntry(
    val name: String,
    val author: String,
    val licence: String,
    val url: String,
)

internal const val APACHE_2_URL = "https://www.apache.org/licenses/LICENSE-2.0"

internal val LANGUAGE_RUNTIME_LICENCES = listOf(
    LicenseEntry("Kotlin", "JetBrains s.r.o. and contributors", "Apache 2.0", APACHE_2_URL),
    LicenseEntry("Kotlinx Coroutines", "JetBrains s.r.o. and contributors", "Apache 2.0", APACHE_2_URL),
)

internal val ANDROID_PLATFORM_LICENCES = listOf(
    LicenseEntry("AndroidX Core / AppCompat / Lifecycle", "The Android Open Source Project", "Apache 2.0", APACHE_2_URL),
    LicenseEntry("Activity Compose", "The Android Open Source Project", "Apache 2.0", APACHE_2_URL),
    LicenseEntry("Navigation Compose", "The Android Open Source Project", "Apache 2.0", APACHE_2_URL),
    LicenseEntry("Material Components for Android", "Google", "Apache 2.0", APACHE_2_URL),
)

internal val UI_LICENCES = listOf(
    LicenseEntry(
        "Jetpack Compose UI / Material 3 / Material Icons Extended",
        "The Android Open Source Project",
        "Apache 2.0",
        APACHE_2_URL,
    ),
)

@Composable
private fun LicenseGroup(entries: List<LicenseEntry>) {
    val br = LocalBrColors.current
    val ctx = LocalContext.current
    val openLabel = stringResource(R.string.settings_licenses_open_action)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(br.bgElev1),
    ) {
        for ((i, entry) in entries.withIndex()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The label is the ACTION, not the destination: TalkBack
                    // reads it as "double tap to <label>", so the licence name
                    // alone announces as "double tap to Apache 2.0".
                    .clickable(onClickLabel = openLabel) { openLink(ctx, entry.url) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entry.name, color = br.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = entry.author,
                        color = br.fgDim,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = entry.licence,
                    color = br.brand,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Without this the row responds to a tap that nothing invited,
                // which reads as a misfire rather than a link.
                LeavesAppGlyph()
            }
            if (i < entries.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(horizontal = 20.dp)
                        .background(br.hairline),
                )
            }
        }
    }
}
