// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.Permissions
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Redesigned home screen, matching the design handoff's main-screen
 * mockup end-to-end.
 *
 * Layout, top to bottom:
 *  1. Top bar: BR mark + "Bike Radar" wordmark only. NO overflow menu —
 *     Debug + About move into Settings → ADVANCED. The triple-tap dev-
 *     mode unlock affordance hides on the wordmark (invisible to non-
 *     dev users).
 *  2. Hero status card: pulsing status dot + headline + subtitle +
 *     optional full-width CTA. Driven by `MainStatusDeriver` exactly as
 *     V1; the new 8th case (Service stopped) renders here too.
 *  3. SYSTEM card: three rows (rear radar / front dashcam / Home
 *     Assistant) with icons, value text, optional battery chip,
 *     trailing status dot. All wired to real state buses.
 *  4. CLOSE PASSES stats card: big number + sub-line + sparkline +
 *     segmented year/month/week control. Synthetic data for now (see
 *     DEC-007 in the overnight decision log) until a persisted store
 *     lands.
 *  5. Full-width Settings button at the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(navController: NavController, prefs: Prefs) {
    UiTheme {
        MainScreenBody(navController, prefs)
    }
}

@Composable
private fun MainScreenBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val devUnlocked by DevModeState.unlocked.collectAsState()

    val radarState by RadarStateBus.state.collectAsState()
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val haHealth by HaHealthBus.state.collectAsState()
    val batteryEntries by BatteryStateBus.entries.collectAsState()

    // Pollers below use repeatOnLifecycle(RESUMED) so they pause when
    // the screen is off / app backgrounded — there's no value in
    // ticking the bond check or wall-clock when the user can't see
    // the result, and it lets Doze idle the device cleanly.
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasBond by remember { mutableStateOf(hasRearBond(ctx)) }
    var btEnabled by remember { mutableStateOf(isBluetoothEnabled(ctx)) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(5_000)
                hasBond = hasRearBond(ctx)
                btEnabled = isBluetoothEnabled(ctx)
            }
        }
    }

    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(2_000)
                tickNowMs = System.currentTimeMillis()
            }
        }
    }

    var devTapCount by remember { mutableIntStateOf(0) }
    var lastDevTapMs by remember { mutableLongStateOf(0L) }

    val now = tickNowMs.coerceAtLeast(radarState.timestamp)
    val radarFresh = radarState.source == DataSource.V2 &&
        now - radarState.timestamp < 10_000L
    val haErrorRecent = (haHealth as? HaHealth.Error)?.let {
        now - it.atMs < 5 * 60_000L
    } ?: false
    val dashcamSlug = prefsSnap.dashcamMac?.let { mac ->
        BikeRadarService.macToSlug[mac]
            ?: BikeRadarService.macToSlug[mac.uppercase(Locale.ROOT)]
            ?: prefsSnap.dashcamDisplayName?.let { BikeRadarService.slug(it) }
    }
    val dashcamFresh = dashcamSlug?.let { slug ->
        batteryEntries[slug]?.let { now - it.readAtMs < 30_000L } == true
    } ?: false
    val dashcamPaired = prefsSnap.dashcamMac != null
    val dashcamOwned = prefsSnap.dashcamOwnership == DashcamOwnership.YES

    val inputs = MainStatusInputs(
        firstRunComplete = prefsSnap.firstRunComplete,
        pausedUntilEpochMs = prefsSnap.pausedUntilEpochMs,
        hasBond = hasBond,
        radarFresh = radarFresh,
        haErrorRecent = haErrorRecent,
        dashcamOwned = dashcamOwned && dashcamPaired,
        dashcamWarnWhenOff = prefsSnap.dashcamWarnWhenOff,
        dashcamFresh = dashcamFresh,
        dashcamDisplayName = prefsSnap.dashcamDisplayName,
        serviceEnabled = prefsSnap.serviceEnabled,
        bluetoothEnabled = btEnabled,
    )
    val status = MainStatusDeriver.derive(
        inputs,
        nowMs = now,
        formatTime = {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        },
    )
    val cta = ctaFor(inputs, now, navController, ctx, prefs)

    val heroIsBtOff = status.icon == MainStatusIcon.BluetoothDisabled &&
        status.tone == MainStatusTone.Warn
    val showBtOffBanner = !btEnabled && !heroIsBtOff
    val showDashcamPrompt = prefsSnap.dashcamOwnership == DashcamOwnership.UNANSWERED

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val onWordmarkLongPress = {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDevTapMs > 2_000L) devTapCount = 0
        devTapCount++
        lastDevTapMs = nowMs
        if (devTapCount >= 3 && !devUnlocked) {
            DevModeState.unlock(prefs)
            devTapCount = 0
            Toast.makeText(ctx, "Developer options enabled", Toast.LENGTH_SHORT).show()
        }
    }
    val onBtBannerTap = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }
    val onSettingsClick = { navController.navigate("settings") }
    val onDashcamYes = {
        prefs.dashcamOwnership = DashcamOwnership.YES
        navController.navigate("settings")
    }
    val onDashcamNo = { prefs.dashcamOwnership = DashcamOwnership.NO }

    val radarBattery = batteryEntries.values.firstOrNull { entry ->
        val n = entry.name.lowercase()
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    }
    val dashcamBattery = dashcamSlug?.let { batteryEntries[it] }
    val haHealthy = !haErrorRecent && (haHealth is HaHealth.Ok || haHealth is HaHealth.Unknown)

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        if (isLandscape) {
            MainScreenLandscape(
                status = status,
                cta = cta,
                btEnabled = btEnabled,
                showBtOffBanner = showBtOffBanner,
                showDashcamPrompt = showDashcamPrompt,
                radarFresh = radarFresh,
                hasBond = hasBond,
                dashcamOwned = dashcamOwned,
                dashcamFresh = dashcamFresh,
                dashcamPaired = dashcamPaired,
                dashcamDisplayName = prefsSnap.dashcamDisplayName,
                radarBattery = radarBattery,
                dashcamBattery = dashcamBattery,
                haHealthy = haHealthy,
                closePassLoggingEnabled = prefsSnap.closePassLoggingEnabled,
                onWordmarkLongPress = onWordmarkLongPress,
                onBtBannerTap = onBtBannerTap,
                onSettingsClick = onSettingsClick,
                onDashcamYes = onDashcamYes,
                onDashcamNo = onDashcamNo,
            )
        } else {
            MainScreenPortrait(
                status = status,
                cta = cta,
                btEnabled = btEnabled,
                showBtOffBanner = showBtOffBanner,
                showDashcamPrompt = showDashcamPrompt,
                radarFresh = radarFresh,
                hasBond = hasBond,
                dashcamOwned = dashcamOwned,
                dashcamFresh = dashcamFresh,
                dashcamPaired = dashcamPaired,
                dashcamDisplayName = prefsSnap.dashcamDisplayName,
                radarBattery = radarBattery,
                dashcamBattery = dashcamBattery,
                haHealthy = haHealthy,
                closePassLoggingEnabled = prefsSnap.closePassLoggingEnabled,
                onWordmarkLongPress = onWordmarkLongPress,
                onBtBannerTap = onBtBannerTap,
                onSettingsClick = onSettingsClick,
                onDashcamYes = onDashcamYes,
                onDashcamNo = onDashcamNo,
            )
        }
    }
}

