// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import es.jjrh.bikeradar.AttentionItem
import es.jjrh.bikeradar.AttentionItemsDeriver
import es.jjrh.bikeradar.AttentionKind
import es.jjrh.bikeradar.AttentionStore
import es.jjrh.bikeradar.BatteryEntry
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.DataSource
import es.jjrh.bikeradar.DeviceNameMatcher
import es.jjrh.bikeradar.EBikeStateBus
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.HaStatus
import es.jjrh.bikeradar.HaStatusDeriver
import es.jjrh.bikeradar.Permissions
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarStateBus
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.eBikeDataIsFresh
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.Settings as AndroidSettings

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
    val ebikeSnap by EBikeStateBus.snapshot.collectAsState()
    val ebikeLastUpdated by EBikeStateBus.lastUpdatedElapsedMs.collectAsState()

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
                // Freshness boundaries on this screen are 10s (radar) and
                // 30s (dashcam); a 5s tick keeps detection latency well
                // under those thresholds while halving the recompose rate.
                delay(5_000)
                tickNowMs = System.currentTimeMillis()
            }
        }
    }

    var devTapCount by remember { mutableIntStateOf(0) }
    var lastDevTapMs by remember { mutableLongStateOf(0L) }

    val now = tickNowMs.coerceAtLeast(radarState.timestamp)
    val radarFresh = radarState.source == DataSource.V2 &&
        now - radarState.timestamp < 10_000L
    // Constructed once: HaCredentials' constructor runs the legacy-ciphertext
    // migration (AndroidKeyStore work on installs that still hold undecryptable
    // blobs), so it must not be rebuilt on every tick. Only the READ is keyed to
    // the tick - the getters go to storage each call, so this stays live when
    // credentials are saved while the screen is open.
    val haCreds = remember { HaCredentials(ctx) }
    val haConfigured = remember(tickNowMs) { haCreds.isConfigured() }
    // Gated on configured: HaHealthBus is process-global and is never reset when
    // the rider clears their credentials, so an ungated check leaves the hero
    // saying "Home Assistant unreachable" for five minutes after HA is removed,
    // while the System row correctly reads "Not configured".
    val haErrorRecent = haConfigured &&
        ((haHealth as? HaHealth.Error)?.let { now - it.atMs < 5 * 60_000L } ?: false)
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
    val statusModel = MainStatusDeriver.derive(
        inputs,
        nowMs = now,
        formatTime = {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        },
    )
    val status = MainStatus(
        icon = statusModel.icon,
        tone = statusModel.tone,
        headline = stringResource(statusModel.headlineRes, *statusModel.headlineArgs.toTypedArray()),
        subtitle = statusModel.subtitleRes?.let {
            stringResource(it, *statusModel.subtitleArgs.toTypedArray())
        },
    )
    val cta = ctaFor(inputs, now, navController, ctx, prefs)

    val heroIsBtOff = status.icon == MainStatusIcon.BluetoothDisabled &&
        status.tone == MainStatusTone.Warn
    val showBtOffBanner = !btEnabled && !heroIsBtOff
    val showDashcamPrompt = prefsSnap.dashcamOwnership == DashcamOwnership.UNANSWERED

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val devEnabledMsg = stringResource(R.string.main_toast_dev_enabled)
    val onWordmarkLongPress = {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDevTapMs > 2_000L) devTapCount = 0
        devTapCount++
        lastDevTapMs = nowMs
        if (devTapCount >= 3 && !devUnlocked) {
            DevModeState.unlock(prefs)
            devTapCount = 0
            Toast.makeText(ctx, devEnabledMsg, Toast.LENGTH_SHORT).show()
        }
    }
    val onBtBannerTap = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }
    val onSettingsClick = { navController.navigate("settings") }
    val onClosePassCardClick = { navController.navigate("ride-history") }
    val onDashcamYes = {
        prefs.dashcamOwnership = DashcamOwnership.YES
        navController.navigate("settings")
    }
    val onDashcamNo = { prefs.dashcamOwnership = DashcamOwnership.NO }

    val radarBattery = batteryEntries.values.firstOrNull { entry ->
        DeviceNameMatcher.isRadarName(entry.name)
    }
    val dashcamBattery = dashcamSlug?.let { batteryEntries[it] }
    // Credentials are re-read on the 5 s tick rather than in a remember{}:
    // they can be saved from Settings while this screen is open, and a
    // composition-time snapshot would leave the row asserting the old state.
    val haStatus = HaStatusDeriver.derive(haConfigured, haHealth)
    // eBike freshness samples elapsedRealtime() fresh on every recompose;
    // the 5 s tickNowMs above is the recompose driver, so the dot drops to
    // amber a few seconds after Flow stops streaming.
    val ebikeReceiving = eBikeDataIsFresh(ebikeLastUpdated, android.os.SystemClock.elapsedRealtime())
    val ebikeBatterySoc = ebikeSnap.batterySoc

    // Needs-attention card (bucket 3): the persisted end-of-trip feed the
    // service wrote at ride end, re-read on the 5 s tick so a ride that ends
    // while the app is open still surfaces. Live-cleared here so an item drops
    // the moment a FRESH reading shows the rider topped the device up; only a
    // fresh reading clears it (a stale/absent reading leaves it standing).
    // The rider can also dismiss any item by hand - the manual escape hatch
    // for the historical kinds, which have no live signal to clear them.
    val attentionStore = remember { AttentionStore(ctx.getSharedPreferences(AttentionStore.PREFS_NAME, Context.MODE_PRIVATE)) }
    var attentionRev by remember { mutableIntStateOf(0) }
    val persistedAttention = remember(tickNowMs, attentionRev) { attentionStore.load() }
    val attentionItems = AttentionItemsDeriver.filterUnresolved(
        persistedAttention,
        AttentionItemsDeriver.LiveState(
            radarBatteryPct = if (radarFresh) radarBattery?.pct else null,
            dashcamBatteryPct = if (dashcamFresh) dashcamBattery?.pct else null,
            ebikeSoc = if (ebikeReceiving) ebikeBatterySoc else null,
        ),
    )
    val onAttentionDismiss: (AttentionKind) -> Unit = { kind ->
        attentionStore.remove(kind)
        attentionRev++
    }

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        MainScreenContent(
            status = status,
            cta = cta,
            btEnabled = btEnabled,
            showBtOffBanner = showBtOffBanner,
            showDashcamPrompt = showDashcamPrompt,
            attentionItems = attentionItems,
            onAttentionDismiss = onAttentionDismiss,
            radarFresh = radarFresh,
            hasBond = hasBond,
            dashcamOwned = dashcamOwned,
            dashcamFresh = dashcamFresh,
            dashcamPaired = dashcamPaired,
            dashcamDisplayName = prefsSnap.dashcamDisplayName,
            radarBattery = radarBattery,
            dashcamBattery = dashcamBattery,
            haStatus = haStatus,
            closePassLoggingEnabled = prefsSnap.closePassLoggingEnabled,
            eBikeDataEnabled = prefsSnap.eBikeDataEnabled,
            ebikeReceiving = ebikeReceiving,
            ebikeBatterySoc = ebikeBatterySoc,
            isLandscape = isLandscape,
            onWordmarkLongPress = onWordmarkLongPress,
            onBtBannerTap = onBtBannerTap,
            onSettingsClick = onSettingsClick,
            onDashcamYes = onDashcamYes,
            onDashcamNo = onDashcamNo,
            onClosePassCardClick = onClosePassCardClick,
        )
    }
}

