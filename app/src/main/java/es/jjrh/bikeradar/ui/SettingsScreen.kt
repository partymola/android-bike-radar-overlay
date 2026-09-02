// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.DeviceNameMatcher
import es.jjrh.bikeradar.EBikeStage
import es.jjrh.bikeradar.EBikeStateBus
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.HaStatus
import es.jjrh.bikeradar.HaStatusDeriver
import es.jjrh.bikeradar.PermissionsSummary
import es.jjrh.bikeradar.PermissionsSummaryDeriver
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarLinkState
import es.jjrh.bikeradar.RadarLinkStatus
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.access.PrefsRadarGrantStore
import es.jjrh.bikeradar.access.RadarAccessSummary
import es.jjrh.bikeradar.access.RadarGrant
import es.jjrh.bikeradar.batteryReadIsFresh
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.eBikeDataIsFresh
import es.jjrh.bikeradar.radarStreamIsLive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import android.provider.Settings as AndroidSettings

/**
 * Settings home. NavHost-routed home that links to per-section sub-screens,
 * matching `settings-screens.jsx`'s `SettingsHome` composition.
 *
 * Top: SettingsHeader with chevron-back.
 * Then: Quick Status card (compressed Radar + Cam status).
 * Then: RIDE (Alerts, Light auto-mode), CONNECTIONS (Dashcam, eBike,
 *   Home Assistant), SYSTEM (Permissions, Experimental, Debug, About).
 *
 * Each row navigates to its own sub-screen.
 */
@Composable
fun SettingsScreen(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsScreenBody(navController, prefs)
    }
}