@Composable
private fun TopBar(onWordmarkLongPress: () -> Unit) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 18.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        BrMark(size = 26.dp)
        Text(
            text = "Bike Radar",
            color = br.fg,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = onWordmarkLongPress,
            ),
        )
    }
}

@Composable
private fun MainScreenPortrait(
    status: MainStatus,
    cta: StatusCta?,
    btEnabled: Boolean,
    showBtOffBanner: Boolean,
    showDashcamPrompt: Boolean,
    radarFresh: Boolean,
    hasBond: Boolean,
    dashcamOwned: Boolean,
    dashcamFresh: Boolean,
    dashcamPaired: Boolean,
    dashcamDisplayName: String?,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    haHealthy: Boolean,
    closePassLoggingEnabled: Boolean,
    onWordmarkLongPress: () -> Unit,
    onBtBannerTap: () -> Unit,
    onSettingsClick: () -> Unit,
    onDashcamYes: () -> Unit,
    onDashcamNo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            TopBar(onWordmarkLongPress = onWordmarkLongPress)
            HeroStatusCard(status = status, cta = cta)
            Spacer(modifier = Modifier.height(12.dp))
            if (showBtOffBanner) {
                BluetoothOffBanner(onTap = onBtBannerTap)
                Spacer(modifier = Modifier.height(12.dp))
            }
            SystemCard(
                radarFresh = radarFresh,
                hasBond = hasBond,
                btEnabled = btEnabled,
                dashcamOwned = dashcamOwned,
                dashcamFresh = dashcamFresh,
                dashcamPaired = dashcamPaired,
                dashcamDisplayName = dashcamDisplayName,
                radarBattery = radarBattery,
                dashcamBattery = dashcamBattery,
                haHealthy = haHealthy,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ClosePassStatsCard(loggingEnabled = closePassLoggingEnabled)
            if (showDashcamPrompt) {
                Spacer(modifier = Modifier.height(14.dp))
                DashcamPromptCard(onYes = onDashcamYes, onNo = onDashcamNo)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SettingsButton(onClick = onSettingsClick)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Two-column landscape layout balanced 48 / 52: Hero + Close-passes
 * stack on the left (status + statistics); System card on the right
 * with Settings pinned to the bottom. TopBar sits full-width above
 * both columns so card tops align. Both columns scroll independently
 * as a fallback when prompts (BT-off, dashcam) push content past
 * the viewport.
 */
@Composable
private fun MainScreenLandscape(
    status: MainStatus,
    cta: StatusCta?,
    btEnabled: Boolean,
    showBtOffBanner: Boolean,
    showDashcamPrompt: Boolean,
    radarFresh: Boolean,
    hasBond: Boolean,
    dashcamOwned: Boolean,
    dashcamFresh: Boolean,
    dashcamPaired: Boolean,
    dashcamDisplayName: String?,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    haHealthy: Boolean,
    closePassLoggingEnabled: Boolean,
    onWordmarkLongPress: () -> Unit,
    onBtBannerTap: () -> Unit,
    onSettingsClick: () -> Unit,
    onDashcamYes: () -> Unit,
    onDashcamNo: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        // Full-width top bar so the wordmark sits at the head of the
        // app screen, and so the Hero + System card tops align in a
        // single horizontal line below it.
        TopBar(onWordmarkLongPress = onWordmarkLongPress)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(0.48f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                HeroStatusCard(status = status, cta = cta)
                Spacer(modifier = Modifier.height(12.dp))
                ClosePassStatsCard(
                    loggingEnabled = closePassLoggingEnabled,
                    compact = true,
                )
                if (showBtOffBanner) {
                    Spacer(modifier = Modifier.height(12.dp))
                    BluetoothOffBanner(onTap = onBtBannerTap)
                }
                if (showDashcamPrompt) {
                    Spacer(modifier = Modifier.height(12.dp))
                    DashcamPromptCard(onYes = onDashcamYes, onNo = onDashcamNo)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Column(
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxHeight(),
            ) {
                // Right column has no verticalScroll — adding cards here
                // requires re-checking the viewport math (~360-400 dp
                // budget on a Pixel landscape after status bar + nav).
                SystemCard(
                    radarFresh = radarFresh,
                    hasBond = hasBond,
                    btEnabled = btEnabled,
                    dashcamOwned = dashcamOwned,
                    dashcamFresh = dashcamFresh,
                    dashcamPaired = dashcamPaired,
                    dashcamDisplayName = dashcamDisplayName,
                    radarBattery = radarBattery,
                    dashcamBattery = dashcamBattery,
                    haHealthy = haHealthy,
                )
                // Push Settings to the bottom of the right column.
                Spacer(modifier = Modifier.weight(1f))
                SettingsButton(onClick = onSettingsClick)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// ── Hero status card ─────────────────────────────────────────────────

private data class StatusCta(val label: String, val onClick: () -> Unit)

@Composable
private fun ctaFor(
    inputs: MainStatusInputs,
    nowMs: Long,
    navController: NavController,
    ctx: Context,
    prefs: Prefs,
): StatusCta? = when {
    !inputs.firstRunComplete ->
        StatusCta("Set up") { navController.navigate("onboarding") }

    !inputs.serviceEnabled -> StatusCta("Start scanning") {
        prefs.serviceEnabled = true
        if (Permissions.hasRequiredForService(ctx)) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, BikeRadarService::class.java),
            )
        } else {
            Toast.makeText(
                ctx,
                "Grant Bluetooth permissions in Settings to start scanning",
                Toast.LENGTH_LONG,
            ).show()
            navController.navigate("settings")
        }
    }

    nowMs < inputs.pausedUntilEpochMs ->
        StatusCta("Resume") { prefs.pausedUntilEpochMs = 0L }

    !inputs.bluetoothEnabled -> StatusCta(
        label = "Turn on Bluetooth",
        onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) },
    )

    !inputs.hasBond -> StatusCta(
        label = "Pair",
        onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) },
    )

    // Dashcam-off Warn: no CTA per DEC-002.
    // Live + good / Live + HA down / Waiting: no CTA.
    else -> null
}

