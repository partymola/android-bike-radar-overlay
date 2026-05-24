// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.EBikeStateBus
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.LdiOutcome
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.HaIntent
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings

/**
 * Mockup-fidelity onboarding pager. Four-step structure: Permissions ->
 * Home Assistant (optional) -> Pair devices -> Connect your eBike.
 *
 * Top: progress bar (4 segments) + Skip on the right.
 * Each step: StepHero(icon, tint) -> Mark / H1 / Sub -> step body ->
 * sticky FooterCta. The eBike step uses `Last step` instead of `Step N
 * of 4` because it's terminal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    navController: NavController,
    prefs: Prefs,
    onFinished: () -> Unit,
) {
    UiTheme {
        OnboardingScreenBody(navController, prefs, onFinished)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnboardingScreenBody(
    navController: NavController,
    prefs: Prefs,
    onFinished: () -> Unit,
) {
    val br = LocalBrColors.current
    val scope = rememberCoroutineScope()
    // Resume deep-link: if the rider was mid-pair on the eBike step in
    // a previous session (set the resume flag, then killed the app),
    // jump straight to the last step so they don't re-walk Permissions
    // / HA / Pair only to confirm choices they already made. The flag
    // is cleared on Paired / Skip-for-now / a deliberate NO.
    val initialPage = if (prefs.ldiOnboardingResumePoint) 3 else 0
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 4 })

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        // Top bar: progress + Skip
        TopProgress(currentPage = pagerState.currentPage, onSkip = onFinished)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> PermissionsStep(
                    onContinue = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
                1 -> HaStep(
                    onContinue = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onSkip = { scope.launch { pagerState.animateScrollToPage(2) } },
                    prefs = prefs,
                )
                2 -> PairingStep(
                    navController = navController,
                    prefs = prefs,
                    onFinish = { scope.launch { pagerState.animateScrollToPage(3) } },
                )
                3 -> EBikeStep(
                    prefs = prefs,
                    onFinish = onFinished,
                )
            }
        }
    }
}

@Composable
private fun TopProgress(currentPage: Int, onSkip: () -> Unit) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0..3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= currentPage) br.brand else br.hairline2),
                )
            }
        }
        Box(modifier = Modifier.clickable(onClick = onSkip).padding(horizontal = 4.dp, vertical = 6.dp)) {
            Text(
                text = "Skip",
                color = br.fgMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── Step 0 — Permissions ─────────────────────────────────────────────

@Composable
private fun PermissionsStep(onContinue: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val states = remember(refresh) {
        PERMISSIONS.map { it to isSpecGranted(ctx, it) }
    }
    val requiredGranted = states.all { (spec, granted) -> !spec.required || granted }

    PermissionsStepContent(
        states = states,
        requiredGranted = requiredGranted,
        onContinue = onContinue,
        onPermissionChanged = { refresh++ },
    )
}

/**
 * Stateless leaf for the onboarding permissions step. Body of
 * [PermissionsStep] forwards a pre-resolved list of (spec, granted)
 * pairs and a refresh callback, keeping the lifecycle/permission-launcher
 * plumbing out of this composable so snapshot tests can render the
 * step without an Activity or [LocalContext].
 */
@Composable
internal fun PermissionsStepContent(
    states: List<Pair<PermissionSpec, Boolean>>,
    requiredGranted: Boolean,
    onContinue: () -> Unit,
    onPermissionChanged: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.Default.Shield,
                tint = br.brand,
                mark = "Step 1 of 4",
                title = "Grant permissions",
                sub = "System permissions so the app can find your devices, post a status notification, and draw the overlay.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((spec, granted) in states) {
                    PermissionCard(spec = spec, granted = granted, onChanged = onPermissionChanged)
                }
            }
        }
        FooterCta(
            label = "Continue",
            enabled = requiredGranted,
            onClick = onContinue,
        )
    }
}

// ── Step 2 — Home Assistant ──────────────────────────────────────────

