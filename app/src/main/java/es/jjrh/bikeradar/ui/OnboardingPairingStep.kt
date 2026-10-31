// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.bluetooth.BluetoothManager
import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.RadarSelection
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.delay
import android.provider.Settings as AndroidSettings

// ── Step 2 - Pairing ─────────────────────────────────────────────────

@Composable
internal fun PairingStep(
    navController: NavController,
    prefs: Prefs,
    onFinish: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())

    // RadarSelection's answers, so a pinned radar whose name does not match
    // reads as paired here too, and a front camera alone does not.
    var allBonded by remember { mutableStateOf(RadarSelection.bondedDevices(ctx)) }
    var askForNearby by remember { mutableStateOf(RadarSelection.nearbyDeniedHidesPairedList(ctx, allBonded)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Read before the first wait, so a rider back from another app sees
            // a fresh list at once.
            while (true) {
                allBonded = RadarSelection.bondedDevices(ctx)
                askForNearby = RadarSelection.nearbyDeniedHidesPairedList(ctx, allBonded)
                delay(2_000)
            }
        }
    }
    val radarBonded = RadarSelection.setupHasRadar(allBonded, prefsSnap.radarMac)
    val activeRadar = RadarSelection.activeRadar(allBonded, prefsSnap.radarMac)
    val eBikeMac = RadarSelection.eBikeMac(allBonded)

    PairingStepContent(
        radarBonded = radarBonded,
        radarLocalName = activeRadar?.name,
        radarMac = activeRadar?.mac,
        pickDevices = if (RadarSelection.setupOffersPick(allBonded, prefsSnap.radarMac)) {
            RadarSelection.pickableDevices(allBonded, listOf(prefsSnap.dashcamMac, eBikeMac))
        } else {
            emptyList()
        },
        onPick = { RadarSelection.pin(prefs, it) },
        pinnedMac = prefsSnap.radarMac,
        askForNearby = askForNearby,
        onGrantPermissions = { navController.navigate("settings/permissions") },
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
    /** The devices offered as the radar, or none
     *  ([RadarSelection.setupOffersPick]): a rider whose radar's name can only
     *  be a rear unit never meets the list of watches and headphones, and one
     *  who picked from it keeps it, with the pick marked, until the pick is
     *  such a radar. */
    pickDevices: List<RadarSelection.BondedRadar> = emptyList(),
    onPick: (RadarSelection.BondedRadar) -> Unit = {},
    /** The rider's pick, marked in the list. Not [radarMac], which can be a
     *  device the app chose by name. */
    pinnedMac: String? = null,
    /** [RadarSelection.nearbyDeniedHidesPairedList]: the card asks for the
     *  permission in place of the radar's, since the radar may already be paired. */
    askForNearby: Boolean = false,
    onGrantPermissions: () -> Unit = {},
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
                mark = stringResource(R.string.onboarding_step_3_of_5),
                title = stringResource(R.string.onboarding_pair_title),
                sub = stringResource(if (askForNearby) R.string.onboarding_pair_sub_no_permission else R.string.onboarding_pair_sub),
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Radar device row (always required). When already bonded
                // we hide the CTA and the detail-prefix - the chip alone
                // signals state, and re-pairing belongs in Settings, not
                // onboarding. Bonded with no single radar to name (two
                // paired, none picked), it says what Settings says.
                val radarNamed = radarLocalName ?: radarMac
                if (askForNearby) {
                    IntentCard(
                        title = stringResource(R.string.main_status_permissions_title),
                        subtitle = stringResource(R.string.main_status_permissions_nearby_sub),
                        filled = true,
                        onClick = onGrantPermissions,
                    )
                } else {
                    DeviceRow(
                        icon = Icons.Default.Sensors,
                        tint = br.brand,
                        title = stringResource(R.string.onboarding_pair_radar_title),
                        optionalLabel = false,
                        bonded = radarBonded,
                        detail = if (radarBonded) {
                            radarNamed ?: stringResource(R.string.settings_radardev_not_selected)
                        } else {
                            stringResource(R.string.onboarding_pair_radar_detail_unpaired)
                        },
                        detailIsDeviceName = radarNamed != null,
                        detailHint = if (!radarBonded) {
                            stringResource(R.string.onboarding_pair_radar_hint)
                        } else {
                            null
                        },
                        primaryCta = if (!radarBonded) stringResource(R.string.onboarding_pair_open_bt_settings) else null,
                        primaryCtaIcon = if (!radarBonded) Icons.Default.Bluetooth else null,
                        onPrimary = onOpenBluetoothSettings,
                    )
                }
                var pickExpanded by rememberSaveable { mutableStateOf(false) }
                RadarNotListedHatch(
                    others = pickDevices,
                    chosenMac = pinnedMac,
                    expanded = pickExpanded,
                    onToggle = { pickExpanded = !pickExpanded },
                    onSelectRadar = onPick,
                    // Once the card above reads PAIRED the pick is a choice of
                    // which device, not a question of whether one is paired.
                    title = if (radarBonded) {
                        stringResource(R.string.settings_radardev_choose_radar)
                    } else {
                        stringResource(R.string.onboarding_pair_radar_other_title)
                    },
                    subtitle = if (radarBonded) {
                        stringResource(R.string.onboarding_pair_radar_choose_sub)
                    } else {
                        stringResource(R.string.onboarding_pair_radar_other_sub)
                    },
                    bordered = true,
                    // The step's column already pads and spaces its cards.
                    paddingHorizontal = 0.dp,
                    topGap = 0.dp,
                )

                // Dashcam row, three sub-states matching the JSX.
                when (dashcamOwnership) {
                    DashcamOwnership.UNANSWERED -> DashcamUnansweredCard(
                        // Pick device opens the picker without flipping
                        // ownership first - that way a brief recompose into
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
                        title = stringResource(R.string.onboarding_pair_dashcam_title),
                        optionalLabel = true,
                        subtitle = stringResource(R.string.onboarding_pair_dashcam_no_subtitle),
                        bonded = false,
                        detail = stringResource(R.string.onboarding_pair_dashcam_no_detail),
                        primaryCta = stringResource(R.string.onboarding_pair_dashcam_have_one),
                        primaryCtaIcon = null,
                        onPrimary = onDashcamReclaim,
                    )
                    DashcamOwnership.YES -> {
                        val picked = dashcamMac != null
                        DeviceRow(
                            icon = Icons.Default.Videocam,
                            tint = br.dashcam,
                            title = stringResource(R.string.onboarding_pair_dashcam_title),
                            optionalLabel = true,
                            subtitle = if (picked) stringResource(R.string.onboarding_pair_dashcam_yes_subtitle) else null,
                            bonded = picked,
                            detail = if (picked) {
                                stringResource(
                                    R.string.onboarding_pair_dashcam_picked_detail,
                                    dashcamDisplayName ?: stringResource(R.string.onboarding_pair_dashcam_picked_fallback),
                                    dashcamMac,
                                )
                            } else {
                                stringResource(R.string.onboarding_pair_dashcam_pick_detail)
                            },
                            primaryCta =
                            if (picked) {
                                stringResource(R.string.onboarding_pair_change_device)
                            } else {
                                stringResource(R.string.onboarding_pair_pick_device)
                            },
                            primaryCtaIcon = null,
                            onPrimary = onPickDashcam,
                            extraAction = stringResource(R.string.onboarding_pair_dont_have_one),
                            onExtra = onDashcamSkip,
                            pairedLabelRes = R.string.ui_chip_paired_cam,
                        )
                    }
                }

                // Hint shown in UNANSWERED / YES states. NO already says
                // the same thing in its detail box, so we'd be repeating
                // ourselves.
                if (dashcamOwnership != DashcamOwnership.NO) {
                    // Whole sentence is one translatable unit with a %1$s slot
                    // for the emphasised "Settings -> Dashcam" link, so word
                    // order stays correct in any language; the bold span is
                    // re-applied over the substituted link text.
                    val link = stringResource(R.string.onboarding_pair_settings_dashcam)
                    val full = stringResource(R.string.onboarding_pair_comeback, link)
                    val hintText = androidx.compose.ui.text.buildAnnotatedString {
                        append(full)
                        val start = full.indexOf(link)
                        if (start >= 0) {
                            addStyle(
                                androidx.compose.ui.text.SpanStyle(
                                    color = br.fg,
                                    fontWeight = FontWeight.Medium,
                                ),
                                start,
                                start + link.length,
                            )
                        }
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
                StepPrivacyNote(
                    heading = stringResource(R.string.onboarding_pair_privacy_heading),
                    bullets = listOf(
                        stringResource(R.string.onboarding_pair_bullet_devices),
                        stringResource(R.string.onboarding_pair_bullet_saves),
                        stringResource(R.string.onboarding_pair_bullet_nothing_sent),
                    ),
                )
            }
        }
        // Footer: always-enabled Continue, with warning text when radar
        // hasn't bonded yet - the user can configure HA telemetry
        // without the overlay so blocking onboarding on pairing would
        // close off legitimate use cases. The eBike step follows.
        Column(modifier = Modifier.fillMaxWidth()) {
            // Discovery hint: the experimental features live behind a
            // single Settings entry so onboarding stays minimal.
            Text(
                text = stringResource(R.string.onboarding_pair_more_features),
                color = br.fgDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
            )
            if (!radarBonded && !askForNearby) {
                Text(
                    text = stringResource(R.string.onboarding_pair_radar_later),
                    color = br.fgDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            FooterCta(label = stringResource(R.string.common_continue), enabled = true, onClick = onFinish)
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
                    text = stringResource(R.string.onboarding_pair_dashcam_title),
                    color = br.fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Mark(stringResource(R.string.common_optional))
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
                text = stringResource(R.string.onboarding_pair_dashcam_unanswered_detail),
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
                Text(
                    text = stringResource(R.string.onboarding_pair_pick_device),
                    color = br.bg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
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
                Text(
                    text = stringResource(R.string.onboarding_pair_dont_have_one),
                    color = br.fg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
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
    /** False when a bonded row's [detail] is a phrase, not a device name: it
     *  then renders as prose and is not announced as "Paired with ...". */
    detailIsDeviceName: Boolean = true,
    detailHint: String? = null,
    primaryCta: String?,
    primaryCtaIcon: ImageVector?,
    onPrimary: () -> Unit,
    extraAction: String? = null,
    onExtra: (() -> Unit)? = null,
    /** Paired-chip wording for THIS row's device, which carries its own
     *  gender in Spanish. A card about a feminine device passes the
     *  feminine string; the default suits the radar. */
    @StringRes pairedLabelRes: Int = R.string.ui_chip_paired,
) {
    val br = LocalBrColors.current
    // Hoisted out of the semantics lambda below (not a @Composable scope).
    val pairedWithDesc = stringResource(R.string.onboarding_device_paired_with, detail)
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
                    if (optionalLabel) Mark(stringResource(R.string.common_optional))
                }
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = subtitle, color = br.fgDim, fontSize = 11.sp)
                }
            }
            if (bonded) PairedChip(labelRes = pairedLabelRes)
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
            val asName = bonded && detailIsDeviceName
            Text(
                text = detail,
                color = br.fgMuted,
                fontFamily = if (asName) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (asName) 11.sp else 12.sp,
                lineHeight = if (asName) 16.sp else 17.sp,
                letterSpacing = if (asName) 0.3.sp else 0.sp,
                modifier = if (asName) {
                    // Without context the screen-reader hears just the bare
                    // device name in monospace. The visual PairedChip
                    // alongside isn't part of this Text's a11y subtree.
                    Modifier.semantics { contentDescription = pairedWithDesc }
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
