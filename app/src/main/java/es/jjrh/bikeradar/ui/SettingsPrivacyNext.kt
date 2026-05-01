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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

/**
 * Privacy summary. Bike Radar is a self-hosted companion: the app does
 * not collect anything for itself. Anything sensitive (HA URL, HA
 * token) is stored locally with hardware-backed encryption; anything
 * published over the network goes to the user's own Home Assistant
 * instance, configured by the user, and nowhere else.
 */
@Composable
fun SettingsPrivacyNext(navController: NavController) {
    NextTheme {
        SettingsPrivacyNextBody(navController)
    }
}

@Composable
private fun SettingsPrivacyNextBody(navController: NavController) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            NextSettingsHeader("Privacy", onBack = { navController.popBackStack() })
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                PrivacyP("Bike Radar is a self-hosted companion. The app collects no telemetry, runs no analytics, and sends no data to anyone other than the Home Assistant instance you configure.")

                PrivacySectionLabel("What stays on your phone")
                PrivacyP("Your settings (alert volume, alert distance, paired-radar status, dashcam preferences, and similar) live in Android's app-private storage and never leave the device.")
                PrivacyP("Your Home Assistant base URL and long-lived bearer token are encrypted at rest with a hardware-backed AES-256/GCM key from the Android Keystore. The encryption key never leaves the secure element. An attacker with raw filesystem access (e.g. `adb pull`) recovers only ciphertext.")
                PrivacyP("Per-ride capture logs (radar packets, BLE characteristic notifications) are written to the app's external files dir under `bike-radar-capture-*.log`. They live on your phone, not in any cloud, and are only shared if you tap Share in the Debug screen.")

                PrivacySectionLabel("What goes to your Home Assistant")
                PrivacyP("If you configure HA in Settings, the app publishes the radar's and the dashcam's battery levels to your HA instance via MQTT discovery, plus a close-pass event log if you enable it. These messages go directly to the HA URL you provided. Nothing is routed through the developer or any third party.")
                PrivacyP("You can stop publishing at any time by tapping Clear HA configuration in Settings → Home Assistant. Stored credentials are removed from the encrypted store immediately.")

                PrivacySectionLabel("Bluetooth")
                PrivacyP("Pairing happens in Android's system Bluetooth flow, not in this app. The app reads your bonded-device list to identify the radar and the dashcam. Bluetooth permissions are declared with `usesPermissionFlags=\"neverForLocation\"`, so Bluetooth scanning never derives location.")

                PrivacySectionLabel("Networking")
                PrivacyP("The only network destination the app contacts is the HA URL you provide. There are no analytics endpoints, crash reporters, ad networks, or third-party libraries that phone home.")
                PrivacyP("If your HA URL points outside your home network, the app requires HTTPS and refuses to send your bearer token in cleartext. Plain HTTP is accepted only for LAN destinations: private IPv4 ranges (10.x, 172.16-31.x, 192.168.x), loopback, IPv6 unique-local and link-local, and `.local` / `.lan` hostnames.")

                PrivacySectionLabel("Permissions")
                PrivacyP("BLUETOOTH_SCAN, BLUETOOTH_CONNECT (radar + dashcam), POST_NOTIFICATIONS (the foreground-service status notification), SYSTEM_ALERT_WINDOW (the radar overlay over your map app), and FOREGROUND_SERVICE (kept alive during rides). The screenshot-capture feature, off by default, additionally requests MediaProjection consent every time it starts.")

                PrivacySectionLabel("Source")
                PrivacyP("The app is open-source under GPL-3.0-or-later. The full source code is at github.com/partymola/android-bike-radar-overlay. Verify any of the above by reading the code.")

                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun PrivacyP(text: String) {
    val br = LocalBrColors.current
    Text(
        text = text,
        color = br.fgMuted,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun PrivacySectionLabel(text: String) {
    val br = LocalBrColors.current
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = text.uppercase(),
        color = br.fgDim,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}
