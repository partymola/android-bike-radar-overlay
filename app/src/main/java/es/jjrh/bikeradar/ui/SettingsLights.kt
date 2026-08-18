// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.Manifest
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.CameraLightMode
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarLightMode
import es.jjrh.bikeradar.RideLocationResolver
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs

/**
 * Consolidated day/night light auto-mode screen, covering BOTH supplementary
 * lights in one place: the rear radar tail light and the front dashcam light.
 * Replaces the two former per-light screens.
 *
 * Radar section first - the radar is the always-present core device; the
 * dashcam section is below and degrades to a single "set up your dashcam"
 * deep-link row when no dashcam is owned (so a radar-only rider sees no dead
 * front-light controls). The two auto-mode toggles stay INDEPENDENT (a rider
 * may want auto on one light but not the other; [Prefs.radarLightAutoModeEnabled]
 * documents the independence).
 *
 * Both lights share ONE dependency - ACCESS_COARSE_LOCATION for the
 * sunrise/sunset calc - so there is a SINGLE location re-grant card at the
 * bottom (one launcher), shown when either auto-mode is on and location is not
 * granted. The "radar can't confirm the switch" caveat is scoped to the radar
 * section only (the dashcam light reports its state; the radar does not).
 */
@Composable
fun SettingsLights(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsLightsBody(navController, prefs)
    }
}

/** Which of the four mode pickers is open. One nullable target (instead of a
 *  boolean per picker) so the four pickers across two enum types can never
 *  cross-write each other's pref. */
private enum class LightPicker { RADAR_DAY, RADAR_NIGHT, DASHCAM_DAY, DASHCAM_NIGHT }