@Composable
private fun HaStep(onContinue: () -> Unit, onSkip: () -> Unit, prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val scope = rememberCoroutineScope()
    val creds = remember { HaCredentials(ctx) }

    var urlField by remember { mutableStateOf(creds.baseUrl) }
    var tokenField by remember { mutableStateOf(creds.token) }
    var tokenVisible by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<Result<String>?>(null) }
    var pinging by remember { mutableStateOf(false) }
    val canSubmit = urlField.isNotBlank() && tokenField.isNotBlank()

    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    // Treat existing saved creds as implicit YES so legacy installs (or
    // anyone who configured HA in Settings) skip the chooser entirely.
    val effectiveIntent = when {
        prefsSnap.haIntent == HaIntent.NO -> HaIntent.NO
        prefsSnap.haIntent == HaIntent.YES || creds.isConfigured() -> HaIntent.YES
        else -> HaIntent.UNSET
    }
    val onContinueSaving: () -> Unit = {
        if (canSubmit) {
            creds.save(urlField.trim(), tokenField.trim())
            prefs.haIntent = HaIntent.YES
            // Confirm the silent save unless the user just tested — otherwise
            // a successful Test connection chip is followed by a redundant
            // "Saved without testing" toast.
            if (pingResult?.isSuccess != true) {
                android.widget.Toast.makeText(
                    ctx,
                    "Saved without testing",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        onContinue()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // No "Optional" mark on this step — the chooser owns the
            // decision now, so duplicating optionality in the hero would
            // suggest a separate skip path that doesn't exist.
            StepHeroBlock(
                icon = Icons.Default.Home,
                tint = Color(0xFFFF8A3D),
                mark = "Step 2 of 4",
                title = "Connect to Home Assistant",
                sub = "Publish ride and battery telemetry to HA for logging, dashboards, and pre-ride reminders.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (effectiveIntent) {
                    HaIntent.UNSET -> HaIntentChooser(
                        onUseHa = { prefs.haIntent = HaIntent.YES },
                        onNotForMe = { prefs.haIntent = HaIntent.NO },
                    )
                    HaIntent.YES -> HaFieldsBlock(
                        urlField = urlField,
                        onUrlChange = {
                            urlField = it
                            // A successful ping is no longer trustworthy
                            // once the URL changes — without invalidating
                            // it, onContinueSaving would suppress the
                            // "Saved without testing" toast on stale creds.
                            pingResult = null
                        },
                        tokenField = tokenField,
                        onTokenChange = {
                            tokenField = it
                            pingResult = null
                        },
                        tokenVisible = tokenVisible,
                        onToggleTokenVisible = { tokenVisible = !tokenVisible },
                        pingResult = pingResult,
                        pinging = pinging,
                        canSubmit = canSubmit,
                        onTest = {
                            pinging = true
                            scope.launch(Dispatchers.IO) {
                                val client = HaClient(urlField.trim(), tokenField.trim())
                                pingResult = client.ping()
                                if (pingResult?.isSuccess == true) {
                                    creds.save(urlField.trim(), tokenField.trim())
                                    prefs.haLastValidatedEpochMs = System.currentTimeMillis()
                                    prefs.haIntent = HaIntent.YES
                                }
                                pinging = false
                            }
                        },
                        onChangeIntent = {
                            // Tapping the "change" pill must actually return
                            // the user to the chooser. Without clearing creds
                            // + local field state, `effectiveIntent` keeps
                            // re-deriving YES via the implicit-creds rule and
                            // the pill looks inert for legacy installs.
                            creds.clear()
                            prefs.haLastValidatedEpochMs = 0L
                            urlField = ""
                            tokenField = ""
                            pingResult = null
                            prefs.haIntent = HaIntent.UNSET
                        },
                    )
                    HaIntent.NO -> HaSkippedCard(
                        onChangeMind = { prefs.haIntent = HaIntent.UNSET },
                    )
                }
            }
        }
        // Footer is intent-aware. UNSET: chooser drives nav, no footer.
        // YES with empty/partial fields: dual CTA so the user can still
        // bail via Skip-for-now. YES with both fields filled: only
        // Continue — the user has clearly opted into HA, and Skip-for-now
        // would silently discard typed creds. The "change" pill remains
        // the bail path. NO: single Continue.
        when (effectiveIntent) {
            HaIntent.UNSET -> Unit
            HaIntent.YES -> if (canSubmit) {
                FooterCta(
                    label = "Continue",
                    enabled = true,
                    onClick = onContinueSaving,
                )
            } else {
                FooterCtaDual(
                    primary = "Continue",
                    secondary = "Skip for now",
                    primaryEnabled = false,
                    onPrimary = onContinueSaving,
                    onSecondary = onSkip,
                )
            }
            HaIntent.NO -> FooterCta(
                label = "Continue",
                enabled = true,
                onClick = onContinue,
            )
        }
    }
}

@Composable
internal fun HaIntentChooser(onUseHa: () -> Unit, onNotForMe: () -> Unit) {
    val br = LocalBrColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IntentCard(
            title = "I use Home Assistant",
            subtitle = "Set up the URL and token now.",
            filled = true,
            onClick = onUseHa,
        )
        IntentCard(
            title = "Not for me",
            subtitle = "Skip this step. The app works without HA.",
            filled = false,
            onClick = onNotForMe,
        )
        Text(
            text = "You can change this later from Settings → Home Assistant.",
            color = br.fgDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
    }
}

