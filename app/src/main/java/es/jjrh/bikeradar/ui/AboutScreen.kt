// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BuildConfig
import es.jjrh.bikeradar.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavController) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            // painterResource can't inflate adaptive-icon XML (R.mipmap.ic_launcher
            // resolves to mipmap-anydpi-v26/ic_launcher.xml on API 26+ and throws
            // "Only VectorDrawables and rasterized asset types are supported").
            // The foreground PNG is designed with a baked-in white background
            // to match the launcher's adaptive-icon composition; mirror that
            // here with a rounded white tile so the logo reads the same on
            // the About screen's dark surface as it does on the launcher.
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "Bike Radar app icon",
                    modifier = Modifier.size(90.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Bike Radar", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Android companion for rear-bike-radar head units that speak " +
                    "the V2 BLE radar protocol used by current-generation hardware.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = {
                    ctx.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/partymola/android-bike-radar-overlay"),
                        )
                    )
                },
            ) { Text("github.com/partymola/android-bike-radar-overlay") }
            Text(
                "Licence: GPL-3.0-or-later",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