@Composable
private fun SettingsLightsBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    var rearAuto by rememberSaveable { mutableStateOf(prefs.radarLightAutoModeEnabled) }
    var radarDay by rememberSaveable { mutableStateOf(prefs.radarLightDayMode) }
    var radarNight by rememberSaveable { mutableStateOf(prefs.radarLightNightMode) }
    var frontAuto by rememberSaveable { mutableStateOf(prefs.autoLightModeEnabled) }
    var dashcamDay by rememberSaveable { mutableStateOf(prefs.cameraLightDayMode) }
    var dashcamNight by rememberSaveable { mutableStateOf(prefs.cameraLightNightMode) }
    var openPicker by rememberSaveable { mutableStateOf<LightPicker?>(null) }
    val dashcamOwnership = prefs.dashcamOwnership

    // Both lights' auto-mode depends on ACCESS_COARSE_LOCATION (sunrise/sunset,
    // else a London fallback). One shared re-grant card, one launcher; the body
    // owns it and the stateless leaf takes it as a slot (snapshot tests pass a
    // launcher-free card). Rationale names both lights, not one.
    val locSpec = remember {
        PERMISSIONS.first { Manifest.permission.ACCESS_COARSE_LOCATION in it.permissions }
            .copy(rationaleRes = R.string.settings_lights_loc_rationale)
    }
    var locPermTick by rememberSaveable { mutableStateOf(0) }
    val locGranted = remember(locPermTick) { isSpecGranted(ctx, locSpec) }

    val manualLocation = rememberManualLocation(prefs)

    SettingsLightsContent(
        onBack = { navController.popBackStack() },
        rearAuto = rearAuto,
        radarDay = radarDay,
        radarNight = radarNight,
        dashcamOwnership = dashcamOwnership,
        frontAuto = frontAuto,
        dashcamDay = dashcamDay,
        dashcamNight = dashcamNight,
        locGranted = locGranted,
        onRearAutoChanged = { v ->
            rearAuto = v
            prefs.radarLightAutoModeEnabled = v
        },
        onRadarDayClick = { openPicker = LightPicker.RADAR_DAY },
        onRadarNightClick = { openPicker = LightPicker.RADAR_NIGHT },
        onFrontAutoChanged = { v ->
            frontAuto = v
            prefs.autoLightModeEnabled = v
        },
        onDashcamDayClick = { openPicker = LightPicker.DASHCAM_DAY },
        onDashcamNightClick = { openPicker = LightPicker.DASHCAM_NIGHT },
        onSetUpDashcam = { navController.navigate("settings/dashcam") },
        locationCard = {
            PermissionCard(
                spec = locSpec,
                granted = locGranted,
                onChanged = { locPermTick++ },
                alternative = locationAlternativeFor(locSpec, manualLocation),
            )
        },
    )

    ManualLocationDialog(manualLocation)

    when (openPicker) {
        LightPicker.RADAR_DAY -> RadarModePickerDialog(
            title = stringResource(R.string.settings_lights_daytime_mode),
            current = radarDay,
            onSelect = {
                radarDay = it
                prefs.radarLightDayMode = it
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        LightPicker.RADAR_NIGHT -> RadarModePickerDialog(
            title = stringResource(R.string.settings_lights_night_mode),
            current = radarNight,
            onSelect = {
                radarNight = it
                prefs.radarLightNightMode = it
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        LightPicker.DASHCAM_DAY -> DashcamModePickerDialog(
            title = stringResource(R.string.settings_lights_daytime_mode),
            current = dashcamDay,
            onSelect = {
                dashcamDay = it
                prefs.cameraLightDayMode = it
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        LightPicker.DASHCAM_NIGHT -> DashcamModePickerDialog(
            title = stringResource(R.string.settings_lights_night_mode),
            current = dashcamNight,
            onSelect = {
                dashcamNight = it
                prefs.cameraLightNightMode = it
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        null -> {}
    }
}

/**
 * Stateless leaf so snapshot tests can render without a `LocalContext`, a
 * permission launcher, or an Activity. The shared location card is a slot
 * gated by `(rearAuto || frontAuto)`; the dashcam section is gated by
 * ownership.
 */
@Composable
internal fun SettingsLightsContent(
    onBack: () -> Unit,
    rearAuto: Boolean,
    radarDay: RadarLightMode,
    radarNight: RadarLightMode,
    dashcamOwnership: DashcamOwnership,
    frontAuto: Boolean,
    dashcamDay: CameraLightMode,
    dashcamNight: CameraLightMode,
    locGranted: Boolean,
    onRearAutoChanged: (Boolean) -> Unit = {},
    onRadarDayClick: () -> Unit = {},
    onRadarNightClick: () -> Unit = {},
    onFrontAutoChanged: (Boolean) -> Unit = {},
    onDashcamDayClick: () -> Unit = {},
    onDashcamNightClick: () -> Unit = {},
    onSetUpDashcam: () -> Unit = {},
    locationCard: @Composable () -> Unit = {},
) {
    val br = LocalBrColors.current
    val dashcamOwned = dashcamOwnership == DashcamOwnership.YES
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader(stringResource(R.string.settings_lights_title), onBack = onBack)

            SettingsSectionLabel(stringResource(R.string.settings_lights_radar_section))
            SettingsRowGroup {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_lights_auto_switch),
                    subtitle = stringResource(R.string.settings_lights_radar_auto_sub),
                    checked = rearAuto,
                    onCheckedChange = onRearAutoChanged,
                    leadingIcon = Icons.Default.WbSunny,
                    leadingTint = br.brand,
                    isLast = false,
                )
                SettingsRow(
                    icon = Icons.Default.LightMode,
                    iconTint = if (rearAuto) br.brand else br.fgMuted,
                    title = stringResource(R.string.settings_lights_daytime_mode),
                    subtitle = radarDay.displayName(),
                    onClick = onRadarDayClick,
                    chevron = false,
                    clickable = rearAuto,
                    enabled = rearAuto,
                    isLast = false,
                )
                SettingsRow(
                    icon = Icons.Default.DarkMode,
                    iconTint = if (rearAuto) br.brand else br.fgMuted,
                    title = stringResource(R.string.settings_lights_night_mode),
                    subtitle = stringResource(R.string.settings_lights_night_sub, radarNight.displayName()),
                    onClick = onRadarNightClick,
                    chevron = false,
                    clickable = rearAuto,
                    enabled = rearAuto,
                    isLast = true,
                )
            }

            // Honest limitation, radar only: it reports its selected cycle slot,
            // not our mode-override, so the app can't confirm the switch landed.
            // Calm one-liner - the built-in rear light is primary; this is a
            // supplementary light, no alarm warranted.
            if (rearAuto) {
                Text(
                    text = stringResource(R.string.settings_lights_radar_caveat),
                    color = br.fgMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_lights_dashcam_section))
            if (dashcamOwned) {
                SettingsRowGroup {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_lights_auto_switch),
                        subtitle = stringResource(R.string.settings_lights_dashcam_auto_sub),
                        checked = frontAuto,
                        onCheckedChange = onFrontAutoChanged,
                        leadingIcon = Icons.Default.WbSunny,
                        leadingTint = br.brand,
                        isLast = false,
                    )
                    SettingsRow(
                        icon = Icons.Default.LightMode,
                        iconTint = if (frontAuto) br.brand else br.fgMuted,
                        title = stringResource(R.string.settings_lights_daytime_mode),
                        subtitle = dashcamDay.displayName(),
                        onClick = onDashcamDayClick,
                        chevron = false,
                        clickable = frontAuto,
                        enabled = frontAuto,
                        isLast = false,
                    )
                    SettingsRow(
                        icon = Icons.Default.DarkMode,
                        iconTint = if (frontAuto) br.brand else br.fgMuted,
                        title = stringResource(R.string.settings_lights_night_mode),
                        subtitle = stringResource(R.string.settings_lights_night_sub, dashcamNight.displayName()),
                        onClick = onDashcamNightClick,
                        chevron = false,
                        clickable = frontAuto,
                        enabled = frontAuto,
                        isLast = true,
                    )
                }
            } else {
                // No dashcam owned: a single deep-link row instead of dead
                // controls, reusing the home screen's ownership vocabulary.
                SettingsRowGroup {
                    SettingsRow(
                        icon = Icons.Default.Videocam,
                        iconTint = br.dashcam,
                        title = stringResource(R.string.settings_lights_dashcam_row_title),
                        subtitle = when (dashcamOwnership) {
                            DashcamOwnership.NO -> stringResource(R.string.settings_lights_dashcam_no)
                            else -> stringResource(R.string.settings_lights_dashcam_setup)
                        },
                        onClick = onSetUpDashcam,
                        isLast = true,
                    )
                }
            }

            // Location for the sunrise/sunset calc, shown whenever a light
            // auto-mode is on. The permission card carries the whole "grant or
            // enter coordinates" choice, a manual-override entry even when
            // granted, and the manual-set value row; London is the silent last
            // resort if the rider configures neither.
            if (rearAuto || frontAuto) {
                SettingsSectionLabel(stringResource(R.string.settings_lights_loc_section))
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    locationCard()
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** Tappable ± that flips a coordinate field's sign, so a minus is always
 *  reachable - many soft keyboards' decimal pads omit the minus key, which
 *  would otherwise lock out every rider west of Greenwich or south of the
 *  equator. */
@Composable
private fun SignToggle(onToggle: () -> Unit) {
    val desc = stringResource(R.string.settings_coord_sign_toggle)
    Text(
        text = "±",
        fontSize = 20.sp,
        modifier = Modifier
            .clickable(onClick = onToggle)
            .semantics { contentDescription = desc }
            .padding(horizontal = 12.dp),
    )
}

/**
 * Coordinate-entry dialog for a GPS-decliner. Every keystroke and paste flows
 * through [RideLocationResolver.sanitizeCoordinateInput] (single leading `-`,
 * digits, one `.`; comma and Unicode-minus normalised; length-capped), the
 * fields are `singleLine` (no newlines), and Save is gated on
 * [RideLocationResolver] validating both as finite and in range. The Composable
 * never constructs a coordinate itself.
 */
@Composable
internal fun CoordinateEntryDialog(
    initialLat: Double?,
    initialLon: Double?,
    onSave: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val br = LocalBrColors.current
    var latText by rememberSaveable { mutableStateOf(initialLat?.let { RideLocationResolver.formatCoordinate(it) } ?: "") }
    var lonText by rememberSaveable { mutableStateOf(initialLon?.let { RideLocationResolver.formatCoordinate(it) } ?: "") }

    val latVal = RideLocationResolver.parseCoordinate(latText)
    val lonVal = RideLocationResolver.parseCoordinate(lonText)
    val latOk = RideLocationResolver.isValidLat(latVal)
    val lonOk = RideLocationResolver.isValidLon(lonVal)
    val latError = latText.isNotBlank() && !latOk
    val lonError = lonText.isNotBlank() && !lonOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_coord_title), fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = RideLocationResolver.sanitizeCoordinateInput(it) },
                    label = { Text(stringResource(R.string.settings_coord_lat_label)) },
                    singleLine = true,
                    isError = latError,
                    supportingText = if (latError) {
                        { Text(stringResource(R.string.settings_coord_lat_error)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = { SignToggle { latText = RideLocationResolver.toggleSign(latText) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = RideLocationResolver.sanitizeCoordinateInput(it) },
                    label = { Text(stringResource(R.string.settings_coord_lon_label)) },
                    singleLine = true,
                    isError = lonError,
                    supportingText = if (lonError) {
                        { Text(stringResource(R.string.settings_coord_lon_error)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = { SignToggle { lonText = RideLocationResolver.toggleSign(lonText) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_coord_hint),
                    color = br.fgMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (latVal != null && lonVal != null) onSave(latVal, lonVal) },
                enabled = latOk && lonOk,
            ) { Text(stringResource(R.string.settings_coord_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RadarModePickerDialog(
    title: String,
    current: RadarLightMode,
    onSelect: (RadarLightMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                RadarLightMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Text(text = mode.displayName(), fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun DashcamModePickerDialog(
    title: String,
    current: CameraLightMode,
    onSelect: (CameraLightMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                CameraLightMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Text(text = mode.displayName(), fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