@Composable
internal fun IntentCard(
    title: String,
    subtitle: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) br.brand.copy(alpha = 0.12f) else br.bgElev1)
            .border(
                1.dp,
                if (filled) br.brand.copy(alpha = 0.40f) else br.hairline,
                RoundedCornerShape(12.dp),
            )
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = br.fg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = br.fgMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            // Trailing chevron makes the tappability obvious — two stacked
            // cards with similar weight read as ambient panels otherwise.
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = br.fgMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun HaFieldsBlock(
    urlField: String,
    onUrlChange: (String) -> Unit,
    tokenField: String,
    onTokenChange: (String) -> Unit,
    tokenVisible: Boolean,
    onToggleTokenVisible: () -> Unit,
    pingResult: Result<String>?,
    pinging: Boolean,
    canSubmit: Boolean,
    onTest: () -> Unit,
    onChangeIntent: () -> Unit,
) {
    val br = LocalBrColors.current
    // Selected-state pill: keeps the user's pick visible and one tap away
    // from reverting, without taking the screen space a full chooser card
    // would. heightIn ensures a reasonable tap target.
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(br.brand.copy(alpha = 0.12f))
            .border(1.dp, br.brand.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onChangeIntent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Using Home Assistant · change",
            color = br.fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    Field(
        label = "Base URL",
        value = urlField,
        onChange = onUrlChange,
        placeholder = "https://homeassistant.local:8123",
        mono = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        ),
    )
    Field(
        label = "Long-lived access token",
        value = tokenField,
        onChange = onTokenChange,
        placeholder = "eyJ0eXAiOiJKV1QiLCJh…",
        mono = true,
        visualTransformation = if (tokenVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = onToggleTokenVisible) {
                Icon(
                    imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (tokenVisible) "Hide token" else "Show token",
                    tint = br.fgMuted,
                )
            }
        },
        hint = "In HA: Profile → Security → Long-lived access tokens.",
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
    )
    val testEnabled = canSubmit && !pinging
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(br.bgElev2)
            .clickable(enabled = testEnabled, onClick = onTest),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = null,
                tint = if (testEnabled) br.brand else br.fgDim,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = if (pinging) "Testing…" else "Test connection",
                color = if (testEnabled) br.fg else br.fgDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
    pingResult?.let { r ->
        BrChip(
            text = if (r.isSuccess) "HA: connected" else "HA: ${r.exceptionOrNull()?.message ?: "error"}",
            color = if (r.isSuccess) br.safe else br.danger,
        )
    }
    // When fields are incomplete, the dual footer shows a disabled
    // Continue with no inline reason. A muted hint here makes the
    // gating explicit without crowding the populated state.
    if (!canSubmit && pingResult == null) {
        Text(
            text = "Enter URL and token to continue.",
            color = br.fgDim,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun HaSkippedCard(onChangeMind: () -> Unit) {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "Skipped",
            color = br.fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "You can add Home Assistant any time from Settings → Home Assistant.",
            color = br.fgMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, br.hairline2, RoundedCornerShape(8.dp))
                .semantics { role = Role.Button }
                .clickable(onClick = onChangeMind)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Use Home Assistant",
                color = br.fg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── Step 2 — Pairing ─────────────────────────────────────────────────

@Composable
private fun PairingStep(
    navController: NavController,
    prefs: Prefs,
    onFinish: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())

    var radarBonded by remember { mutableStateOf(hasRadarBond(ctx)) }
    var radarMac by remember { mutableStateOf(currentRadarMac(ctx)) }
    var radarLocalName by remember { mutableStateOf(currentRadarLocalName(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(2_000)
                radarBonded = hasRadarBond(ctx)
                radarMac = currentRadarMac(ctx)
                radarLocalName = currentRadarLocalName(ctx)
            }
        }
    }

    PairingStepContent(
        radarBonded = radarBonded,
        radarLocalName = radarLocalName,
        radarMac = radarMac,
        dashcamOwnership = prefsSnap.dashcamOwnership,
        dashcamMac = prefsSnap.dashcamMac,
        dashcamDisplayName = prefsSnap.dashcamDisplayName,
        onOpenBluetoothSettings = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
        },
        onPickDashcam = { navController.navigate("dashcam-picker?fromOnboarding=true") },
        onDashcamSkip = { prefs.dashcamOwnership = DashcamOwnership.NO },
        onDashcamReclaim = { prefs.dashcamOwnership = DashcamOwnership.UNANSWERED },
        onFinish = onFinish,
    )
}

/**
 * Stateless leaf for the pairing step. The body owns the bond-state
 * poller and nav/prefs callbacks; this leaf only renders the
 * already-derived state so snapshot tests can exercise the radar and
 * dashcam sub-states without a [BluetoothManager] or a [NavController].
 */
