// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.BatteryScanReceiver
import es.jjrh.bikeradar.BatteryStateBus
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.delay

/**
 * Mockup-fidelity dashcam picker. Replaces the V1
 * `DashcamPickerDialog` modal in the redesigned UX.
 *
 * Layout per `dashcam-picker.jsx`:
 *  - Top bar: chevron-back + "Select dashcam"
 *  - Brand-tinted explainer banner with `i` badge
 *  - Scrollable list:
 *      - "None" radio row always first
 *      - "LIKELY MATCHES" section + rows (with `VUE` tag)
 *      - "OTHER PAIRED DEVICES" section + rows
 *      - Refresh-every-2s indicator with pulsing dot
 *      - Optional "Pair a new device" CTA
 *  - Sticky footer: Cancel (ghost) + Save (brand)
 *
 * Selection writes prefs.dashcamMac/.dashcamDisplayName and
 * (when fromOnboarding) prefs.dashcamWarnWhenOff = true on a
 * non-null pick.
 */
@Composable
fun DashcamPickerSheet(
    navController: NavController,
    prefs: Prefs,
    fromOnboarding: Boolean,
) {
    NextTheme {
        DashcamPickerSheetBody(navController, prefs, fromOnboarding)
    }
}

@Composable
private fun DashcamPickerSheetBody(
    navController: NavController,
    prefs: Prefs,
    fromOnboarding: Boolean,
) {
    val ctx = LocalContext.current
    val br = LocalBrColors.current
    val batteryEntries by BatteryStateBus.entries.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var devices by remember { mutableStateOf(listBondedNext(ctx, batteryEntries.keys)) }
    // Bonded-device list refreshes only while the picker is on
    // screen — no point hitting BluetoothAdapter while paused.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(2_000L)
                devices = listBondedNext(ctx, batteryEntries.keys)
            }
        }
    }

    // initialMac snapshots the at-open value so Save stays disabled
    // until the user actually changes the selection. selectedMac is
    // saveable so a rotation mid-pick doesn't reset the choice;
    // initialMac stays in plain remember because rememberSaveable's
    // T : Any rules out a top-level String? slot, and the value is
    // only used as a comparison anchor.
    val initialMac = remember { prefs.dashcamMac }
    var selectedMac by rememberSaveable { mutableStateOf(initialMac) }
    val saveEnabled = selectedMac != initialMac

    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar (matches NextSettingsHeader)
            NextSettingsHeader("Select dashcam", onBack = { navController.popBackStack() })

            // Explainer banner
            ExplainerBanner()

            Spacer(modifier = Modifier.height(8.dp))

            val (likely, other) = devices.partition { it.likely }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                item("none") {
                    PickerRowNext(
                        title = "None — I don't have one",
                        subtitle = null,
                        tag = null,
                        selected = selectedMac == null,
                        onSelect = { selectedMac = null },
                    )
                }

                if (likely.isNotEmpty()) {
                    item("likely-header") {
                        PickerSectionLabel("Likely matches")
                    }
                    items(likely, key = { "l-" + it.mac }) { d ->
                        PickerRowNext(
                            title = d.name,
                            subtitle = d.mac,
                            tag = "Vue",
                            selected = selectedMac.equals(d.mac, ignoreCase = true),
                            onSelect = { selectedMac = d.mac },
                        )
                    }
                }

                if (other.isNotEmpty()) {
                    item("other-header") {
                        PickerSectionLabel("Other paired devices")
                    }
                    items(other, key = { "o-" + it.mac }) { d ->
                        PickerRowNext(
                            title = d.name,
                            subtitle = d.mac,
                            tag = null,
                            selected = selectedMac.equals(d.mac, ignoreCase = true),
                            onSelect = { selectedMac = d.mac },
                        )
                    }
                }

                item("refresh") {
                    Spacer(modifier = Modifier.height(16.dp))
                    RefreshIndicator()
                }

                item("pair-cta") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, br.hairline2, RoundedCornerShape(10.dp))
                            .clickable {
                                ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = br.fgMuted,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Pair a new device in Android Settings",
                                color = br.fgMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // Sticky footer
            FooterButtons(
                saveEnabled = saveEnabled,
                onCancel = { navController.popBackStack() },
                onSave = {
                    val name = devices.firstOrNull { it.mac.equals(selectedMac, ignoreCase = true) }?.name
                    prefs.dashcamMac = selectedMac
                    prefs.dashcamDisplayName = name
                    if (selectedMac == null) {
                        prefs.dashcamWarnWhenOff = false
                    } else if (fromOnboarding) {
                        prefs.dashcamWarnWhenOff = true
                    }
                    navController.popBackStack()
                },
            )
        }
    }
}

