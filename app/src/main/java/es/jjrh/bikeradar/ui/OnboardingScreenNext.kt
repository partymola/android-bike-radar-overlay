// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import es.jjrh.bikeradar.data.Prefs

/**
 * Stub for the redesigned Onboarding flow. Wired into the NavHost behind
 * the `nextUxOnboarding` Prefs flag. Surfaces a Finish button so a user
 * who flips the flag on at first launch can still complete onboarding;
 * the real 3-step pager lands in a follow-up commit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreenNext(prefs: Prefs, onFinished: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Welcome") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Onboarding · redesigned", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Placeholder. The redesigned 3-step onboarding pager will land here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onFinished) { Text("Finish") }
            OutlinedButton(onClick = { prefs.nextUxOnboarding = false }) {
                Text("Back to current Onboarding")
            }
        }
    }
}