@Composable
internal fun PairingStepContent(
    radarBonded: Boolean,
    radarLocalName: String?,
    radarMac: String?,
    dashcamOwnership: DashcamOwnership,
    dashcamMac: String?,
    dashcamDisplayName: String?,
    onOpenBluetoothSettings: () -> Unit,
    onPickDashcam: () -> Unit,
    onDashcamSkip: () -> Unit,
    onDashcamReclaim: () -> Unit,
    onFinish: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.Default.Bluetooth,
                tint = br.brand,
                mark = "Step 3 of 4",
                title = "Pair your devices",
                sub = "Pairing happens in Android's Bluetooth settings, not in this app. Put the radar in pair mode (check the manual if unsure), then tap Open Bluetooth settings.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Radar device row (always required). When already bonded
                // we hide the CTA and the detail-prefix — the chip alone
                // signals state, and re-pairing belongs in Settings, not
                // onboarding.
                DeviceRow(
                    icon = Icons.Default.Sensors,
                    tint = br.brand,
                    title = "Rear radar",
                    optionalLabel = false,
                    bonded = radarBonded,
                    detail = if (radarBonded) {
                        (radarLocalName ?: radarMac ?: "Rear radar")
                    } else {
                        "Not paired yet. The pairing screen is in Android's Bluetooth settings."
                    },
                    detailHint = if (!radarBonded) {
                        "After pairing, press back to return here."
                    } else {
                        null
                    },
                    primaryCta = if (!radarBonded) "Open Bluetooth settings" else null,
                    primaryCtaIcon = if (!radarBonded) Icons.Default.Bluetooth else null,
                    onPrimary = onOpenBluetoothSettings,
                )

                // Dashcam row, three sub-states matching the JSX.
                when (dashcamOwnership) {
                    DashcamOwnership.UNANSWERED -> DashcamUnansweredCard(
                        // Pick device opens the picker without flipping
                        // ownership first — that way a brief recompose into
                        // the YES-not-picked state never paints, and backing
                        // out of the picker leaves the user on the original
                        // unanswered card (pink button still pink). The
                        // picker writes ownership=YES on a successful save.
                        onSetUp = onPickDashcam,
                        onSkip = onDashcamSkip,
                    )
                    DashcamOwnership.NO -> DeviceRow(
                        icon = Icons.Default.Videocam,
                        tint = br.dashcam,
                        title = "Front dashcam",
                        optionalLabel = true,
                        subtitle = "You said you don't have one",
                        bonded = false,
                        detail = "Change your mind any time from Settings → Dashcam.",
                        primaryCta = "I do have one",
                        primaryCtaIcon = null,
                        onPrimary = onDashcamReclaim,
                    )
                    DashcamOwnership.YES -> {
                        val picked = dashcamMac != null
                        DeviceRow(
                            icon = Icons.Default.Videocam,
                            tint = br.dashcam,
                            title = "Front dashcam",
                            optionalLabel = true,
                            subtitle = if (picked) "Warns you if it's off" else null,
                            bonded = picked,
                            detail = if (picked) {
                                "${dashcamDisplayName ?: "Picked"} · $dashcamMac"
                            } else {
                                "Pick the dashcam you ride with to enable off-warnings."
                            },
                            primaryCta = if (picked) "Change device" else "Pick device",
                            primaryCtaIcon = null,
                            onPrimary = onPickDashcam,
                            extraAction = "I don't have one",
                            onExtra = onDashcamSkip,
                        )
                    }
                }

                // Hint shown in UNANSWERED / YES states. NO already says
                // the same thing in its detail box, so we'd be repeating
                // ourselves.
                if (dashcamOwnership != DashcamOwnership.NO) {
                    val hintText = androidx.compose.ui.text.buildAnnotatedString {
                        append("You can come back to this later in ")
                        pushStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = br.fg,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                        append("Settings → Dashcam")
                        pop()
                        append(".")
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(br.bgElev1)
                            .border(1.dp, br.hairline, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = hintText,
                            color = br.fgMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
        // Footer: always-enabled Continue, with warning text when radar
        // hasn't bonded yet - the user can configure HA telemetry
        // without the overlay so blocking onboarding on pairing would
        // close off legitimate use cases. The eBike step follows.
        Column(modifier = Modifier.fillMaxWidth()) {
            // Discovery hint: experimental features (directional audio,
            // overtake prediction) live behind a single Settings entry
            // so onboarding stays minimal.
            Text(
                text = "More features in Settings -> Experimental once you're set up.",
                color = br.fgDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
            )
            if (!radarBonded) {
                Text(
                    text = "You can pair the radar later from Bluetooth settings.",
                    color = br.fgDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            FooterCta(label = "Continue", enabled = true, onClick = onFinish)
        }
    }
}

@Composable
private fun DashcamUnansweredCard(onSetUp: () -> Unit, onSkip: () -> Unit) {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(br.dashcam.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = br.dashcam,
                    modifier = Modifier.size(20.dp),
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Front dashcam",
                    color = br.fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Mark("Optional")
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(br.bgElev2)
                .border(1.dp, br.hairline, RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(
                text = "Got a Bluetooth dashcam? The overlay shows its battery and flags when it stops broadcasting.",
                color = br.fgMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(br.dashcam)
                    .clickable(onClick = onSetUp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Pick device", color = br.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
                    .clickable(onClick = onSkip),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "I don't have one", color = br.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun DeviceRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    optionalLabel: Boolean,
    subtitle: String? = null,
    bonded: Boolean,
    detail: String,
    detailHint: String? = null,
    primaryCta: String?,
    primaryCtaIcon: ImageVector?,
    onPrimary: () -> Unit,
    extraAction: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    val br = LocalBrColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = title, color = br.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (optionalLabel) Mark("Optional")
                }
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = subtitle, color = br.fgDim, fontSize = 11.sp)
                }
            }
            if (bonded) PairedChip()
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (bonded) br.safe.copy(alpha = 0.05f) else br.bgElev2)
                .border(1.dp, if (bonded) br.safe.copy(alpha = 0.20f) else br.hairline, RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(
                text = detail,
                color = br.fgMuted,
                fontFamily = if (bonded) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (bonded) 11.sp else 12.sp,
                lineHeight = if (bonded) 16.sp else 17.sp,
                letterSpacing = if (bonded) 0.3.sp else 0.sp,
                modifier = if (bonded) {
                    // Without context the screen-reader hears just the bare
                    // device name in monospace. The visual PairedChip
                    // alongside isn't part of this Text's a11y subtree.
                    Modifier.semantics { contentDescription = "Paired with $detail" }
                } else {
                    Modifier
                },
            )
        }
        if (detailHint != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = detailHint,
                color = br.fgDim,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        if (primaryCta != null || extraAction != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (primaryCta != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, br.hairline2, RoundedCornerShape(8.dp))
                            .clickable(onClick = onPrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (primaryCtaIcon != null) {
                                Icon(
                                    imageVector = primaryCtaIcon,
                                    contentDescription = null,
                                    tint = br.fg,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(text = primaryCta, color = br.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (extraAction != null && onExtra != null) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, br.hairline2, RoundedCornerShape(8.dp))
                            .clickable(onClick = onExtra)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = extraAction, color = br.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ── Step 4: eBike ────────────────────────────────────────────────────

/**
 * Onboarding's fourth step: Connect your eBike. Tri-state by
 * [EBikeOwnership]:
 *  - UNANSWERED: chooser with two balanced [IntentCard]s.
 *  - YES + no bond: pairing walkthrough + live status panel, polling
 *    [EBikeStateBus.outcome] for the current state of the link.
 *  - YES + bonded: success [DeviceRow] with the short address and a
 *    Finish footer.
 *  - Outcome-keyed edge cards (NoServiceFound, SlotConflict,
 *    PermissionsDenied, NoInbound, PairPromptDeclined) shadow the
 *    walkthrough when the link reports a failure.
 *
 * Live outcome / snapshot data is consumed via [EBikeStateBus], a
 * process-wide singleton that [BikeRadarService] mirrors from its
 * service-owned [es.jjrh.bikeradar.EBikeLink]. This decoupling keeps
 * the Composable testable without a service binder; the tradeoff is
 * that the bus reads as Idle when the service isn't running, so we
 * fire [BikeRadarService.ACTION_START_LDI] from the chooser's "I have
 * one" branch to bring the subsystem up.
 */
@Composable
private fun EBikeStep(prefs: Prefs, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val outcome by EBikeStateBus.outcome.collectAsState()

    EBikeStepContent(
        ownership = prefsSnap.eBikeOwnership,
        bondedAddress = prefs.ldiBondedAddress,
        outcome = outcome,
        onChooseHave = {
            // Promotion -> YES. Flipping ldiEnabled arms the subsystem
            // and ACTION_START_LDI brings the advertiser up in-session.
            // ldiOnboardingResumePoint is set so a cold-start mid-pair
            // deep-links back to this step.
            prefs.eBikeOwnership = EBikeOwnership.YES
            prefs.ldiEnabled = true
            prefs.ldiOnboardingResumePoint = true
            ctx.startService(
                Intent(ctx, BikeRadarService::class.java).setAction(BikeRadarService.ACTION_START_LDI),
            )
        },
        onChooseDontHave = {
            prefs.eBikeOwnership = EBikeOwnership.NO
            prefs.ldiOnboardingResumePoint = false
            onFinish()
        },
        onOpenFlow = { openFlowFromOnboarding(ctx) },
        onOpenPermissionSettings = { openAppPermissionsSettings(ctx) },
        onTryAgain = {
            // Force a fresh advertise cycle. After SlotConflict (rider
            // unpaired their other accessory in Flow) or NoInbound (bike
            // woken up) the existing EBikeLink is in a terminal state;
            // ACTION_RESTART_LDI tears it down and rebuilds so the bike
            // can re-discover us.
            ctx.startService(
                Intent(ctx, BikeRadarService::class.java).setAction(BikeRadarService.ACTION_RESTART_LDI),
            )
        },
        onUnpairAndRepair = {
            // Unpair + return to YES, not-yet-paired. Reuses the same
            // helper as Settings.
            releaseEBikeBondFromOnboarding(ctx, prefs)
        },
        onSkipForNow = {
            prefs.ldiOnboardingResumePoint = false
            onFinish()
        },
        onFinish = {
            prefs.ldiOnboardingResumePoint = false
            onFinish()
        },
    )
}

/**
 * Stateless leaf. Snapshot-friendly; renders the eBike step from
 * already-derived state. Side effects (Prefs writes, service intents,
 * bond release) live in the body above.
 */
@Composable
internal fun EBikeStepContent(
    ownership: EBikeOwnership,
    bondedAddress: String?,
    outcome: LdiOutcome,
    onChooseHave: () -> Unit,
    onChooseDontHave: () -> Unit,
    onOpenFlow: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onTryAgain: () -> Unit,
    onUnpairAndRepair: () -> Unit,
    onSkipForNow: () -> Unit,
    onFinish: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.AutoMirrored.Filled.DirectionsBike,
                tint = br.brand,
                mark = "Last step",
                title = "Connect your eBike",
                sub = "For Bosch Smart System eBikes on firmware v19.54 or newer.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (ownership) {
                    EBikeOwnership.UNANSWERED, EBikeOwnership.NO -> EBikeChooser(
                        onHaveOne = onChooseHave,
                        onDontHaveOne = onChooseDontHave,
                    )
                    EBikeOwnership.YES -> {
                        val paired = outcome is LdiOutcome.Paired || bondedAddress != null
                        if (paired && outcome !is LdiOutcome.SlotConflict) {
                            EBikePairedBlock(
                                shortAddress = shortenAddress(
                                    (outcome as? LdiOutcome.Paired)?.shortAddress?.ifEmpty { bondedAddress.orEmpty() }
                                        ?: bondedAddress.orEmpty(),
                                ),
                                onUnpairAndRepair = onUnpairAndRepair,
                            )
                        } else {
                            EBikePairingWalkthrough(
                                outcome = outcome,
                                onOpenFlow = onOpenFlow,
                                onTryAgain = onTryAgain,
                                onOpenPermissionSettings = onOpenPermissionSettings,
                            )
                        }
                    }
                }
            }
        }
        // Footer is state-dependent:
        // - UNANSWERED / NO: chooser drives nav, no footer.
        // - YES not paired: skip-for-now + finish path is in the
        //   walkthrough's CTAs; show only a Skip footer.
        // - YES paired: single Finish.
        when {
            ownership != EBikeOwnership.YES -> Unit
            outcome is LdiOutcome.Paired || (bondedAddress != null && outcome !is LdiOutcome.SlotConflict) ->
                FooterCta(label = "Finish", enabled = true, onClick = onFinish)
            else -> FooterCta(label = "Skip for now", enabled = true, onClick = onSkipForNow)
        }
    }
}

@Composable
private fun EBikeChooser(onHaveOne: () -> Unit, onDontHaveOne: () -> Unit) {
    val br = LocalBrColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = listOf(
                "Beeps if the rear radar drops out mid-ride",
                "Quieter alerts when you stop",
                "Quieter walk-away alarm while riding",
            ).joinToString("\n") { "•  $it" },
            color = br.fgMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        IntentCard(
            title = "I have one",
            subtitle = "Pair to set it up.",
            filled = true,
            onClick = onHaveOne,
        )
        IntentCard(
            title = "I don't have one",
            subtitle = "Skip this step. The app works without it.",
            filled = false,
            onClick = onDontHaveOne,
        )
        Text(
            text = "You can change this later from Settings -> eBike.",
            color = br.fgDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
    }
}

/**
 * Pairing walkthrough body for the YES + not-yet-paired states, plus
 * the outcome-keyed edge cards. Single composable so the layout flow
 * (numbered walkthrough above, status / actions below) stays in one
 * place rather than diverging across outcomes.
 */
@Composable
private fun EBikePairingWalkthrough(
    outcome: LdiOutcome,
    onOpenFlow: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val br = LocalBrColors.current

    // The walkthrough is always visible; only the trailing edge card
    // changes per outcome.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(br.bgElev1)
            .border(1.dp, br.hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Pair from Flow",
            color = br.fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        WalkthroughLine(
            number = "1.",
            body = "Open Flow on this phone.",
        )
        WalkthroughLine(
            number = "2.",
            body = "Open your bike, then the gear icon -> Components -> Add new device -> Accessories.",
        )
        WalkthroughLine(
            number = "3.",
            body = "When the bike scans, pick this phone from the list (it shows as the phone's Bluetooth name, not \"Bike Radar\").",
        )
        WalkthroughLine(
            number = "4.",
            body = "Confirm pairing on the bike's display when prompted.",
        )
    }

    // Status panel. Talkback reads label + value via contentDescription.
    val statusText = walkthroughStatusText(outcome)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(br.bgElev2)
            .border(1.dp, br.hairline, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .semantics { contentDescription = "Status: $statusText" },
    ) {
        Text(
            text = statusText,
            color = br.fgMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }

    // Outcome-keyed edge card. Only renders when the outcome is a
    // failure that needs rider action.
    EBikeOutcomeEdgeCard(
        outcome = outcome,
        onOpenFlow = onOpenFlow,
        onTryAgain = onTryAgain,
        onOpenPermissionSettings = onOpenPermissionSettings,
    )

    // Primary "Open Flow" CTA, always reachable from the walkthrough.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(br.brand)
            .clickable(onClick = onOpenFlow),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = br.bg,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Open Flow",
                color = br.bg,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun walkthroughStatusText(outcome: LdiOutcome): String = when (outcome) {
    LdiOutcome.Idle -> "Not started yet."
    LdiOutcome.Advertising -> "Waiting for the bike..."
    LdiOutcome.Connecting -> "Connected. Reading your bike..."
    is LdiOutcome.Paired -> "Paired with bike at ${shortenAddress(outcome.shortAddress)}"
    LdiOutcome.NoInbound -> "Haven't heard from the bike yet."
    LdiOutcome.NoServiceFound -> "Couldn't find Live Data on the bike."
    LdiOutcome.PairPromptDeclined -> "Pairing wasn't confirmed on the bike."
    LdiOutcome.SlotConflict -> "Another device holds the bike's Live Data slot."
    LdiOutcome.PermissionsDenied -> "Bluetooth permission needed."
    LdiOutcome.AdapterUnavailable -> "Bluetooth is off."
}

@Composable
private fun WalkthroughLine(number: String, body: String) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = number,
            color = br.brand,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = body,
            color = br.fgMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun EBikeOutcomeEdgeCard(
    outcome: LdiOutcome,
    onOpenFlow: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val br = LocalBrColors.current
    val (body, ctas) = when (outcome) {
        LdiOutcome.NoInbound ->
            "We didn't hear from the bike. Make sure it's powered on and within 2 m, then try again." to
                listOf<EBikeCta>(EBikeCta("Try again", onTryAgain, primary = true))
        LdiOutcome.NoServiceFound ->
            "Couldn't find Live Data on the bike. Most likely the firmware is older than v19.54. Update via Flow." to
                listOf(
                    EBikeCta("Open Flow", onOpenFlow, primary = true),
                )
        LdiOutcome.PairPromptDeclined ->
            "The pairing prompt may have been declined on the bike's display. Tap Open Flow and confirm on the bike when prompted." to
                listOf(
                    EBikeCta("Open Flow", onOpenFlow, primary = true),
                    EBikeCta("Try again", onTryAgain, primary = false),
                )
        LdiOutcome.SlotConflict ->
            "Another accessory - possibly your previous phone running this app, a sports computer, or a sports watch - is paired with your bike's Live Data slot. The bike supports only one at a time. Release the other in Flow: open your bike -> gear icon -> Components, then remove the other accessory and try again." to
                listOf(
                    EBikeCta("Open Flow", onOpenFlow, primary = true),
                    EBikeCta("Try again", onTryAgain, primary = false),
                )
        LdiOutcome.PermissionsDenied ->
            "Bluetooth permissions are needed to talk to the bike." to
                listOf(EBikeCta("Open app permissions", onOpenPermissionSettings, primary = true))
        else -> return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(br.danger.copy(alpha = 0.08f))
            .border(1.dp, br.danger.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = body,
            color = br.fg,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (cta in ctas) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (cta.primary) br.brand else br.bgElev2)
                        .border(
                            1.dp,
                            if (cta.primary) br.brand else br.hairline2,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable(onClick = cta.onClick)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cta.label,
                        color = if (cta.primary) br.bg else br.fg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private data class EBikeCta(
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean,
)

@Composable
private fun EBikePairedBlock(shortAddress: String, onUnpairAndRepair: () -> Unit) {
    val br = LocalBrColors.current
    DeviceRow(
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        tint = br.brand,
        title = "Bosch eBike",
        optionalLabel = false,
        subtitle = "Warns if the radar drops; quieter standstill + walk-away.",
        bonded = true,
        detail = shortAddress,
        primaryCta = null,
        primaryCtaIcon = null,
        onPrimary = {},
        extraAction = "Pair a different bike",
        onExtra = onUnpairAndRepair,
    )
    Text(
        text = "Paired with bike at $shortAddress. Only one bike at a time - unpair to swap.",
        color = br.fgDim,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

private fun openFlowFromOnboarding(ctx: Context) {
    val pm = ctx.packageManager
    val launch = pm.getLaunchIntentForPackage(EBIKE_FLOW_PACKAGE)
    val intent = launch?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$EBIKE_FLOW_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            ctx,
            "Couldn't open Flow. Install it from the Play Store and try again.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}

private fun openAppPermissionsSettings(ctx: Context) {
    try {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", ctx.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {
        // Fallback to top-level settings if the per-app screen is
        // unavailable on this OEM.
        ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Mirror of [SettingsEBike]'s helper, duplicated to keep the onboarding
 * step free of a Settings dependency. The reflection-based unpair path
 * is small enough that one duplication beats the import cycle.
 *
 * getBondState() needs BLUETOOTH_CONNECT; reached only from the eBike
 * onboarding step, which the user enters after granting BLE permissions.
 */
@SuppressLint("MissingPermission")
private fun releaseEBikeBondFromOnboarding(ctx: Context, prefs: Prefs) {
    val address = prefs.ldiBondedAddress ?: run {
        android.widget.Toast.makeText(ctx, "No bond to release.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val btManager = ctx.getSystemService(BluetoothManager::class.java)
    val adapter = btManager?.adapter
    val device = try {
        adapter?.getRemoteDevice(address)
    } catch (_: Exception) {
        null
    }
    val msg = when {
        device == null -> "Could not look up the bike's BLE device."
        device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED -> {
            prefs.ldiBondedAddress = null
            "No active bond; cleared the local pointer."
        }
        else -> try {
            device.javaClass.getMethod("removeBond").invoke(device)
            prefs.ldiBondedAddress = null
            "Bike unpaired. Restart pairing to bond a different bike."
        } catch (_: Exception) {
            "Could not unpair automatically. Forget the bike in Android Settings -> Bluetooth."
        }
    }
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
}

/** Bosch Flow Android package name (public, on Google Play). */
private const val EBIKE_FLOW_PACKAGE = "com.bosch.ebike.onebikeapp"

// ── Shared step primitives ───────────────────────────────────────────

@Composable
internal fun StepHeroBlock(
    icon: ImageVector,
    tint: Color,
    mark: String,
    title: String,
    sub: String,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        HeroIcon(icon = icon, tint = tint)
        Spacer(modifier = Modifier.height(16.dp))
        Mark(text = mark)
        Spacer(modifier = Modifier.height(8.dp))
        H1(text = title)
        Spacer(modifier = Modifier.height(6.dp))
        Sub(text = sub)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    hint: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val br = LocalBrColors.current
    Column {
        Text(
            text = label,
            color = br.fgMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = br.fgDim) },
            singleLine = true,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            keyboardOptions = keyboardOptions,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = br.fg,
                unfocusedTextColor = br.fg,
                focusedBorderColor = br.brand,
                unfocusedBorderColor = br.hairline2,
                cursorColor = br.brand,
                focusedContainerColor = br.bgElev1,
                unfocusedContainerColor = br.bgElev1,
            ),
            textStyle = TextStyle(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 13.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) {
            Spacer(modifier = Modifier.height(5.dp))
            Text(text = hint, color = br.fgDim, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun FooterCta(label: String, enabled: Boolean, onClick: () -> Unit) {
    val br = LocalBrColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(br.bg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(br.hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) br.brand else br.bgElev2)
                    .clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (enabled) br.bg else br.fgDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun FooterCtaDual(
    primary: String,
    secondary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    val br = LocalBrColors.current
    Column(modifier = Modifier.fillMaxWidth().background(br.bg)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(br.hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, br.hairline2, RoundedCornerShape(12.dp))
                    .clickable(onClick = onSecondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = secondary, color = br.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (primaryEnabled) br.brand else br.bgElev2)
                    .clickable(enabled = primaryEnabled, onClick = onPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = primary,
                    color = if (primaryEnabled) br.bg else br.fgDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ── BLE helpers ──────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
private fun hasRadarBond(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.any { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    } == true
} catch (_: Throwable) {
    false
}

@SuppressLint("MissingPermission")
private fun currentRadarMac(ctx: Context): String? = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.firstOrNull { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    }?.address
} catch (_: Throwable) {
    null
}

@SuppressLint("MissingPermission")
private fun currentRadarLocalName(ctx: Context): String? = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.firstOrNull { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    }?.name
} catch (_: Throwable) {
    null
}
