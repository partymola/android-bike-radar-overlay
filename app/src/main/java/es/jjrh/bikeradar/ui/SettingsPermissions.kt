// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavController
import es.jjrh.bikeradar.data.Prefs

@Composable
fun SettingsPermissions(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsPermissionsBody(navController, prefs)
    }
}

@Composable
private fun SettingsPermissionsBody(navController: NavController, @Suppress("UNUSED_PARAMETER") prefs: Prefs) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current

    // Recompute permission states on resume so a user who came back
    // from system Settings sees the new state immediately.
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val states = remember(refreshTick) {
        PERMISSIONS.map { spec -> spec to isSpecGranted(ctx, spec) }
    }

    // The body runs `PermissionCard` per spec so each card gets its own
    // permission-launcher (one launcher per call-site). The stateless
    // [SettingsPermissionsContent] mirrors this chrome for snapshot
    // tests but uses [PermissionCardContent] directly with stub actions.
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader("Permissions", onBack = { navController.popBackStack() })

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((spec, granted) in states) {
                    PermissionCard(spec, granted, onChanged = { refreshTick++ })
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/**
 * Stateless leaf — wraps the screen chrome and renders one
 * [PermissionCardContent] per spec from a pre-derived list. Tests can
 * call this without a `LocalContext`, a `Lifecycle`, or an Activity:
 * grant/permanently-denied state is pre-resolved and the action
 * callback is a no-op stub.
 */
@Composable
internal fun SettingsPermissionsContent(
    navController: NavController,
    specsAndGranted: List<Pair<PermissionSpec, Boolean>>,
    permanentlyDeniedFor: (PermissionSpec) -> Boolean = { false },
    onAction: (PermissionSpec) -> Unit = {},
) {
    val br = LocalBrColors.current
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader("Permissions", onBack = { navController.popBackStack() })

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((spec, granted) in specsAndGranted) {
                    PermissionCardContent(
                        spec = spec,
                        granted = granted,
                        permanentlyDenied = permanentlyDeniedFor(spec),
                        onAction = { onAction(spec) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
internal fun PermissionCard(spec: PermissionSpec, granted: Boolean, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    // Track whether we've ever asked for this spec in this card's
    // lifetime so we can disambiguate "never asked" (rationale=false)
    // from "tapped 'Don't ask again'" (rationale=false, but attempted).
    var requestAttempted by rememberSaveable(spec.title) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onChanged() }
    val permanentlyDenied = !granted && requestAttempted && spec.permissions.isNotEmpty() &&
        activity != null &&
        spec.permissions.all { perm ->
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }
    PermissionCardContent(
        spec = spec,
        granted = granted,
        permanentlyDenied = permanentlyDenied,
        onAction = {
            when {
                spec.permissions.isEmpty() -> {
                    ctx.startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}"),
                        )
                    )
                }
                permanentlyDenied -> {
                    ctx.startActivity(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}"),
                        )
                    )
                }
                else -> {
                    requestAttempted = true
                    launcher.launch(spec.permissions.toTypedArray())
                }
            }
        },
    )
}

/**
 * Stateless leaf — visible to snapshot tests so the visual contract can
 * be locked without an Activity, a permission launcher, or a real
 * `LocalContext`. Callers derive [permanentlyDenied] and provide an
 * [onAction] that routes to the launcher or the Settings intent.
 */
@Composable
internal fun PermissionCardContent(
    spec: PermissionSpec,
    granted: Boolean,
    permanentlyDenied: Boolean,
    onAction: () -> Unit,
) {
    val br = LocalBrColors.current
    val accentColor = when {
        granted -> br.safe
        !spec.required -> br.fgDim
        else -> br.danger
    }
    val borderColor = when {
        granted -> br.safe.copy(alpha = 0.30f)
        !spec.required -> br.hairline
        else -> br.danger.copy(alpha = 0.22f)
    }
    val bg = when {
        granted -> br.safe.copy(alpha = 0.08f)
        !spec.required -> br.bgElev1
        else -> br.danger.copy(alpha = 0.06f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (granted) br.safe.copy(alpha = 0.15f) else br.bgElev2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (granted) Icons.Default.Check
                    else if (spec.required) Icons.Default.Shield
                    else Icons.Default.Visibility,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = spec.title,
                        color = br.fg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    spec.markLabel?.let { Mark(text = it) }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = spec.rationale,
                    color = br.fgMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        if (!granted) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (spec.required) br.brand else androidx.compose.ui.graphics.Color.Transparent)
                    .border(
                        1.dp,
                        if (spec.required) androidx.compose.ui.graphics.Color.Transparent else br.hairline2,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        permanentlyDenied -> "Open App info"
                        spec.required -> "Grant"
                        else -> "Enable"
                    },
                    color = if (spec.required) br.bg else br.fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
