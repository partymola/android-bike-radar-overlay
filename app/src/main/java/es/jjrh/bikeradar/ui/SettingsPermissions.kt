// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RideLocationResolver
import es.jjrh.bikeradar.data.Prefs
import android.provider.Settings as AndroidSettings

@Composable
fun SettingsPermissions(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsPermissionsBody(navController, prefs)
    }
}

@Composable
private fun SettingsPermissionsBody(navController: NavController, prefs: Prefs) {
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

    var manualLat by rememberSaveable { mutableStateOf(prefs.manualLocationLat) }
    var manualLon by rememberSaveable { mutableStateOf(prefs.manualLocationLon) }
    var showCoordDialog by rememberSaveable { mutableStateOf(false) }
    val manualSummary = remember(manualLat, manualLon) {
        RideLocationResolver.summary(manualLat, manualLon)
    }

    // The body runs `PermissionCard` per spec so each card gets its own
    // permission-launcher (one launcher per call-site). The stateless
    // [SettingsPermissionsContent] mirrors this chrome for snapshot
    // tests but uses [PermissionCardContent] directly with stub actions.
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.settings_perm_title), onBack = { navController.popBackStack() })

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((spec, granted) in states) {
                    PermissionCard(
                        spec = spec,
                        granted = granted,
                        onChanged = { refreshTick++ },
                        alternative = locationAlternative(
                            spec = spec,
                            manualLocationSummary = manualSummary,
                            onEnterCoordinates = { showCoordDialog = true },
                            onClearCoordinates = {
                                manualLat = null
                                manualLon = null
                                prefs.setManualLocation(null, null)
                            },
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    if (showCoordDialog) {
        CoordinateEntryDialog(
            initialLat = manualLat,
            initialLon = manualLon,
            onSave = { lat, lon ->
                manualLat = lat
                manualLon = lon
                prefs.setManualLocation(lat, lon)
                showCoordDialog = false
            },
            onDismiss = { showCoordDialog = false },
        )
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
    manualLocationSummary: String? = null,
    onEnterCoordinates: () -> Unit = {},
    onClearCoordinates: () -> Unit = {},
) {
    val br = LocalBrColors.current
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(stringResource(R.string.settings_perm_title), onBack = { navController.popBackStack() })

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
                        alternative = locationAlternative(
                            spec = spec,
                            manualLocationSummary = manualLocationSummary,
                            onEnterCoordinates = onEnterCoordinates,
                            onClearCoordinates = onClearCoordinates,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/**
 * Optional "or do it another way" action folded into a [PermissionCard]. The
 * card then presents granting the permission and this alternative as equal
 * peers - two full-width buttons separated by an "or" divider - and, once the
 * alternative is set, a satisfied value row in its place. Used for the location
 * permission, whose sunrise/sunset need can equally be met by manual
 * coordinates. Kept generic (string ids + lambdas) so no feature knowledge
 * leaks into the shared card.
 */
internal data class PermissionAlternative(
    @StringRes val actionLabelRes: Int,
    @StringRes val setTitleRes: Int,
    val summary: String?,
    val onEnter: () -> Unit,
    val onClear: () -> Unit,
)

/**
 * The coordinates alternative for the location card, null for every other spec.
 *
 * Every surface rendering [PERMISSIONS] must build the alternative here rather
 * than constructing its own. A surface that skips it renders a location card
 * whose rationale offers coordinate entry as the way out of the London
 * fallback, with nothing on the card to enter or read them - and the rider's
 * already-set coordinates invisible. It compiles, and the goldens for this
 * screen cannot see it (see `SettingsPermissionsCoordinatesTest`).
 */
internal fun locationAlternative(
    spec: PermissionSpec,
    manualLocationSummary: String?,
    onEnterCoordinates: () -> Unit,
    onClearCoordinates: () -> Unit,
): PermissionAlternative? = if (Manifest.permission.ACCESS_COARSE_LOCATION in spec.permissions) {
    PermissionAlternative(
        actionLabelRes = R.string.settings_lights_loc_enter_coords,
        setTitleRes = R.string.settings_lights_loc_manual_set_title,
        summary = manualLocationSummary,
        onEnter = onEnterCoordinates,
        onClear = onClearCoordinates,
    )
} else {
    null
}

@Composable
internal fun PermissionCard(
    spec: PermissionSpec,
    granted: Boolean,
    onChanged: () -> Unit,
    alternative: PermissionAlternative? = null,
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    // Track whether we've ever asked for this spec in this card's
    // lifetime so we can disambiguate "never asked" (rationale=false)
    // from "tapped 'Don't ask again'" (rationale=false, but attempted).
    var requestAttempted by rememberSaveable(spec.titleRes) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onChanged() }
    val permanentlyDenied = !granted &&
        requestAttempted &&
        spec.permissions.isNotEmpty() &&
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
                        ),
                    )
                }
                permanentlyDenied -> {
                    ctx.startActivity(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}"),
                        ),
                    )
                }
                else -> {
                    requestAttempted = true
                    launcher.launch(spec.permissions.toTypedArray())
                }
            }
        },
        alternative = alternative,
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
    alternative: PermissionAlternative? = null,
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
                    imageVector = if (granted) {
                        Icons.Default.Check
                    } else if (spec.required) {
                        Icons.Default.Shield
                    } else {
                        Icons.Default.Visibility
                    },
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
                        text = stringResource(spec.titleRes),
                        color = br.fg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    spec.markLabelRes?.let { Mark(text = stringResource(it)) }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(spec.rationaleRes),
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
                        permanentlyDenied -> stringResource(R.string.settings_perm_open_app_info)
                        spec.required -> stringResource(R.string.settings_perm_grant)
                        else -> stringResource(R.string.settings_perm_enable)
                    },
                    color = if (spec.required) br.bg else br.fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // Optional alternative (e.g. manual coordinates for the location perm):
        // once set -> a satisfied value row; otherwise, when not granted, an
        // "or" divider and a peer outline button beneath the primary action.
        alternative?.let { alt ->
            when {
                alt.summary != null -> {
                    // When the permission is still ungranted, the "or" divider keeps
                    // the (above) Enable button and this value row reading as the two
                    // alternatives they are, rather than two unrelated stacked items.
                    if (!granted) {
                        Spacer(modifier = Modifier.height(8.dp))
                        PermissionOrDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    PermissionAlternativeValueRow(alt)
                }
                !granted -> {
                    // Two peer ways to supply location: Enable (above) or coordinates.
                    Spacer(modifier = Modifier.height(8.dp))
                    PermissionOrDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    PermissionOutlineButton(
                        label = stringResource(alt.actionLabelRes),
                        onClick = alt.onEnter,
                    )
                }
                else -> {
                    // Permission granted, no manual override yet: still offer to
                    // enter coordinates (manual coordinates win over GPS in the
                    // resolver, so this is a usable override).
                    Spacer(modifier = Modifier.height(12.dp))
                    PermissionOutlineButton(
                        label = stringResource(alt.actionLabelRes),
                        onClick = alt.onEnter,
                    )
                }
            }
        }
    }
}

