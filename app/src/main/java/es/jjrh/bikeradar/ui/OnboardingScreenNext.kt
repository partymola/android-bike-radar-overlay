// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
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
fun OnboardingScreenNext(
    navController: NavController,
    prefs: Prefs,
    onFinished: () -> Unit,
) {
    NextTheme {
        OnboardingScreenNextBody(navController, prefs, onFinished)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnboardingScreenNextBody(
    navController: NavController,
    prefs: Prefs,
    onFinished: () -> Unit,
) {
    val br = LocalBrColors.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    Column(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        // Top bar: progress + Skip
        TopProgress(currentPage = pagerState.currentPage, onSkip = onFinished)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> PermissionsStepNext(
                    onContinue = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
                1 -> HaStepNext(
                    onContinue = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onSkip = { scope.launch { pagerState.animateScrollToPage(2) } },
                    prefs = prefs,
                )
                2 -> PairingStepNext(
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
private fun PermissionsStepNext(onContinue: () -> Unit) {
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
                sub = "A few system permissions so the app can connect, stay running, and show alerts.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((spec, granted) in states) {
                    PermissionCardNext(spec = spec, granted = granted, onChanged = { refresh++ })
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

// ── Step 1 — Home Assistant ──────────────────────────────────────────

@Composable
private fun HaStepNext(onContinue: () -> Unit, onSkip: () -> Unit, prefs: Prefs) {
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

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            StepHeroBlock(
                icon = Icons.Default.Home,
                tint = Color(0xFFFF8A3D),
                mark = "Step 2 of 3 · Optional",
                title = "Connect to Home Assistant",
                sub = "Publish ride and battery telemetry to HA for logging, dashboards, and pre-ride reminders. Skip if you don't use HA.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FieldNext(
                    label = "Base URL",
                    value = urlField,
                    onChange = { urlField = it },
                    placeholder = "https://homeassistant.local:8123",
                    mono = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                )
                FieldNext(
                    label = "Long-lived access token",
                    value = tokenField,
                    onChange = { tokenField = it },
                    placeholder = "eyJ0eXAiOiJKV1QiLCJh…",
                    mono = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (tokenVisible) "Hide token" else "Show token",
                                tint = br.fgMuted,
                            )
                        }
                    },
                    hint = "Profile → Security → Long-lived access tokens, in HA.",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
                        .clickable(enabled = canSubmit && !pinging) {
                            pinging = true
                            scope.launch(Dispatchers.IO) {
                                val client = HaClient(urlField.trim(), tokenField.trim())
                                pingResult = client.ping()
                                if (pingResult?.isSuccess == true) {
                                    creds.save(urlField.trim(), tokenField.trim())
                                    prefs.haLastValidatedEpochMs = System.currentTimeMillis()
                                }
                                pinging = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = br.brand,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (pinging) "Testing…" else "Test connection",
                            color = br.fg,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                pingResult?.let { r ->
                    NextChip(
                        text = if (r.isSuccess) "HA: saved" else "HA: ${r.exceptionOrNull()?.message ?: "error"}",
                        color = if (r.isSuccess) br.safe else br.danger,
                    )
                }
            }
        }
        FooterCtaDual(
            primary = "Continue",
            secondary = "Skip for now",
            primaryEnabled = true, // optional step — Continue is always allowed
            onPrimary = onContinue,
            onSecondary = onSkip,
        )
    }
}

// ── Step 2 — Pairing ─────────────────────────────────────────────────

@Composable
private fun PairingStepNext(
    navController: NavController,
    prefs: Prefs,
    onFinish: () -> Unit,
) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())

    var radarBonded by remember { mutableStateOf(hasRadarBondNext(ctx)) }
    var radarMac by remember { mutableStateOf(currentRadarMacNext(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(2_000)
                radarBonded = hasRadarBondNext(ctx)
                radarMac = currentRadarMacNext(ctx)
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
                sub = "Pairing happens in Android's Bluetooth settings, not in this app. Put the radar in pair mode (check the manual if unsure), then tap below.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Radar device row (always required).
                DeviceRowNext(
                    icon = Icons.Default.Sensors,
                    tint = br.brand,
                    title = "Rear radar",
                    optionalLabel = false,
                    bonded = radarBonded,
                    detail = if (radarBonded)
                        "Bonded · ${radarMac ?: "Rear radar"}"
                    else "No paired radar yet. Open Bluetooth settings to pair your rear radar.",
                    primaryCta = "Open Bluetooth settings",
                    primaryCtaIcon = Icons.Default.Bluetooth,
                    onPrimary = {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
                    },
                )

                // Dashcam row, three sub-states matching the JSX.
                when (prefsSnap.dashcamOwnership) {
                    DashcamOwnership.UNANSWERED -> DashcamUnansweredCardNext(
                        onSetUp = {
                            prefs.dashcamOwnership = DashcamOwnership.YES
                        },
                        onSkip = {
                            prefs.dashcamOwnership = DashcamOwnership.NO
                        },
                    )
                    DashcamOwnership.NO -> DeviceRowNext(
                        icon = Icons.Default.Videocam,
                        tint = br.dashcam,
                        title = "Front dashcam",
                        optionalLabel = true,
                        subtitle = "Optional · you said you don't have one",
                        bonded = false,
                        detail = "You can set this up later in Settings → Dashcam if you change your mind.",
                        primaryCta = "Actually, I do",
                        primaryCtaIcon = null,
                        onPrimary = { prefs.dashcamOwnership = DashcamOwnership.UNANSWERED },
                    )
                    DashcamOwnership.YES -> {
                        val picked = prefsSnap.dashcamMac != null
                        DeviceRowNext(
                            icon = Icons.Default.Videocam,
                            tint = br.dashcam,
                            title = "Front dashcam",
                            optionalLabel = true,
                            subtitle = "Optional · warns you if it's off",
                            bonded = picked,
                            detail = if (picked)
                                "${prefsSnap.dashcamDisplayName ?: "Picked"} · ${prefsSnap.dashcamMac}"
                            else "Got a Bluetooth dashcam?",
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

                // Hint — single Text with an inline span (AnnotatedString)
                // for the bolder "Settings → Dashcam" clause so it flows
                // correctly on any phone width.
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
        // Footer: always-enabled Finish, with warning text when radar
        // hasn't bonded yet — the user can configure HA telemetry
        // without the overlay so blocking onboarding on pairing would
        // close off legitimate use cases.
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!radarBonded) {
                Text(
                    text = "You can pair the radar later in Settings.",
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
private fun DashcamUnansweredCardNext(onSetUp: () -> Unit, onSkip: () -> Unit) {
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
                    .weight(1.4f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(br.dashcam)
                    .clickable(onClick = onSetUp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Set it up", color = br.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
private fun DeviceRowNext(
    icon: ImageVector,
    tint: Color,
    title: String,
    optionalLabel: Boolean,
    subtitle: String? = null,
    bonded: Boolean,
    detail: String,
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
            if (bonded) BondedChip()
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
                            .clickable(onClick = onExtra)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = extraAction, color = br.fgMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
private fun FieldNext(
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
private fun hasRadarBondNext(ctx: Context): Boolean = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.any { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    } == true
} catch (_: Throwable) { false }

@SuppressLint("MissingPermission")
private fun currentRadarMacNext(ctx: Context): String? = try {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    mgr?.adapter?.bondedDevices?.firstOrNull { dev ->
        val n = dev.name?.lowercase() ?: ""
        n.contains("rearvue") || n.contains("rtl") || n.contains("varia")
    }?.address
} catch (_: Throwable) { null }