/**
 * Stateless leaf for the home screen — everything below the systemBars
 * padding box. Body owns the BLE bond / Bluetooth-enabled poller, the
 * close-pass / radar / battery flow collection, the [MainStatusDeriver]
 * call, and the dev-tap counter; this composable just renders the
 * resolved state in either portrait or landscape layout. Snapshot
 * tests render this directly without a [Prefs], a [BluetoothManager],
 * or any state buses.
 */
@Composable
internal fun MainScreenContent(
    status: MainStatus,
    cta: StatusCta?,
    btEnabled: Boolean,
    showBtOffBanner: Boolean,
    showDashcamPrompt: Boolean,
    attentionItems: List<AttentionItem> = emptyList(),
    onAttentionDismiss: (AttentionKind) -> Unit = {},
    radarFresh: Boolean,
    hasBond: Boolean,
    dashcamOwned: Boolean,
    dashcamFresh: Boolean,
    dashcamPaired: Boolean,
    dashcamDisplayName: String?,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    haStatus: HaStatus,
    closePassLoggingEnabled: Boolean,
    eBikeDataEnabled: Boolean = false,
    ebikeReceiving: Boolean = false,
    ebikeBatterySoc: Int? = null,
    isLandscape: Boolean,
    onWordmarkLongPress: () -> Unit,
    onBtBannerTap: () -> Unit,
    onSettingsClick: () -> Unit,
    onDashcamYes: () -> Unit,
    onDashcamNo: () -> Unit,
    onClosePassCardClick: () -> Unit = {},
) {
    if (isLandscape) {
        MainScreenLandscape(
            status = status,
            cta = cta,
            btEnabled = btEnabled,
            showBtOffBanner = showBtOffBanner,
            showDashcamPrompt = showDashcamPrompt,
            attentionItems = attentionItems,
            onAttentionDismiss = onAttentionDismiss,
            radarFresh = radarFresh,
            hasBond = hasBond,
            dashcamOwned = dashcamOwned,
            dashcamFresh = dashcamFresh,
            dashcamPaired = dashcamPaired,
            dashcamDisplayName = dashcamDisplayName,
            radarBattery = radarBattery,
            dashcamBattery = dashcamBattery,
            haStatus = haStatus,
            closePassLoggingEnabled = closePassLoggingEnabled,
            eBikeDataEnabled = eBikeDataEnabled,
            ebikeReceiving = ebikeReceiving,
            ebikeBatterySoc = ebikeBatterySoc,
            onWordmarkLongPress = onWordmarkLongPress,
            onBtBannerTap = onBtBannerTap,
            onSettingsClick = onSettingsClick,
            onDashcamYes = onDashcamYes,
            onDashcamNo = onDashcamNo,
            onClosePassCardClick = onClosePassCardClick,
        )
    } else {
        MainScreenPortrait(
            status = status,
            cta = cta,
            btEnabled = btEnabled,
            showBtOffBanner = showBtOffBanner,
            showDashcamPrompt = showDashcamPrompt,
            attentionItems = attentionItems,
            onAttentionDismiss = onAttentionDismiss,
            radarFresh = radarFresh,
            hasBond = hasBond,
            dashcamOwned = dashcamOwned,
            dashcamFresh = dashcamFresh,
            dashcamPaired = dashcamPaired,
            dashcamDisplayName = dashcamDisplayName,
            radarBattery = radarBattery,
            dashcamBattery = dashcamBattery,
            haStatus = haStatus,
            closePassLoggingEnabled = closePassLoggingEnabled,
            eBikeDataEnabled = eBikeDataEnabled,
            ebikeReceiving = ebikeReceiving,
            ebikeBatterySoc = ebikeBatterySoc,
            onWordmarkLongPress = onWordmarkLongPress,
            onBtBannerTap = onBtBannerTap,
            onSettingsClick = onSettingsClick,
            onDashcamYes = onDashcamYes,
            onDashcamNo = onDashcamNo,
            onClosePassCardClick = onClosePassCardClick,
        )
    }
}

