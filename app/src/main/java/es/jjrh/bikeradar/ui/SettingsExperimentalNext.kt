// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.data.Prefs

@Composable
fun SettingsExperimentalNext(navController: NavController, prefs: Prefs) {
    NextTheme {
        SettingsExperimentalNextBody(navController, prefs)
    }
}

@Composable
private fun SettingsExperimentalNextBody(navController: NavController, prefs: Prefs) {
    val br = LocalBrColors.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            NextSettingsHeader("Experimental", onBack = { navController.popBackStack() })

            Text(
                text = "Features still being tested. May be jittery or change without notice.",
                color = br.fgDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))
            NextSettingsRowGroup {
                NextSettingsToggleRow(
                    leadingIcon = Icons.Default.FlashOn,
                    leadingTint = br.brand,
                    title = "Predict overtake paths (1 s lookahead)",
                    subtitle = "Render each vehicle 1 s into the future — see where overtakers are heading, not just where they are. Can look jittery in noisy traffic.",
                    checked = prefsSnap.precogEnabled,
                    onCheckedChange = { prefs.precogEnabled = it },
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
