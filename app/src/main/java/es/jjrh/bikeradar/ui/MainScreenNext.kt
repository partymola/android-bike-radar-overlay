// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import es.jjrh.bikeradar.data.Prefs

/**
 * Stub for the redesigned Main screen. Wired into the NavHost behind the
 * `nextUxMain` Prefs flag so it can be toggled on / off via Debug. The
 * actual redesign per the design handoff lands in follow-up commits;
 * this file exists so the toggle plumbing is testable end-to-end.
 *
 * Stubs include an in-screen "Back to current" button because Main is
 * the start destination and has no back arrow — without the button a
 * user who flips the flag on has no way to navigate to Debug (the
 * placeholder has no menu) and would be stuck. Real redesigned screens
 * will not need this emergency exit; the flag stays in Debug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenNext(navController: NavController, prefs: Prefs) {
    Scaffold(topBar = { TopAppBar(title = { Text("Bike Radar") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Main · redesigned", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Placeholder. The redesigned Main screen will land here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { prefs.nextUxMain = false }) {
                Text("Back to current Main")
            }
        }
    }
}