@Composable
private fun HeroStatusCard(status: MainStatus, cta: StatusCta?) {
    val br = LocalBrColors.current
    val (dotColor, pulse) = dotForStatus(status.tone, status.icon, br)
    BrCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    StatusDot(color = dotColor, pulse = pulse, size = 10.dp)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.headline,
                        color = br.fg,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    status.subtitle?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = it,
                            color = br.fgMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            if (cta != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(br.bgElev2)
                        .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = cta.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cta.label,
                        color = br.fg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun dotForStatus(
    tone: MainStatusTone,
    icon: MainStatusIcon,
    br: BrColors,
): Pair<Color, Boolean> {
    // Map MainStatus to mockup's dot+pulse pairs.
    //   Service stopped     → fgDim, no pulse  (matches `serviceOff`)
    //   Waiting for radar   → fgDim, pulse     (matches `searching`)
    //   First-run setup     → safe, no pulse   (matches `active` style — green welcome)
    //   Paused              → fgDim, no pulse
    //   Not paired (BT on)  → caution, no pulse
    //   Not paired (BT off) → danger, no pulse  (mockup's `noBluetooth`)
    //   Live + dashcam off  → caution, pulse  (matches `dashcamMissing`)
    //   Live + HA down      → safe, no pulse  (still live; HA is a side-channel)
    //   Live + good         → safe, no pulse
    return when (icon) {
        // PlayCircle is shared between First-run (Good) and Service-
        // stopped (Neutral). Mockup: first-run is the green "active"
        // welcome dot, service-stopped is the muted `serviceOff` dot.
        MainStatusIcon.PlayCircle -> when (tone) {
            MainStatusTone.Good -> br.safe to false
            else -> br.fgDim to false
        }
        MainStatusIcon.PauseCircle -> br.fgDim to false
        // BT-off (Warn tone) and Radar-not-paired (Error tone) share this
        // icon. The dot uses the tone so the two states are visually
        // distinct: caution amber for "fixable in two taps", danger red
        // for "needs the system pair flow".
        MainStatusIcon.BluetoothDisabled -> when (tone) {
            MainStatusTone.Warn -> br.caution to false
            else -> br.danger to false
        }
        MainStatusIcon.Sensors -> br.fgDim to true
        MainStatusIcon.Warning -> br.caution to true
        MainStatusIcon.CheckCircle -> when (tone) {
            MainStatusTone.Good -> br.safe to false
            else -> br.fg to false
        }
    }
}

// ── System card ──────────────────────────────────────────────────────

@Composable
private fun BluetoothOffBanner(onTap: () -> Unit) {
    val br = LocalBrColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LocalBrShapes.current.r3))
            .background(br.danger.copy(alpha = 0.10f))
            .border(1.dp, br.danger.copy(alpha = 0.30f), RoundedCornerShape(LocalBrShapes.current.r3))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(color = br.danger, size = 8.dp)
            Text(
                text = "Bluetooth off",
                color = br.fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Tap to enable",
                color = br.danger,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SystemCard(
    radarFresh: Boolean,
    hasBond: Boolean,
    btEnabled: Boolean,
    dashcamOwned: Boolean,
    dashcamFresh: Boolean,
    dashcamPaired: Boolean,
    dashcamDisplayName: String?,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    haHealthy: Boolean,
) {
    val br = LocalBrColors.current

    // Three-state device vocabulary (from the UX converger):
    //   Not paired      — grey hollow ring
    //   No signal       — amber
    //   Live            — green, solid
    // Plus: BT off shown ONCE as a card-level banner, never per-row.
    //
    // Battery chip hides outside Live to avoid surfacing stale numbers.
    val radarRow = SystemRow(
        icon = Icons.Default.Sensors,
        label = "Rear radar",
        value = when {
            !btEnabled || !hasBond -> "Not paired"
            radarFresh -> "Live"
            else -> "No signal"
        },
        muted = !hasBond || !btEnabled,
        battery = if (radarFresh) radarBattery?.pct else null,
        dot = when {
            !btEnabled || !hasBond -> br.fgDim
            radarFresh -> br.safe
            else -> br.caution
        },
        hollow = !btEnabled || !hasBond,
    )

    val dashcamRow = SystemRow(
        icon = Icons.Default.Videocam,
        label = "Front dashcam",
        value = when {
            !dashcamOwned || !dashcamPaired -> "Not paired"
            dashcamFresh -> dashcamDisplayName ?: "Live"
            else -> "No signal"
        },
        muted = !dashcamOwned || !dashcamPaired,
        battery = if (dashcamFresh) dashcamBattery?.pct else null,
        dot = when {
            !dashcamOwned || !dashcamPaired -> br.fgDim
            dashcamFresh -> br.safe
            else -> br.caution
        },
        hollow = !dashcamOwned || !dashcamPaired,
    )

    val haRow = SystemRow(
        icon = Icons.Default.Home,
        label = "Home Assistant",
        value = if (haHealthy) "MQTT ready" else "Unreachable",
        muted = !haHealthy,
        battery = null,
        dot = if (haHealthy) br.safe else br.caution,
    )

    BrCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SectionLabel("System")
            Spacer(modifier = Modifier.height(10.dp))
            SystemRowRender(radarRow, isFirst = true)
            SystemRowRender(dashcamRow, isFirst = false)
            SystemRowRender(haRow, isFirst = false)
        }
    }
}

