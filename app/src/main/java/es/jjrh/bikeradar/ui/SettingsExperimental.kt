// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.data.Prefs

@Composable
fun SettingsExperimental(navController: NavController, prefs: Prefs) {
    UiTheme {
        SettingsExperimentalBody(navController, prefs)
    }
}

@Composable
private fun SettingsExperimentalBody(navController: NavController, prefs: Prefs) {
    val prefsSnap by prefs.flow.collectAsState(initial = prefs.snapshot())
    val context = LocalContext.current
    SettingsExperimentalContent(
        navController = navController,
        precogEnabled = prefsSnap.precogEnabled,
        onPrecogChange = { prefs.precogEnabled = it },
        lateralPanningEnabled = prefsSnap.experimentalLateralPanning,
        onLateralPanningChange = { prefs.experimentalLateralPanning = it },
        lateralPanningInvertLR = prefsSnap.experimentalLateralPanningInvertLR,
        onLateralPanningInvertLRChange = { prefs.experimentalLateralPanningInvertLR = it },
        ldiEnabled = prefsSnap.ldiEnabled,
        onLdiEnabledChange = { enabled ->
            prefs.ldiEnabled = enabled
            // Toast tells the rider the change takes effect on the next
            // service start. We don't restart the service here; the
            // experimental flag is meant to be flipped pre-ride.
            val msg = if (enabled)
                "Bosch eBike LDI will start on next ride. Open Flow to pair."
            else
                "Bosch eBike LDI will stop on next ride."
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        },
        hasLdiBond = prefsSnap.ldiEnabled && !prefs.ldiBondedAddress.isNullOrBlank(),
        onReleaseLdiBond = {
            val address = prefs.ldiBondedAddress
            if (address == null) {
                Toast.makeText(context, "No LDI bond to release.", Toast.LENGTH_SHORT).show()
                return@SettingsExperimentalContent
            }
            val btManager = context.getSystemService(BluetoothManager::class.java)
            val adapter: BluetoothAdapter? = btManager?.adapter
            val device = try { adapter?.getRemoteDevice(address) } catch (_: Exception) { null }
            // Reflection-based removeBond is hidden-API; wrapped here so the
            // rider gets a fallback message if it fails. The
            // bond may already have been cleared via Android Settings;
            // surface a different message rather than claiming a release
            // that never happened.
            val msg = when {
                device == null -> "Could not look up the bike's BLE device. Forget the bike in Android Settings -> Bluetooth -> Saved devices."
                device.bondState != BluetoothDevice.BOND_BONDED -> {
                    prefs.ldiBondedAddress = null
                    "No active bond; cleared the local pointer."
                }
                else -> try {
                    device.javaClass.getMethod("removeBond").invoke(device)
                    prefs.ldiBondedAddress = null
                    "LDI bond released. Restart the app to stop advertising."
                } catch (_: Exception) {
                    "Could not release automatically. Forget the bike in Android Settings -> Bluetooth -> Saved devices."
                }
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        },
    )
}

/**
 * Stateless leaf — visible to snapshot tests so the visual contract can
 * be locked without Prefs scaffolding.
 */
@Composable
internal fun SettingsExperimentalContent(
    navController: NavController,
    precogEnabled: Boolean,
    onPrecogChange: (Boolean) -> Unit,
    lateralPanningEnabled: Boolean,
    onLateralPanningChange: (Boolean) -> Unit,
    lateralPanningInvertLR: Boolean,
    onLateralPanningInvertLRChange: (Boolean) -> Unit,
    ldiEnabled: Boolean = false,
    onLdiEnabledChange: (Boolean) -> Unit = {},
    hasLdiBond: Boolean = false,
    onReleaseLdiBond: () -> Unit = {},
) {
    val br = LocalBrColors.current
    Box(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader("Experimental", onBack = { navController.popBackStack() })

            Text(
                text = "Features still being tested. May be jittery or change without notice.",
                color = br.fgDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))
            SettingsRowGroup {
                SettingsToggleRow(
                    leadingIcon = Icons.Default.FlashOn,
                    leadingTint = br.brand,
                    title = "Predict overtake paths (1 s lookahead)",
                    subtitle = "Render each vehicle 1 s into the future - see where overtakers are heading, not just where they are. Can look jittery in noisy traffic.",
                    checked = precogEnabled,
                    onCheckedChange = onPrecogChange,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsRowGroup {
                SettingsToggleRow(
                    leadingIcon = Icons.Default.Headphones,
                    leadingTint = br.brand,
                    title = "Directional alert audio",
                    subtitle = "Pan beeps and urgent cues to the threat's side via stereo. Works on phone speakers in landscape (rotates with the mount) and on stereo headphones (BT, BLE, wired, USB, hearing aid). Portrait plays mono.",
                    checked = lateralPanningEnabled,
                    onCheckedChange = onLateralPanningChange,
                )
                if (lateralPanningEnabled) {
                    SettingsToggleRow(
                        leadingIcon = Icons.Default.SwapHoriz,
                        leadingTint = br.fgDim,
                        title = "Invert left/right",
                        subtitle = "Flip channels if directional cues land in the wrong ear (rare device-class quirk or earbuds worn on the wrong side).",
                        checked = lateralPanningInvertLR,
                        onCheckedChange = onLateralPanningInvertLRChange,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsRowGroup {
                SettingsToggleRow(
                    leadingIcon = Icons.AutoMirrored.Filled.DirectionsBike,
                    leadingTint = br.brand,
                    title = "Bosch eBike data (LDI)",
                    subtitle = "For Bosch Smart System eBikes on firmware v19.54 or newer. Uses the bike's own wheel sensor to suppress alerts at traffic lights more reliably, and reads the bike's lock state so radar BLE blips mid-ride don't trigger the walk-away alarm. After enabling, pair from Flow: My eBike -> System -> Connect device -> Live Data Interface, then confirm on the bike's controller.",
                    checked = ldiEnabled,
                    onCheckedChange = onLdiEnabledChange,
                )
                if (hasLdiBond) {
                    SettingsToggleRow(
                        leadingIcon = Icons.Default.LinkOff,
                        leadingTint = br.fgDim,
                        title = "Release LDI bond",
                        subtitle = "Forget the paired eBike on this phone. Use this before pairing a Garmin Edge or other LDI accessory with your bike (the bike has only one LDI slot).",
                        checked = false,
                        onCheckedChange = { if (it) onReleaseLdiBond() },
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