@Composable
private fun SettingsScreenBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val devUnlocked by DevModeState.unlocked.collectAsState()
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val haHealth by HaHealthBus.state.collectAsState()
    val batteryEntries by BatteryStateBus.entries.collectAsState()
    val creds = remember { HaCredentials(ctx) }
    val haConfigured = creds.baseUrl.isNotBlank() && creds.token.isNotBlank()

    // Re-read on a tick so a device that drops while this screen is open stops
    // reporting as connected: the entries flow only emits when a device IS
    // seen, so without this the last verdict would stand indefinitely.
    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        // RESUMED-gated: this screen sits in the backstack behind its
        // sub-screens, and an ungated loop keeps recomposing there.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Resume-first, then loop. The gated loop restarts on RESUME
            // and would otherwise delay BEFORE its first assignment, so a
            // screen left open across an hour of standby would difference
            // two hour-old values, get a small number, and render a dead
            // device as connected for the first five seconds back.
            tickNowMs = System.currentTimeMillis()
            while (true) {
                delay(5_000)
                tickNowMs = System.currentTimeMillis()
            }
        }
    }

    // Re-read on resume rather than held in state: a grant can be added by the
    // consent screen in another task while this one sits in the backstack.
    val radarGrants = remember(tickNowMs) {
        PrefsRadarGrantStore(
            ctx.getSharedPreferences(PrefsRadarGrantStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        ).all()
    }

    // Stale entries are dropped rather than rendered: BatteryStateBus is never
    // cleared in production, so an entry survives long after the device is off,
    // and every consumer below treats "present" as "connected".
    val radarBattery: BatteryEntry? = batteryEntries.values.firstOrNull { entry ->
        DeviceNameMatcher.isRadarName(entry.name)
    }?.takeIf { batteryReadIsFresh(it.readAtMs, tickNowMs) }
    val dashcamSlug = prefsSnap.dashcamMac?.let { mac ->
        BikeRadarService.macToSlug[mac]
            ?: BikeRadarService.macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefsSnap.dashcamDisplayName?.let { BikeRadarService.slug(it) }
    }
    val dashcamBattery = dashcamSlug?.let { batteryEntries[it] }
        ?.takeIf { batteryReadIsFresh(it.readAtMs, tickNowMs) }

    // The radar is scored on decoded frames, not on the battery entry above.
    // This screen used to answer "is the radar there" from a battery read
    // alone, which the radar's own screen already refuses to do: the setup
    // sequence publishes a reading on every attempt that reaches its battery
    // step, so an aborting radar keeps one permanently fresh while sending no
    // targets. The battery is still rendered, it just no longer decides.
    val radarState by RadarStateBus.state.collectAsState()
    val noRadarLinkFlow = remember { MutableStateFlow(RadarLinkState()) }
    val radarLinkSnap by (BikeRadarService.radarLinkStateForUi ?: noRadarLinkFlow).collectAsState()
    // Polled, not read on every recomposition: this body recomposes on each
    // decoded radar frame, and `bondedDevices` is a binder round-trip to the
    // Bluetooth process. The 5 s cadence matches the home screen's.
    var radarSelected by remember { mutableStateOf(radarIsSelected(ctx, prefs.radarMac)) }
    var btEnabled by remember { mutableStateOf(bluetoothIsOn(ctx)) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Resume-first, then loop, like the tick above. A delay-first loop
            // leaves the Bluetooth-off banner and both "Not paired" rows
            // standing for five seconds after the rider comes back from the
            // system Bluetooth screen the banner itself sent them to.
            radarSelected = radarIsSelected(ctx, prefs.radarMac)
            btEnabled = bluetoothIsOn(ctx)
            while (true) {
                delay(5_000)
                radarSelected = radarIsSelected(ctx, prefs.radarMac)
                btEnabled = bluetoothIsOn(ctx)
            }
        }
    }

    val radarLink = deviceLinkState(
        // Bonded AND the transport up, per deviceLinkState's contract. The
        // radar cannot opt out of the transport half even if we wanted it to:
        // `getBondedDevices` returns an empty set with the adapter off, so
        // `radarSelected` is already false there.
        linked = btEnabled && radarSelected,
        fresh = radarStreamIsLive(radarState, tickNowMs),
        limited = radarState.source == DataSource.V1,
        connecting = RadarLinkStatus.isConnecting(
            gattActive = radarLinkSnap.radarGattActive,
            offSinceMs = radarLinkSnap.radarOffSinceMs,
            nowMs = SystemClock.elapsedRealtime(),
        ),
    )
    val dashcamLink = deviceLinkState(
        // The camera's pairing lives in prefs, which outlive the radio, so
        // the transport half is spelled out here to match the radar rather
        // than letting the two rows give one cause two different words.
        linked = btEnabled &&
            prefsSnap.dashcamOwnership == DashcamOwnership.YES &&
            prefsSnap.dashcamMac != null,
        fresh = dashcamBattery != null,
    )
    val ebikeLastUpdated by EBikeStateBus.lastUpdatedElapsedMs.collectAsState()
    val ebikeStage by EBikeStateBus.stage.collectAsState()

    val grantedCount = PERMISSIONS.count { isSpecGranted(ctx, it) }
    val requiredMissing = PERMISSIONS.count { it.required && !isSpecGranted(ctx, it) }

    SettingsMenuBody(
        navController = navController,
        devUnlocked = devUnlocked,
        prefsSnap = prefsSnap,
        btEnabled = btEnabled,
        radarLink = radarLink,
        dashcamLink = dashcamLink,
        radarBattery = radarBattery,
        dashcamBattery = dashcamBattery,
        ebikeReceiving = eBikeDataIsFresh(ebikeLastUpdated, SystemClock.elapsedRealtime()),
        ebikeStage = ebikeStage,
        haConfigured = haConfigured,
        haHealth = haHealth,
        permissionsGrantedCount = grantedCount,
        permissionsRequiredMissing = requiredMissing,
        permissionsTotal = PERMISSIONS.size,
        radarGrants = radarGrants,
    )
}

/**
 * Stateless leaf — renders the Settings home menu from already-derived
 * state. Visible to snapshot tests so the visual contract can be locked
 * without `LocalContext`, the radar/HA/battery buses, or `HaCredentials`.
 */