@Composable
private fun TopBar(onWordmarkLongPress: () -> Unit) {
    val br = LocalBrColors.current
    val wordmark = stringResource(R.string.app_name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 18.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        BrMark(size = 26.dp)
        Text(
            text = wordmark,
            color = br.fg,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            modifier = Modifier
                .combinedClickable(
                    onClick = {},
                    onLongClick = onWordmarkLongPress,
                )
                // The tap does nothing (long-press is the hidden dev-unlock
                // gesture), so clear the inherited "double-tap to activate"
                // semantics and expose just the label. Touch handling for the
                // long-press is unaffected.
                .clearAndSetSemantics { contentDescription = wordmark },
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
    attentionItems: List<AttentionItem>,
    onAttentionDismiss: (AttentionKind) -> Unit,
    radarFresh: Boolean,
    hasBond: Boolean,
    dashcamOwned: Boolean,
    dashcamFresh: Boolean,
    dashcamPaired: Boolean,
    dashcamDisplayName: String?,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    haStatus: HaStatus,
    closePassLoggingEnabled: Boolean,
    eBikeDataEnabled: Boolean,
    ebikeReceiving: Boolean,
    ebikeBatterySoc: Int?,
    onWordmarkLongPress: () -> Unit,
    onBtBannerTap: () -> Unit,
    onSettingsClick: () -> Unit,
    onDashcamYes: () -> Unit,
    onDashcamNo: () -> Unit,
    onClosePassCardClick: () -> Unit,
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
            if (attentionItems.isNotEmpty()) {
                AttentionCard(items = attentionItems, onDismiss = onAttentionDismiss)
                Spacer(modifier = Modifier.height(12.dp))
            }
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
                haStatus = haStatus,
                ebikeEnabled = eBikeDataEnabled,
                ebikeReceiving = ebikeReceiving,
                ebikeBatterySoc = ebikeBatterySoc,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ClosePassStatsCard(
                loggingEnabled = closePassLoggingEnabled,
                onClick = onClosePassCardClick,
            )
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
    attentionItems: List<AttentionItem>,
    onAttentionDismiss: (AttentionKind) -> Unit,
    radarFresh: Boolean,
    hasBond: Boolean,
    dashcamOwned: Boolean,
    dashcamFresh: Boolean,
    dashcamPaired: Boolean,
    dashcamDisplayName: String?,
    radarBattery: BatteryEntry?,
    dashcamBattery: BatteryEntry?,
    haStatus: HaStatus,
    closePassLoggingEnabled: Boolean,
    eBikeDataEnabled: Boolean,
    ebikeReceiving: Boolean,
    ebikeBatterySoc: Int?,
    onWordmarkLongPress: () -> Unit,
    onBtBannerTap: () -> Unit,
    onSettingsClick: () -> Unit,
    onDashcamYes: () -> Unit,
    onDashcamNo: () -> Unit,
    onClosePassCardClick: () -> Unit,
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
                if (attentionItems.isNotEmpty()) {
                    AttentionCard(items = attentionItems, onDismiss = onAttentionDismiss)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                HeroStatusCard(status = status, cta = cta)
                Spacer(modifier = Modifier.height(12.dp))
                ClosePassStatsCard(
                    loggingEnabled = closePassLoggingEnabled,
                    compact = true,
                    onClick = onClosePassCardClick,
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
                    haStatus = haStatus,
                    ebikeEnabled = eBikeDataEnabled,
                    ebikeReceiving = ebikeReceiving,
                    ebikeBatterySoc = ebikeBatterySoc,
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

internal data class StatusCta(val label: String, val onClick: () -> Unit)

@Composable
private fun ctaFor(
    inputs: MainStatusInputs,
    nowMs: Long,
    navController: NavController,
    ctx: Context,
    prefs: Prefs,
): StatusCta? {
    // Hoisted out of the onClick lambda below: stringResource is
    // @Composable-only and cannot be called from the click handler.
    val grantBtMsg = stringResource(R.string.main_toast_grant_bt)
    return when {
        !inputs.firstRunComplete ->
            StatusCta(stringResource(R.string.main_cta_set_up)) { navController.navigate("onboarding") }

        !inputs.serviceEnabled -> StatusCta(stringResource(R.string.main_cta_start_scanning)) {
            prefs.serviceEnabled = true
            if (Permissions.hasRequiredForService(ctx)) {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, BikeRadarService::class.java),
                )
            } else {
                Toast.makeText(ctx, grantBtMsg, Toast.LENGTH_LONG).show()
                navController.navigate("settings")
            }
        }

        nowMs < inputs.pausedUntilEpochMs ->
            StatusCta(stringResource(R.string.main_cta_resume)) { prefs.pausedUntilEpochMs = 0L }

        !inputs.bluetoothEnabled -> StatusCta(
            label = stringResource(R.string.main_cta_turn_on_bluetooth),
            onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) },
        )

        !inputs.hasBond -> StatusCta(
            label = stringResource(R.string.main_cta_pair),
            onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) },
        )

        // Dashcam-off Warn: no CTA per DEC-002.
        // Live + good / Live + HA down / Waiting: no CTA.
        else -> null
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
                text = stringResource(R.string.common_settings),
                color = br.fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── BLE helpers (mirror V1's hasRearBond) ────────────────────────────

@SuppressLint("MissingPermission")
private fun hasRearBond(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.any { dev ->
        DeviceNameMatcher.isRadarName(dev.name)
    } == true
} catch (_: Throwable) {
    false
}

private fun isBluetoothEnabled(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.isEnabled == true
} catch (_: Throwable) {
    false
}
