// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

/**
 * Open-source licences for the dependencies the app ships with. Hand-
 * curated rather than via Google's `OssLicensesPlugin` because adding
 * the plugin pulls Play Services as a transitive dependency, which is
 * something an open-source app deliberately avoiding tracking should
 * not be doing. The list below mirrors `app/build.gradle.kts`'s
 * implementation deps, plus the language and platform.
 */
@Composable
fun SettingsLicensesNext(navController: NavController) {
    NextTheme {
        SettingsLicensesNextBody(navController)
    }
}

@Composable
private fun SettingsLicensesNextBody(navController: NavController) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            NextSettingsHeader("Open source licences", onBack = { navController.popBackStack() })

            Text(
                text = "Bike Radar is itself GPL-3.0-or-later. Every third-party " +
                    "library it depends on is listed below with its licence. " +
                    "All licences are compatible with GPL-3.0.",
                color = br.fgMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            NextSettingsSectionLabel("Language & runtime")
            LicenseGroup(
                listOf(
                    LicenseEntry("Kotlin", "JetBrains s.r.o. and contributors", "Apache 2.0"),
                    LicenseEntry("Kotlinx Coroutines", "JetBrains s.r.o. and contributors", "Apache 2.0"),
                ),
            )

            NextSettingsSectionLabel("Android platform")
            LicenseGroup(
                listOf(
                    LicenseEntry("AndroidX Core / AppCompat / Lifecycle", "The Android Open Source Project", "Apache 2.0"),
                    LicenseEntry("Activity Compose", "The Android Open Source Project", "Apache 2.0"),
                    LicenseEntry("Navigation Compose", "The Android Open Source Project", "Apache 2.0"),
                    LicenseEntry("Security Crypto", "The Android Open Source Project", "Apache 2.0"),
                    LicenseEntry("Material Components for Android", "Google", "Apache 2.0"),
                ),
            )

            NextSettingsSectionLabel("UI")
            LicenseGroup(
                listOf(
                    LicenseEntry("Jetpack Compose UI / Material 3 / Material Icons Extended", "The Android Open Source Project", "Apache 2.0"),
                ),
            )

            NextSettingsSectionLabel("Build & test")
            LicenseGroup(
                listOf(
                    LicenseEntry("Android Gradle Plugin", "Google", "Apache 2.0"),
                    LicenseEntry("Gradle", "Gradle Inc.", "Apache 2.0"),
                    LicenseEntry("JUnit 4", "JUnit team", "Eclipse Public License 1.0"),
                ),
            )

            NextSettingsSectionLabel("Audio assets")
            LicenseGroup(
                listOf(
                    LicenseEntry(
                        "Walk-away alarm tone (bicycle bell)",
                        "freesound_community on Pixabay (id 32072)",
                        "Pixabay Content License",
                    ),
                ),
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

private data class LicenseEntry(val name: String, val author: String, val licence: String)

@Composable
private fun LicenseGroup(entries: List<LicenseEntry>) {
    val br = LocalBrColors.current
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