@Composable
internal fun SettingsMenuBody(
    navController: NavController,
    devUnlocked: Boolean,
    prefsSnap: es.jjrh.bikeradar.data.PrefsSnapshot,
    /** Radio off makes both radio-backed rows read "Not paired", which is what
     *  `linked` means: bonded AND the transport up. The banner names the cause
     *  once, so the word is never left unexplained; the home screen shows the
     *  same one, and suppresses it when its hero already says Bluetooth is
     *  off. */
    btEnabled: Boolean,
    /** Classified by the same [deviceLinkState] the home System card uses, so
     *  the quick-status chip, the Connections row and the home row cannot
     *  give one device three different words. */
    radarLink: DeviceLinkState,
    dashcamLink: DeviceLinkState,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    ebikeReceiving: Boolean,
    ebikeStage: EBikeStage,
    haConfigured: Boolean,
    haHealth: HaHealth,
    permissionsGrantedCount: Int,
    permissionsRequiredMissing: Int,
    permissionsTotal: Int,
    radarGrants: List<RadarGrant>,
) {
    val radarAccessSummary = RadarAccessSummary.of(radarGrants)
    val br = LocalBrColors.current
    val ctx = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(title = stringResource(R.string.common_settings), onBack = { navController.popBackStack() })

            if (!btEnabled) {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    BluetoothOffBanner(
                        onTap = {
                            ctx.startActivity(
                                Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    )
                }
            }

            // System health card (the small one at the top of Settings)
            SystemHealthBar(
                radarLink = radarLink,
                dashcamLink = dashcamLink,
                // A rider who answered "I don't have one" has nothing to say
                // about a camera, and "Not paired" invites them to pair a
                // device they told us does not exist.
                showDashcam = prefsSnap.dashcamOwnership != DashcamOwnership.NO,
                radarBattery = radarBattery,
                dashcamBattery = dashcamBattery,
                batteryLowThresholdPct = prefsSnap.batteryLowThresholdPct,
            )

            SettingsSectionLabel(stringResource(R.string.settings_home_section_ride))
            SettingsRowGroup {
                SettingsRow(
                    icon = Icons.Default.Notifications,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_alerts_title),
                    subtitle = stringResource(
                        R.string.settings_home_alerts_subtitle,
                        prefsSnap.alertVolume,
                        prefsSnap.alertMaxDistanceM,
                    ),
                    onClick = { navController.navigate("settings/radar") },
                )
                SettingsRow(
                    icon = Icons.Default.WbSunny,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_lights_title),
                    subtitle = lightsSubtitle(ctx, prefsSnap),
                    onClick = { navController.navigate("settings/lights") },
                    isLast = true,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_home_section_connections))
            SettingsRowGroup {
                SettingsRow(
                    icon = Icons.Default.Sensors,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_radar_title),
                    subtitle = connectionSubtitle(
                        ctx,
                        setUp = R.string.settings_home_conn_paired
                            .takeIf { radarLink != DeviceLinkState.NOT_PAIRED },
                        status = radarLinkLabel(radarLink),
                    ),
                    onClick = { navController.navigate("settings/radar-device") },
                )
                SettingsRow(
                    icon = Icons.Default.Videocam,
                    iconTint = br.dashcam,
                    title = stringResource(R.string.settings_home_dashcam_title),
                    subtitle = dashcamSetupPrompt(ctx, prefsSnap) ?: connectionSubtitle(
                        ctx,
                        // Guarded like the radar row even though the prompt
                        // above already covers every NOT_PAIRED case today.
                        // Unguarded, one change to what feeds `linked` yields
                        // "Paired · Not paired", and the asymmetry is what
                        // made that reachable once already.
                        setUp = R.string.settings_home_conn_paired_cam
                            .takeIf { dashcamLink != DeviceLinkState.NOT_PAIRED },
                        status = dashcamLinkLabel(dashcamLink),
                    ),
                    onClick = { navController.navigate("settings/dashcam") },
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.DirectionsBike,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_ebike_title),
                    subtitle = eBikeSetupPrompt(ctx, prefsSnap) ?: connectionSubtitle(
                        ctx,
                        setUp = R.string.settings_home_ebike_on,
                        status = ebikeStatusLabel(ebikeReceiving, ebikeStage),
                    ),
                    onClick = { navController.navigate("settings/ebike") },
                )
                SettingsRow(
                    icon = Icons.Default.Home,
                    // Green only when it IS ready: this column's other status
                    // colour, on the shield below, means "all granted", so a
                    // green house beside "Not configured" reads as fine.
                    iconTint = when (HaStatusDeriver.derive(haConfigured, haHealth)) {
                        HaStatus.READY -> br.safe
                        HaStatus.UNREACHABLE -> br.caution
                        HaStatus.CONFIGURED, HaStatus.NOT_CONFIGURED -> br.fgDim
                    },
                    title = stringResource(R.string.settings_home_ha_title),
                    subtitle = haSubtitle(ctx, haConfigured, haHealth),
                    onClick = { navController.navigate("settings/ha") },
                    isLast = true,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_home_section_system))
            SettingsRowGroup {
                val permissionsSummary = PermissionsSummaryDeriver.derive(
                    grantedCount = permissionsGrantedCount,
                    requiredMissing = permissionsRequiredMissing,
                    total = permissionsTotal,
                )
                SettingsRow(
                    icon = Icons.Default.Shield,
                    // Tinted from the same three states the subtitle reads, so
                    // the colour cannot say "attention" while the text says
                    // everything is granted.
                    iconTint = when (permissionsSummary) {
                        PermissionsSummary.ACTION_NEEDED -> br.danger
                        PermissionsSummary.PARTIALLY_GRANTED -> br.caution
                        PermissionsSummary.ALL_GRANTED -> br.safe
                    },
                    title = stringResource(R.string.settings_home_permissions_title),
                    subtitle = when (permissionsSummary) {
                        // Counts required-missing only, with no denominator:
                        // pairing it with the all-permissions total read "1 of
                        // 4 need action" while three were outstanding.
                        PermissionsSummary.ACTION_NEEDED -> pluralStringResource(
                            R.plurals.settings_home_permissions_subtitle_action,
                            permissionsRequiredMissing,
                            permissionsRequiredMissing,
                        )
                        PermissionsSummary.PARTIALLY_GRANTED -> pluralStringResource(
                            R.plurals.settings_home_permissions_subtitle_partial,
                            permissionsGrantedCount,
                            permissionsGrantedCount,
                            permissionsTotal,
                        )
                        PermissionsSummary.ALL_GRANTED -> stringResource(
                            R.string.settings_home_permissions_subtitle_granted,
                            permissionsGrantedCount,
                            permissionsTotal,
                        )
                    },
                    onClick = { navController.navigate("settings/permissions") },
                )
                SettingsRow(
                    icon = Icons.Default.Share,
                    // The tint follows the highest trust given, so an app that
                    // can switch the tail light off does not look like one that
                    // can only watch.
                    iconTint = when (radarAccessSummary) {
                        RadarAccessSummary.CONTROLLING -> br.caution
                        RadarAccessSummary.READING -> br.brand
                        RadarAccessSummary.NONE -> br.fgDim
                    },
                    title = stringResource(R.string.settings_home_radar_access_title),
                    // Both counts when any app can control, because reporting
                    // only the controlling one hides every app that can watch.
                    subtitle = when (radarAccessSummary) {
                        RadarAccessSummary.CONTROLLING -> {
                            val controlling = radarGrants.count { it.control }
                            val reading = radarGrants.count { it.read }
                            pluralStringResource(
                                R.plurals.settings_radar_access_reading,
                                reading,
                                reading,
                            ) + " · " + pluralStringResource(
                                R.plurals.settings_radar_access_controlling,
                                controlling,
                                controlling,
                            )
                        }
                        RadarAccessSummary.READING -> {
                            val reading = radarGrants.count { it.read }
                            pluralStringResource(R.plurals.settings_radar_access_reading, reading, reading)
                        }
                        RadarAccessSummary.NONE -> stringResource(R.string.settings_radar_access_none)
                    },
                    onClick = { navController.navigate("settings/radar-access") },
                )
                SettingsRow(
                    icon = Icons.Default.FlashOn,
                    iconTint = br.brand,
                    title = stringResource(R.string.settings_home_experimental_title),
                    subtitle = experimentalSubtitle(ctx, prefsSnap),
                    onClick = { navController.navigate("settings/experimental") },
                )
                if (devUnlocked) {
                    SettingsRow(
                        icon = Icons.Default.Terminal,
                        iconTint = br.fgMuted,
                        title = stringResource(R.string.settings_home_debug_title),
                        subtitle = stringResource(R.string.settings_home_debug_subtitle),
                        onClick = { navController.navigate("debug") },
                    )
                }
                SettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = br.fgMuted,
                    title = stringResource(R.string.settings_home_about_title),
                    subtitle = stringResource(
                        R.string.settings_home_about_subtitle,
                        es.jjrh.bikeradar.BuildConfig.VERSION_NAME,
                    ),
                    onClick = { navController.navigate("settings/about") },
                    isLast = true,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SystemHealthBar(
    radarLink: DeviceLinkState,
    dashcamLink: DeviceLinkState,
    showDashcam: Boolean,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    batteryLowThresholdPct: Int,
) {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        SectionLabel(stringResource(R.string.settings_home_quick_status))
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemHealthChip(
                label = stringResource(R.string.settings_home_chip_radar),
                status = stringResource(radarLinkLabel(radarLink)),
                battery = radarBattery?.takeIf { radarLink.isDelivering },
                batteryLowThresholdPct = batteryLowThresholdPct,
                color = chipColour(radarLink, br),
                modifier = Modifier.weight(1f),
            )
            if (showDashcam) {
                SystemHealthChip(
                    label = stringResource(R.string.settings_home_chip_cam),
                    status = stringResource(dashcamLinkLabel(dashcamLink)),
                    battery = dashcamBattery?.takeIf { dashcamLink.isDelivering },
                    batteryLowThresholdPct = batteryLowThresholdPct,
                    color = chipColour(dashcamLink, br),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Same colour rule as the home System row and the device screens: grey only
 * for a device that is not set up, amber for one that is set up and not
 * delivering. The chip used grey for both, so it said "nothing to see here"
 * about a radar that had dropped mid-ride.
 */
private fun chipColour(link: DeviceLinkState, br: BrColors) = when (link) {
    DeviceLinkState.NOT_PAIRED -> br.fgDim
    DeviceLinkState.LIVE -> br.safe
    else -> br.caution
}

@Composable
private fun SystemHealthChip(
    label: String,
    status: String,
    battery: BatteryEntry?,
    batteryLowThresholdPct: Int,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val br = LocalBrColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = color, size = 6.dp)
        Text(text = label, color = br.fgMuted, fontSize = 12.sp)
        // The state is always named, never left to the dot, and it is the
        // same word the home row and the device's own screen use. The chip
        // previously said "Not seen" for what those two called "No signal".
        Text(
            text = status,
            color = br.fgFaint,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (battery != null) {
            BatteryChip(pct = battery.pct, lowThresholdPct = batteryLowThresholdPct)
        }
    }
}

/**
 * A Connections row answers two questions: is this device set up, and is it
 * working right now. [setUp] is the first half and null when it is not set up
 * yet, in which case that fact is the whole answer and there is no link to
 * describe. [status] is the second half, and it is the shared vocabulary, so
 * the row, the quick-status chip above it and the device's own screen all use
 * one word.
 *
 * Battery is deliberately absent: the quick-status chips at the top of this
 * same screen already carry it, and the row previously said "Connected · 85%"
 * a finger's width below a chip saying the same percentage.
 */
internal fun connectionSubtitle(ctx: Context, @StringRes setUp: Int?, @StringRes status: Int): String = if (setUp == null) {
    ctx.getString(status)
} else {
    ctx.getString(R.string.settings_home_conn_two_part, ctx.getString(setUp), ctx.getString(status))
}

// One row now summarises both lights. Radar-first (matches the consolidated
// screen's section order); fold in dashcam ownership so a rider with no
// dashcam isn't told about a front light they can't use.
internal fun lightsSubtitle(ctx: Context, snap: es.jjrh.bikeradar.data.PrefsSnapshot): String {
    val rear = ctx.getString(
        if (snap.radarLightAutoModeEnabled) R.string.settings_home_lights_on else R.string.settings_home_lights_off,
    )
    val hasFront = snap.dashcamOwnership == DashcamOwnership.YES
    if (!hasFront) return ctx.getString(R.string.settings_home_lights_rear_only, rear)
    val front = ctx.getString(
        if (snap.autoLightModeEnabled) R.string.settings_home_lights_on else R.string.settings_home_lights_off,
    )
    return if (!snap.radarLightAutoModeEnabled && !snap.autoLightModeEnabled) {
        ctx.getString(R.string.settings_home_lights_all_off)
    } else {
        ctx.getString(R.string.settings_home_lights_summary, rear, front)
    }
}

/**
 * What to do about a device that is not set up yet, or null once it is and a
 * link state is the honest answer.
 *
 * These are instructions, not statuses, which is why they are allowed to
 * differ from the shared vocabulary. The line they replaced was not: a paired
 * camera read "Front cam · paired" here while the home row called the same
 * camera "No signal", so the row answered a question about pairing that the
 * rider had not asked.
 */
private fun eBikeSetupPrompt(ctx: Context, snap: es.jjrh.bikeradar.data.PrefsSnapshot): String? = when (snap.eBikeOwnership) {
    es.jjrh.bikeradar.data.EBikeOwnership.UNANSWERED -> ctx.getString(R.string.settings_home_ebike_setup)
    es.jjrh.bikeradar.data.EBikeOwnership.NO -> ctx.getString(R.string.settings_home_dont_have_one)
    // The feature switch, not the link: with it off nothing is being asked of
    // the bike, so "Waiting for Flow" would blame Flow for the rider's choice.
    es.jjrh.bikeradar.data.EBikeOwnership.YES ->
        if (snap.eBikeDataEnabled) null else ctx.getString(R.string.settings_home_ebike_off)
}

private fun dashcamSetupPrompt(ctx: Context, snap: es.jjrh.bikeradar.data.PrefsSnapshot): String? = when (snap.dashcamOwnership) {
    DashcamOwnership.UNANSWERED -> ctx.getString(R.string.settings_home_dashcam_setup)
    DashcamOwnership.NO -> ctx.getString(R.string.settings_home_dont_have_one)
    DashcamOwnership.YES ->
        if (snap.dashcamMac != null) null else ctx.getString(R.string.settings_home_dashcam_pick)
}

// Same derivation AND the same words as the home screen's System row. The old
// `else` branch said "Connected" for stored credentials that had never
// published anything, which is the over-claim the deriver exists to refuse.
private fun haSubtitle(ctx: Context, configured: Boolean, health: HaHealth): String = ctx.getString(haStatusLabel(HaStatusDeriver.derive(configured, health)))

private fun experimentalSubtitle(ctx: Context, snap: es.jjrh.bikeradar.data.PrefsSnapshot): String {
    val on = buildList {
        if (snap.precogEnabled) add(ctx.getString(R.string.settings_home_exp_precog))
    }
    return if (on.isEmpty()) {
        ctx.getString(R.string.settings_home_exp_all_off)
    } else {
        ctx.getString(R.string.settings_home_exp_active, on.joinToString(" + "))
    }
}
