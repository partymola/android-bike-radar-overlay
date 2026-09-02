// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.access.RadarGrant
import es.jjrh.bikeradar.access.RadarGrantStore

/**
 * The screen as the app runs it, over the real store.
 *
 * The grants are re-read on every resume because the rider does not change
 * them here: they answer the consent screen in ANOTHER app's task and come
 * back to this one. Read once at composition, the list still shows what was
 * there before they answered, and the only way to see the truth is to leave
 * the screen and come back.
 */
@Composable
fun SettingsRadarAccessRoute(
    store: RadarGrantStore,
    onBack: () -> Unit,
) {
    var grants by remember(store) { mutableStateOf(store.all()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, store) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            grants = store.all()
        }
    }
    SettingsRadarAccessContent(
        grants = grants,
        onRevoke = { pkg ->
            // The Boolean is deliberately dropped. False means the store could
            // not be read, and the re-read below then yields an empty list, so
            // the screen shows nothing allowed. That agrees with the gate,
            // which denies every caller off the same unreadable file, so the
            // rider is not told a grant survives when nothing will honour it.
            store.revoke(pkg)
            grants = store.all()
        },
        onBack = onBack,
    )
}

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
    // Revoking is not reversible from here: the rider cannot re-allow an app
    // from this screen, only from the app itself, so a mis-tap costs them a
    // round trip through the other app.
    var pendingRevoke by remember { mutableStateOf<RadarGrant?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                            // Hiding the overlay needs a live registration and
                            // so needs READ as well, which the consent screen
                            // now says. A control-only grant here would be told
                            // it can do something that always fails, on the one
                            // screen the rider audits.
                            stringResource(
                                if (grant.read) {
                                    R.string.settings_radar_access_can_control
                                } else {
                                    R.string.settings_radar_access_can_control_light_only
                                },
                            ).takeIf { grant.control },
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
                        onAction = { pendingRevoke = grant },
                        isLast = index == grants.lastIndex,
                        actionIcon = Icons.Default.Delete,
                        actionTint = br.danger,
                        // The title is the other app's own label, so it is the
                        // one string on this screen nobody here chose.
                        titleMaxLines = 2,
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

    pendingRevoke?.let { grant ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = {
                Text(
                    stringResource(R.string.settings_radar_access_revoke_title, grant.label),
                    // An app picks its own label. This is legibility, not
                    // safety: measured, the title renders the same height with
                    // and without the cap, because Material3's AlertDialog
                    // scrolls its content and the buttons stay reachable
                    // either way. `theWayOutStaysReachableWithARidiculousName`
                    // pins the reachability, which is the part that matters.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = { Text(stringResource(R.string.settings_radar_access_revoke_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onRevoke(grant.packageName)
                    pendingRevoke = null
                }) {
                    // The bin that opens this dialog is danger red; a brand-coloured
                    // confirm would read as the safe choice of the two.
                    Text(stringResource(R.string.settings_radar_access_revoke), color = br.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
