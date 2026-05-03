// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import es.jjrh.bikeradar.HaClient
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.HaIntent
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mockup-fidelity onboarding pager. Mirrors `onboarding.jsx`'s 3-step
 * structure: Permissions → Home Assistant (optional) → Pair devices.
 *
 * Top: progress bar (3 segments) + Skip on the right.
 * Each step: StepHero(icon, tint) → Mark / H1 / Sub → step body →
 * sticky FooterCta. Layout copy and component anatomy match the JSX
 * end-to-end.
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
    val pagerState = rememberPagerState(pageCount = { 3 })

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
            for (i in 0..2) {
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
    val br = LocalBrColors.current
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

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.Default.Shield,
                tint = br.brand,
                mark = "Step 1 of 3",
                title = "Grant permissions",
                sub = "System permissions so the app can find your devices, post a status notification, and draw the overlay.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((spec, granted) in states) {
                    PermissionCard(spec = spec, granted = granted, onChanged = { refresh++ })
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
                mark = "Step 2 of 3",
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
private fun HaIntentChooser(onUseHa: () -> Unit, onNotForMe: () -> Unit) {
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
private fun IntentCard(
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
private fun HaFieldsBlock(
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
            .heightIn(min = 36.dp)
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
        visualTransformation = if (tokenVisible) VisualTransformation.None
        else PasswordVisualTransformation(),
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
            .height(40.dp)
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
private fun HaSkippedCard(onChangeMind: () -> Unit) {
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
                .height(36.dp)
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
    val br = LocalBrColors.current
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

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.Default.Bluetooth,
                tint = br.brand,
                mark = "Step 3 of 3",
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
                    detail = if (radarBonded)
                        (radarLocalName ?: radarMac ?: "Rear radar")
                    else "Not paired yet. The pairing screen is in Android's Bluetooth settings.",
                    detailHint = if (!radarBonded)
                        "After pairing, press back to return here."
                    else null,
                    primaryCta = if (!radarBonded) "Open Bluetooth settings" else null,
                    primaryCtaIcon = if (!radarBonded) Icons.Default.Bluetooth else null,
                    onPrimary = {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
                    },
                )

                // Dashcam row, three sub-states matching the JSX.
                when (prefsSnap.dashcamOwnership) {
                    DashcamOwnership.UNANSWERED -> DashcamUnansweredCard(
                        // Pick device opens the picker without flipping
                        // ownership first — that way a brief recompose into
                        // the YES-not-picked state never paints, and backing
                        // out of the picker leaves the user on the original
                        // unanswered card (pink button still pink). The
                        // picker writes ownership=YES on a successful save.
                        onSetUp = { navController.navigate("dashcam-picker?fromOnboarding=true") },
                        onSkip = { prefs.dashcamOwnership = DashcamOwnership.NO },
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
                        onPrimary = { prefs.dashcamOwnership = DashcamOwnership.UNANSWERED },
                    )
                    DashcamOwnership.YES -> {
                        val picked = prefsSnap.dashcamMac != null
                        DeviceRow(
                            icon = Icons.Default.Videocam,
                            tint = br.dashcam,
                            title = "Front dashcam",
                            optionalLabel = true,
                            subtitle = if (picked) "Warns you if it's off" else null,
                            bonded = picked,
                            detail = if (picked)
                                "${prefsSnap.dashcamDisplayName ?: "Picked"} · ${prefsSnap.dashcamMac}"
                            else "Pick the dashcam you ride with to enable off-warnings.",
                            primaryCta = if (picked) "Change device" else "Pick device",
                            primaryCtaIcon = null,
                            onPrimary = {
                                navController.navigate("dashcam-picker?fromOnboarding=true")
                            },
                            extraAction = "I don't have one",
                            onExtra = { prefs.dashcamOwnership = DashcamOwnership.NO },
                        )
                    }
                }

                // Hint shown in UNANSWERED / YES states. NO already says
                // the same thing in its detail box, so we'd be repeating
                // ourselves.
                if (prefsSnap.dashcamOwnership != DashcamOwnership.NO) {
                    val hintText = androidx.compose.ui.text.buildAnnotatedString {
                        append("You can come back to this later in ")
                        pushStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = br.fg,
                                fontWeight = FontWeight.Medium,
                            )
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
        // Footer: always-enabled Finish, with warning text when radar
        // hasn't bonded yet — the user can configure HA telemetry
        // without the overlay so blocking onboarding on pairing would
        // close off legitimate use cases.
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!radarBonded) {
                Text(
                    text = "You can pair the radar later from Bluetooth settings.",
                    color = br.fgDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            FooterCta(label = "Finish", enabled = true, onClick = onFinish)
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
                    .height(40.dp)
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
                    .height(40.dp)
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
private fun DeviceRow(
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
                } else Modifier,
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
                            .height(36.dp)
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
                            .height(36.dp)
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

// ── Shared step primitives ───────────────────────────────────────────

@Composable
private fun StepHeroBlock(
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
private fun FooterCta(label: String, enabled: Boolean, onClick: () -> Unit) {
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
private fun FooterCtaDual(
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
} catch (_: Throwable) { false }

@SuppressLint("MissingPermission")
private fun currentRadarMac(ctx: Context): String? = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.firstOrNull { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    }?.address
} catch (_: Throwable) { null }

@SuppressLint("MissingPermission")
private fun currentRadarLocalName(ctx: Context): String? = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.firstOrNull { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    }?.name
} catch (_: Throwable) { null }
