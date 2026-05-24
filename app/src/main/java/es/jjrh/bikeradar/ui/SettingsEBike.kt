// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.EBikeStateBus
import es.jjrh.bikeradar.LdiOutcome
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.Prefs

/**
 * Settings -> eBike screen. Top-level Settings entry alongside Radar and
 * Dashcam, owning the post-onboarding adjustments for Bosch eBike Live
 * Data: master toggle, status, re-pair, unpair.
 *
 * Settings reads from Prefs (`ldiEnabled`, `ldiBondedAddress`) plus the
 * latest [LdiOutcome] published to [EBikeStateBus]. The onboarding step
 * is the right place to drive the pair flow live; Settings shows the
 * resulting status without needing the same live grain - if the service
 * isn't running the bus stays at [LdiOutcome.Idle] and the screen falls
 * back to the bond-presence inference from Prefs.
 *
 * Flow's Android package name (public, on Google Play).
 */
private const val FLOW_PACKAGE = "com.bosch.ebike.onebikeapp"

@Composable
fun SettingsEBike(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsEBikeBody(navController, prefs)
    }
}

@Composable
private fun SettingsEBikeBody(navController: NavController, prefs: Prefs) {
    val ctx = LocalContext.current
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val outcome by EBikeStateBus.outcome.collectAsState()

    SettingsEBikeContent(
        navController = navController,
        ownership = prefsSnap.eBikeOwnership,
        ldiEnabled = prefsSnap.ldiEnabled,
        bondedAddress = prefs.ldiBondedAddress,
        outcome = outcome,
        onOwnershipYes = {
            // Promotion from UNANSWERED / NO -> YES. Flipping ldiEnabled
            // arms the subsystem on the next service start; the screen's
            // pairing walkthrough handles the in-session path through
            // ACTION_START_LDI, but Settings is reached post-onboarding
            // so we don't fire the intent here.
            prefs.eBikeOwnership = EBikeOwnership.YES
            prefs.ldiEnabled = true
        },
        onToggleLdi = { enabled ->
            prefs.ldiEnabled = enabled
            val msg = if (enabled) {
                "Bosch eBike Live Data will start on next ride. Open Flow to pair."
            } else {
                "Bosch eBike Live Data will stop on next ride."
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        },
        onOpenFlow = { openFlow(ctx) },
        onUnpair = { releaseBondLocally(ctx, prefs) },
    )
}

/**
 * Stateless leaf. Snapshot tests render this without Prefs scaffolding
 * or the [EBikeStateBus] singleton.
 */
@Composable
internal fun SettingsEBikeContent(
    navController: NavController,
    ownership: EBikeOwnership,
    ldiEnabled: Boolean,
    bondedAddress: String?,
    outcome: LdiOutcome,
    onOwnershipYes: () -> Unit,
    onToggleLdi: (Boolean) -> Unit,
    onOpenFlow: () -> Unit,
    onUnpair: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(title = "eBike", onBack = { navController.popBackStack() })

            // Introductory line - sits above the toggle / promotion card.
            Text(
                text = "For Bosch Smart System eBikes on firmware v19.54 or newer.",
                color = br.fgDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (ownership != EBikeOwnership.YES) {
                // Promotion path. NO / UNANSWERED riders see the chooser
                // card instead of a toggle they can't usefully flip; one
                // tap brings them into the eBike branch.
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    IntentCard(
                        title = "I have a Bosch Smart System eBike now",
                        subtitle = "Tap to set up.",
                        filled = true,
                        onClick = onOwnershipYes,
                    )
                }
            } else {
                // Master toggle row
                SettingsRowGroup {
                    SettingsToggleRow(
                        leadingIcon = Icons.AutoMirrored.Filled.DirectionsBike,
                        leadingTint = br.brand,
                        title = "Use eBike data",
                        subtitle = if (!ldiEnabled) {
                            "Turn on to set up"
                        } else {
                            "Warns if the radar drops; quieter standstill + walk-away"
                        },
                        checked = ldiEnabled,
                        onCheckedChange = onToggleLdi,
                    )
                }
            }

            if (ownership == EBikeOwnership.YES) {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionLabel("Status")
                SettingsRowGroup {
                    // Status row uses a SettingsRow without a chevron; the
                    // line is informational, not navigational.
                    SettingsRow(
                        icon = Icons.AutoMirrored.Filled.DirectionsBike,
                        iconTint = if (statusIsHealthy(ldiEnabled, bondedAddress, outcome)) br.safe else br.fgMuted,
                        title = settingsStatusText(ldiEnabled, bondedAddress, outcome),
                        subtitle = settingsStatusSub(ldiEnabled, bondedAddress, outcome),
                        onClick = {},
                        clickable = false,
                        chevron = false,
                        isLast = true,
                    )
                }

                // ACTIONS group: visible only when the toggle is on. When
                // off, the toggle subtitle reads "Turn on to set up" and
                // actions don't apply.
                if (ldiEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsSectionLabel("Actions")
                    SettingsRowGroup {
                        if (bondedAddress == null) {
                            SettingsActionRow(
                                leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                leadingTint = br.brand,
                                title = "Open Flow to pair",
                                subtitle = "Flow: your bike -> gear icon -> Components -> Add new device -> Accessories.",
                                actionLabel = "Open",
                                onAction = onOpenFlow,
                            )
                        } else {
                            SettingsActionRow(
                                leadingIcon = Icons.Default.LinkOff,
                                leadingTint = br.fgMuted,
                                title = "Unpair this bike",
                                subtitle = "Forget this bike on the phone. Use before pairing a new accessory with your bike.",
                                actionLabel = "Unpair",
                                onAction = onUnpair,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            SettingsSectionLabel("What it does")
            Text(
                text = listOf(
                    "Beeps if the rear radar drops out mid-ride",
                    "Quieter alerts when you stop (wheel sensor, not GPS)",
                    "Keeps the walk-away alarm quiet while riding",
                ).joinToString("\n") { "•  $it" },
                color = br.fgMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/**
 * Map ldiEnabled + bondedAddress + live outcome to the single status
 * string shown at the top of the Status group. Order of precedence:
 * disabled -> outcome-specific failure -> "Paired with ..." (if a
 * bonded address was previously persisted) -> "Not paired".
 *
 * The outcome stream is only meaningful when the service is running;
 * if it's [LdiOutcome.Idle] but a bonded address exists, we still show
 * "Paired" - the bond survives across service restarts and the rider
 * doesn't want to be told it vanished on every cold start.
 */
internal fun settingsStatusText(
    ldiEnabled: Boolean,
    bondedAddress: String?,
    outcome: LdiOutcome,
): String {
    if (!ldiEnabled) return "Off"
    return when (outcome) {
        LdiOutcome.NoServiceFound -> "Firmware too old (need v19.54+)"
        LdiOutcome.SlotConflict -> "Pairing rejected (another device holds the slot)"
        LdiOutcome.PermissionsDenied -> "Bluetooth permission needed"
        LdiOutcome.AdapterUnavailable -> "Bluetooth is off"
        LdiOutcome.Connecting -> "Connecting..."
        is LdiOutcome.Paired -> "Paired with bike at ${shortenAddress(outcome.shortAddress.ifEmpty { bondedAddress.orEmpty() })}"
        LdiOutcome.Idle, LdiOutcome.Advertising, LdiOutcome.NoInbound, LdiOutcome.PairPromptDeclined ->
            if (bondedAddress != null) "Paired with bike at ${shortenAddress(bondedAddress)}" else "Not paired"
    }
}

internal fun settingsStatusSub(
    ldiEnabled: Boolean,
    bondedAddress: String?,
    outcome: LdiOutcome,
): String? {
    if (!ldiEnabled) return null
    return when (outcome) {
        is LdiOutcome.Paired -> "Reading wheel speed and lock state."
        LdiOutcome.Idle, LdiOutcome.Advertising ->
            if (bondedAddress != null) "Reading wheel speed and lock state." else null
        else -> null
    }
}

private fun statusIsHealthy(
    ldiEnabled: Boolean,
    bondedAddress: String?,
    outcome: LdiOutcome,
): Boolean {
    if (!ldiEnabled) return false
    return outcome is LdiOutcome.Paired || (bondedAddress != null && outcome !is LdiOutcome.SlotConflict)
}

/** Truncate to "AA:BB:CC..." so a BLE MAC fits in the row without
 *  pushing the chevron off-screen. Empty -> empty. */
internal fun shortenAddress(addr: String): String {
    if (addr.isBlank()) return addr
    return if (addr.length > 8) addr.substring(0, 8) + "..." else addr
}

private fun openFlow(ctx: Context) {
    val pm = ctx.packageManager
    val launch = pm.getLaunchIntentForPackage(FLOW_PACKAGE)
    val intent = launch?.apply {
        // From a non-Activity context (e.g. Settings reached from
        // navigation backstack) Android requires NEW_TASK to launch
        // another app.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$FLOW_PACKAGE"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(
            ctx,
            "Couldn't open Flow. Install it from the Play Store and try again.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

/**
 * Reflection-based `removeBond()` for the eBike. Same shape as the
 * helper in [es.jjrh.bikeradar.EBikeLink.releaseBond], duplicated here
 * because [EBikeLink] is service-owned: in Settings the service may not
 * be running, so we can't route through it. On any failure (hidden-API
 * missing, deny-list block, device unbonded) we surface a manual
 * fallback via Toast and still clear the local pointer.
 *
 * getBondState() needs BLUETOOTH_CONNECT; reached only from the eBike
 * unpair action, which the user can reach after granting BLE permissions.
 */
@SuppressLint("MissingPermission")
private fun releaseBondLocally(ctx: Context, prefs: Prefs) {
    val address = prefs.ldiBondedAddress
    if (address == null) {
        Toast.makeText(ctx, "No bond to release.", Toast.LENGTH_SHORT).show()
        return
    }
    val btManager = ctx.getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter? = btManager?.adapter
    val device = try {
        adapter?.getRemoteDevice(address)
    } catch (_: Exception) {
        null
    }
    val msg = when {
        device == null -> "Could not look up the bike's BLE device. Forget the bike in Android Settings -> Bluetooth -> Saved devices."
        device.bondState != BluetoothDevice.BOND_BONDED -> {
            prefs.ldiBondedAddress = null
            "No active bond; cleared the local pointer."
        }
        else -> try {
            device.javaClass.getMethod("removeBond").invoke(device)
            prefs.ldiBondedAddress = null
            "Bike unpaired. Restart the app to stop advertising."
        } catch (_: Exception) {
            "Could not unpair automatically. Forget the bike in Android Settings -> Bluetooth -> Saved devices."
        }
    }
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
