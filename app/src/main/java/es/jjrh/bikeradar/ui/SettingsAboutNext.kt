// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BuildConfig

@Composable
fun SettingsAboutNext(navController: NavController) {
    NextTheme {
        SettingsAboutNextBody(navController)
    }
}

@Composable
private fun SettingsAboutNextBody(navController: NavController) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            NextSettingsHeader("About", onBack = { navController.popBackStack() })

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // BR icon tile — same launcher foreground used in the
                // top bar BrMark, scaled up. Renders the white-background
                // blue-letters logo.
                BrMark(size = 88.dp)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Bike Radar",
                    color = br.fg,
                    fontWeight = FontWeight.Medium,
                    fontSize = 24.sp,
                    letterSpacing = (-0.3).sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME} · GPL-3.0",
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = "Android companion for rear-bike-radar head units on the V2 BLE protocol.",
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
                        .clickable {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/partymola/android-bike-radar-overlay"))
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "github.com/partymola/android-bike-radar-overlay",
                        color = br.fgMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    )
                }
            }

            NextSettingsSectionLabel("Legal")
            NextSettingsRowGroup {
                NextSettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = br.fgMuted,
                    title = "Open source licences",
                    subtitle = "Every dependency the app ships with",
                    onClick = { navController.navigate("settings/licenses") },
                )
                NextSettingsRow(
                    icon = Icons.Default.Lock,
                    iconTint = br.fgMuted,
                    title = "Privacy",
                    subtitle = "What stays on the phone, what doesn't",
                    onClick = { navController.navigate("settings/privacy") },
                )
                NextSettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = br.fgMuted,
                    title = "Not affiliated with Garmin",
                    subtitle = "This is an independent tool",
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