private data class SystemRow(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val muted: Boolean,
    val battery: Int?,
    val dot: Color,
    val hollow: Boolean = false,
)

@Composable
private fun SystemRowRender(row: SystemRow, isFirst: Boolean) {
    val br = LocalBrColors.current
    if (!isFirst) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(br.hairline),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = row.icon,
            contentDescription = null,
            tint = if (row.muted) br.fgDim else br.fgMuted,
            modifier = Modifier.size(17.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                color = if (row.muted) br.fgMuted else br.fg,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.value,
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
                if (row.battery != null) BatteryChip(pct = row.battery)
            }
        }
        StatusDot(color = row.dot, hollow = row.hollow, size = 7.dp)
    }
}

// ── Close-pass stats card ─────────────────────────────────────────────
//
// The repo doesn't yet persist close-pass history (events stream
// straight to HA via MQTT and aren't kept locally). With nothing to
// visualise, the card renders an empty state matching what the user
// would actually see today. Once a local store lands the layout flips
// to the mockup's number + sparkline + segmented control.

@Composable
private fun ClosePassStatsCard(loggingEnabled: Boolean, compact: Boolean = false) {
    val br = LocalBrColors.current
    BrCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
            SectionLabel("Close passes")
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "0",
                color = br.fgDim,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Light,
                fontSize = 38.sp,
                letterSpacing = (-1).sp,
            )
            if (!loggingEnabled && !compact) {
                // Hidden in compact mode (landscape) so the card doesn't
                // push the rest of the left column past the viewport on
                // first-run installs that haven't enabled HA logging.
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Enable Settings → Radar & alerts → Log to Home Assistant to start counting overtakes inside your set lateral threshold.",
                    color = br.fgDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

// ── Settings button ──────────────────────────────────────────────────

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val br = LocalBrColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = br.fg,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Settings",
                color = br.fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── Dashcam ownership prompt (kept for first-run flows) ─────────────

@Composable
private fun DashcamPromptCard(onYes: () -> Unit, onNo: () -> Unit) {
    val br = LocalBrColors.current
    BrCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Do you run a dashcam?",
                color = br.fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "The overlay shows its battery and flags when it stops broadcasting.",
                color = br.fgMuted,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(br.dashcam)
                        .combinedClickable(onClick = onYes),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Set it up", color = br.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = onNo),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("I don't have one", color = br.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── BLE helpers (mirror V1's hasRearBond) ────────────────────────────

@SuppressLint("MissingPermission")
private fun hasRearBond(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.any { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    } == true
} catch (_: Throwable) { false }

private fun isBluetoothEnabled(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.isEnabled == true
} catch (_: Throwable) { false }
