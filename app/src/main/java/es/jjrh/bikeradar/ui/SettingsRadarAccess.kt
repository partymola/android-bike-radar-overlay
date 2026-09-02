// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.access.RadarGrant

/**
 * What the rider has allowed other apps to do with the radar, and the way to
 * stop it.
 *
 * A leaf: the caller supplies the grants and handles a revoke, so the screen
 * can be rendered from a golden without a store behind it.
 */
@Composable
fun SettingsRadarAccessContent(
    grants: List<RadarGrant>,
    onRevoke: (packageName: String) -> Unit,
    onBack: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(br.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsHeader(
            title = stringResource(R.string.settings_home_radar_access_title),
            onBack = onBack,
        )

        if (grants.isEmpty()) {
            Text(
                stringResource(R.string.settings_radar_access_empty),
                color = br.fgMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            return@Column
        }

        SettingsRowGroup {
            grants.forEachIndexed { index, grant ->
                SettingsActionRow(
                    leadingIcon = if (grant.control) Icons.Default.FlashOn else Icons.Default.Visibility,
                    leadingTint = if (grant.control) br.caution else br.brand,
                    title = grant.label,
                    subtitle = listOfNotNull(
                        grant.packageName,
                        stringResource(R.string.settings_radar_access_can_read).takeIf { grant.read },
                        stringResource(R.string.settings_radar_access_can_control).takeIf { grant.control },
                        if (grant.lastUsedAtMs == 0L) {
                            stringResource(R.string.settings_radar_access_never_used)
                        } else {
                            stringResource(
                                R.string.settings_radar_access_last_used,
                                DateUtils.getRelativeTimeSpanString(
                                    grant.lastUsedAtMs,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString(),
                            )
                        },
                    ).joinToString(" · "),
                    actionLabel = stringResource(R.string.settings_radar_access_revoke),
                    onAction = { onRevoke(grant.packageName) },
                    isLast = index == grants.lastIndex,
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_radar_access_backup_note), color = br.fgMuted)
        }
    }
}