/** Full-width "or" separator (a hairline on each side of the OR caption),
 *  using the same uppercase-mono caption voice as the OPTIONAL mark so it
 *  reads as chrome, not content. */
@Composable
private fun PermissionOrDivider() {
    val br = LocalBrColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(br.hairline))
        Mark(text = stringResource(R.string.common_or), modifier = Modifier.padding(horizontal = 10.dp))
        Box(modifier = Modifier.weight(1f).height(1.dp).background(br.hairline))
    }
}

/** Peer of the optional-spec action button (transparent, hairline border). */
@Composable
private fun PermissionOutlineButton(label: String, onClick: () -> Unit) {
    val br = LocalBrColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, br.hairline2, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = br.fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Satisfied state for an alternative that is set: a green-tinted row echoing
 *  the card's own "granted" vocabulary, with change/clear actions. */
@Composable
private fun PermissionAlternativeValueRow(alt: PermissionAlternative) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(br.safe.copy(alpha = 0.08f))
            .border(1.dp, br.safe.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Place,
            contentDescription = null,
            tint = br.safe,
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(alt.setTitleRes),
                color = br.fg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = alt.summary ?: "",
                color = br.fgMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = stringResource(R.string.common_change),
            color = br.brand,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = alt.onEnter).padding(8.dp),
        )
        Text(
            text = stringResource(R.string.common_clear),
            color = br.fgMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = alt.onClear).padding(8.dp),
        )
    }
}
