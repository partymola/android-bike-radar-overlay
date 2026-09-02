// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.R

/**
 * Privacy summary. Bike Radar is a self-hosted companion: the app does
 * not collect anything for itself. Anything sensitive (HA URL, HA
 * token) is stored locally in the app's private storage, readable by no
 * other app; anything published over the network goes to the user's own
 * Home Assistant instance, configured by the user, and nowhere else.
 *
 * This is the one screen that keeps full substance rather than being trimmed
 * to bullets, because it is the disclosure a reader checks against the code.
 * That makes it long, so the layout carries the load instead: a card per
 * subject with a heading a reader can scan for, at a size meant for reading
 * rather than for a settings label.
 *
 * `scripts/privacy-disclosure-check.sh` does NOT cover this file: it reads
 * `strings.xml`, the manifest and the MQTT anchor, so it proves the strings
 * exist and agree with the code, not that anything here renders them. Deleting
 * a [PrivacyP] call leaves its string in place and that gate still passes, and
 * the single golden covers only the top of a screen taller than a viewport.
 * `SettingsPrivacyRendersEveryDisclosureTest` is what closes it, by reading
 * this file and requiring every `settings_privacy_*` key to appear.
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

            Text(
                text = stringResource(R.string.settings_privacy_intro),
                color = br.fg,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )

            PrivacySection(Icons.Default.PhoneAndroid, R.string.settings_privacy_on_phone_label) {
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_settings))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_location))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_rides))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_creds))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_capture))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_crashes))
                PrivacyP(stringResource(R.string.settings_privacy_on_phone_linklog))
            }

            PrivacySection(Icons.Default.Home, R.string.settings_privacy_to_ha_label) {
                PrivacyP(stringResource(R.string.settings_privacy_to_ha_publish))
                PrivacyP(stringResource(R.string.settings_privacy_to_ha_stop))
            }

            PrivacySection(Icons.Default.Apps, R.string.settings_privacy_to_apps_label) {
                PrivacyP(stringResource(R.string.settings_privacy_to_apps_body))
            }

            PrivacySection(Icons.Default.Bluetooth, R.string.settings_privacy_bluetooth_label) {
                PrivacyP(stringResource(R.string.settings_privacy_bluetooth_body))
            }

            PrivacySection(Icons.Default.Lock, R.string.settings_privacy_networking_label) {
                PrivacyP(stringResource(R.string.settings_privacy_networking_destination))
                PrivacyP(stringResource(R.string.settings_privacy_networking_https))
            }

            PrivacySection(Icons.Default.VerifiedUser, R.string.settings_privacy_permissions_label) {
                PrivacyP(stringResource(R.string.settings_privacy_permissions_body))
            }

            PrivacySection(Icons.Default.Code, R.string.settings_privacy_source_label) {
                PrivacyP(stringResource(R.string.settings_privacy_source_body))
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** A heading a reader can scan for, and its paragraphs boxed off from the next subject. */
@Composable
private fun PrivacySection(
    icon: ImageVector,
    @StringRes labelRes: Int,
    body: @Composable () -> Unit,
) {
    val br = LocalBrColors.current
    Spacer(modifier = Modifier.height(18.dp))
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = br.brand,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(labelRes),
            color = br.fg,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(br.bgElev1)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        body()
    }
}

@Composable
private fun PrivacyP(text: String) {
    val br = LocalBrColors.current
    Text(
        text = text,
        color = br.fgMuted,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
