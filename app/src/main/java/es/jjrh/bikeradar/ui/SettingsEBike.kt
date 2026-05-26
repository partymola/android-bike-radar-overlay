// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.EBikeStateBus
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.eBikeDataIsFresh
import kotlinx.coroutines.delay

/**
 * Settings -> eBike screen. Owns the post-onboarding controls for the
 * eBike Live Data feature: the master toggle, a receiving/waiting status,
 * and an "Open Bosch Flow" shortcut.
 *
 * The feature reads the bike's live data (battery, speed, cadence, rider
 * power, odometer) by passively subscribing to the proprietary status stream
 * the Bosch eBike Flow app uses - it works only while Flow is open and
 * connected to the bike. Only battery and a live/waiting status are surfaced
 * in the UI; the rest feeds alert tuning (stop detection, walk-away gating).
 * There is nothing to pair in this app, so this screen has no pairing
 * walkthrough or unpair action; status is just "are frames arriving right
 * now", derived from [EBikeStateBus] snapshot freshness.
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
    val lastUpdated by EBikeStateBus.lastUpdatedElapsedMs.collectAsState()
    // Re-evaluate freshness on a 5s tick (like MainScreen) so the status flips
    // to "Waiting" when Flow stops streaming - otherwise it would stay on the
    // last frame's verdict until an unrelated recompose.
    var tickNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tickNowMs = SystemClock.elapsedRealtime()
        }
    }
    val receiving = eBikeDataIsFresh(lastUpdated, tickNowMs)

    SettingsEBikeContent(
        navController = navController,
        ownership = prefsSnap.eBikeOwnership,
        eBikeDataEnabled = prefsSnap.eBikeDataEnabled,
        receiving = receiving,
        onOwnershipYes = {
            prefs.eBikeOwnership = EBikeOwnership.YES
            prefs.eBikeDataEnabled = true
        },
        onToggleEBikeData = { enabled ->
            prefs.eBikeDataEnabled = enabled
            val msg = if (enabled) {
                "Live eBike data is on. Leave Bosch Flow running in the background while you ride."
            } else {
                "Live eBike data is off."
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        },
        onOpenFlow = { openFlow(ctx) },
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
    eBikeDataEnabled: Boolean,
    receiving: Boolean,
    onOwnershipYes: () -> Unit,
    onToggleEBikeData: (Boolean) -> Unit,
    onOpenFlow: () -> Unit,
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(title = "eBike", onBack = { navController.popBackStack() })

            // Introductory line - lead with the payoff, state the dependency.
            Text(
                text = "Shows your eBike battery and connection status on the home " +
                    "screen, while Bosch Flow runs in the background on this phone.",
                color = br.fgDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                text = "Needs a Bosch Smart System eBike and the Bosch Flow app.",
                color = br.fgMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (ownership != EBikeOwnership.YES) {
                // Promotion path. NO / UNANSWERED riders see the chooser
                // card instead of a toggle they can't usefully flip.
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    IntentCard(
                        title = "I have a Bosch Smart System eBike now",
                        subtitle = "Tap to turn on live data.",
                        filled = true,
                        onClick = onOwnershipYes,
                    )
                }
            } else {
                SettingsRowGroup {
                    SettingsToggleRow(
                        leadingIcon = Icons.AutoMirrored.Filled.DirectionsBike,
                        leadingTint = br.brand,
                        title = "Use eBike data",
                        subtitle = if (!eBikeDataEnabled) {
                            "Turn on to show eBike battery and status on the home screen."
                        } else {
                            "Battery and status - shown while Bosch Flow runs in the background."
                        },
                        checked = eBikeDataEnabled,
                        onCheckedChange = onToggleEBikeData,
                    )
                }
            }

            if (ownership == EBikeOwnership.YES && eBikeDataEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionLabel("Status")
                SettingsRowGroup {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Filled.DirectionsBike,
                        iconTint = if (receiving) br.safe else br.fgMuted,
                        title = if (receiving) "Receiving live data" else "Waiting for Bosch Flow",
                        subtitle = if (receiving) {
                            "Battery and ride status from your bike."
                        } else {
                            "Open Flow and ride - data appears here automatically."
                        },
                        onClick = {},
                        clickable = false,
                        chevron = false,
                        isLast = true,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionLabel("Actions")
                SettingsRowGroup {
                    SettingsActionRow(
                        leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                        leadingTint = br.brand,
                        title = "Open Bosch Flow",
                        subtitle = "Live data needs Flow running in the background while you ride.",
                        actionLabel = "Open",
                        onAction = onOpenFlow,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            SettingsSectionLabel("What it does")
            Text(
                text = listOf(
                    "Shows eBike battery and connection status on the home screen",
                    "Keeps close-pass beeps firing on a slow climb",
                    "Scales beep timing to your wheel speed",
                ).joinToString("\n") { "•  $it" },
                color = br.fgMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))
            SettingsSectionLabel("What's collected")
            Text(
                text = listOf(
                    "Reads battery, speed and pedalling - read-only, never sends " +
                        "anything to your bike",
                    "Stays on your phone, saved to the ride log",
                    "Ride and battery info goes to Home Assistant only if you set it up",
                ).joinToString("\n") { "•  $it" },
                color = br.fgMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                text = "Full detail in the Privacy screen.",
                color = br.fgDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

private fun openFlow(ctx: Context) {
    val pm = ctx.packageManager
    val launch = pm.getLaunchIntentForPackage(FLOW_PACKAGE)
    val intent = launch?.apply {
        // From a non-Activity context (Settings reached from the nav
        // backstack) Android requires NEW_TASK to launch another app.
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