@Composable
private fun ExplainerBanner() {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(br.brand.copy(alpha = 0.08f))
            .border(1.dp, br.brandGlow, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // `i` info badge
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(br.brand),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "i",
                color = br.bg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "Likely matches are paired devices whose name fits the Vue/Varia pattern or that have advertised battery this session.",
            color = br.fgMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun PickerSectionLabel(text: String) {
    val br = LocalBrColors.current
    Text(
        text = text.uppercase(),
        color = br.fgDim,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
    )
}

@Composable
private fun PickerRowNext(
    title: String,
    subtitle: String?,
    tag: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val br = LocalBrColors.current
    val bg = if (selected) br.brand.copy(alpha = 0.10f) else Color.Transparent
    val borderColor = if (selected) br.brand else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Custom radio: 18dp circle with 2dp border, brand if selected
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) br.brand else br.fgDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(br.brand),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = br.fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (tag != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(br.dashcam.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = tag.uppercase(),
                            color = br.dashcam,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp,
                            letterSpacing = 0.6.sp,
                        )
                    }
                }
            }
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = br.fgDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    letterSpacing = 0.3.sp,
                )
            }
        }
    }
}

@Composable
private fun RefreshIndicator() {
    val br = LocalBrColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(color = br.fgDim, pulse = true, size = 6.dp)
        Text(
            text = "REFRESHING EVERY 2 s",
            color = br.fgDim,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
private fun FooterButtons(
    saveEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(br.bg)
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, br.hairline2, RoundedCornerShape(12.dp))
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Cancel", color = br.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            modifier = Modifier
                .weight(1.4f)
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (saveEnabled) br.brand else br.bgElev2)
                .clickable(enabled = saveEnabled, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Save",
                color = if (saveEnabled) br.bg else br.fgDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── Bonded BLE list helper (mirrors V1's listBondedBle) ───────────────

@SuppressLint("MissingPermission")
private fun listBondedNext(ctx: Context, seenSlugs: Set<String>): List<DashcamCandidate> {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bonded: Set<BluetoothDevice> = try {
        mgr?.adapter?.bondedDevices ?: emptySet()
    } catch (_: SecurityException) { emptySet() }

    val slugOf = { name: String -> name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') }

    return bonded.mapNotNull { d ->
        val name = try { d.name } catch (_: SecurityException) { null } ?: return@mapNotNull null
        // The dashcam picker should never offer the rear radar as a
        // candidate. The radar-matching heuristic catches RTL- /
        // RearVue-named devices (radar-only) and "varia" devices that
        // are NOT also dashcam-named. Garmin's Varia line includes
        // both radar (RTL...) and dashcam (Vue / VUE...) so we only
        // exclude when the name is unambiguously radar.
        val n = name.lowercase()
        val isUnambiguousRadar = n.contains("rearvue") ||
            n.contains("rtl") ||
            (n.contains("varia") && !n.contains("vue"))
        if (isUnambiguousRadar) return@mapNotNull null
        val likely = seenSlugs.contains(slugOf(name).removePrefix("varia_")) ||
            BatteryScanReceiver.matchesVariaName(name)
        DashcamCandidate(mac = d.address, name = name, likely = likely)
    }.sortedWith(
        compareByDescending<DashcamCandidate> { it.likely }.thenBy { it.name.lowercase() }
    )
}
