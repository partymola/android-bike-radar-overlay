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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.R

/**
 * Privacy summary. Bike Radar is a self-hosted companion: the app does
 * not collect anything for itself. Anything sensitive (HA URL, HA
 * token) is stored locally with hardware-backed encryption; anything
 * published over the network goes to the user's own Home Assistant
 * instance, configured by the user, and nowhere else.
 */
@Composable
fun SettingsPrivacy(navController: NavController) {
    UiTheme {
        SettingsPrivacyBody(navController)
    }
}

@Composable
private fun SettingsPrivacyBody(navController: NavController) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.settings_privacy_title), onBack = { navController.popBackStack() })
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                PrivacyP(stringResource(R.string.settings_privacy_intro))

                PrivacySectionLabel(stringResource(R.string.settings_privacy_on_phone_label))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_settings))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_rides))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_creds))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_capture))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_crashes))

                PrivacySectionLabel(stringResource(R.string.settings_privacy_to_ha_label))
                PrivacyP(stringResource(R.string.settings_privacy_to_ha_publish))
                PrivacyP(stringResource(R.string.settings_privacy_to_ha_stop))

                PrivacySectionLabel(stringResource(R.string.settings_privacy_bluetooth_label))
                PrivacyP(stringResource(R.string.settings_privacy_bluetooth_body))

                PrivacySectionLabel(stringResource(R.string.settings_privacy_networking_label))
                PrivacyP(stringResource(R.string.settings_privacy_networking_destination))
                PrivacyP(stringResource(R.string.settings_privacy_networking_https))

                PrivacySectionLabel(stringResource(R.string.settings_privacy_permissions_label))
                PrivacyP(stringResource(R.string.settings_privacy_permissions_body))

                PrivacySectionLabel(stringResource(R.string.settings_privacy_source_label))
                PrivacyP(stringResource(R.string.settings_privacy_source_body))

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
