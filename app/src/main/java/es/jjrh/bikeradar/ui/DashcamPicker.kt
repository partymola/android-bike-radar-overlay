// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import es.jjrh.bikeradar.BatteryScanReceiver
import es.jjrh.bikeradar.BatteryStateBus
import kotlinx.coroutines.delay

/** Represents a paired BLE device shown in the picker. */
data class DashcamCandidate(
    val mac: String,
    val name: String,
    /** True if the app has seen battery adverts from this device this session,
     *  or if its name matches the existing Varia/Vue heuristic. Used for sort
     *  order so the likely dashcam floats to the top without being the only
     *  thing shown. */
    val likely: Boolean,
)

/**
 * Modal dialog that lists bonded BLE devices so the user can designate one
 * as the dashcam. Does NOT filter the list — the whole point of this screen
 * is to escape the fragile name-matching heuristic. Likely dashcams sort
 * first; everything else shows under "Other paired devices".
 */
@Composable
fun DashcamPickerDialog(
    currentMac: String?,
    onDismiss: () -> Unit,
    onConfirm: (mac: String?, name: String?) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var devices by remember { mutableStateOf(listBondedBle(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            devices = listBondedBle(ctx)
        }
    }

    var selectedMac by remember(currentMac) { mutableStateOf(currentMac) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select dashcam") },
        text = {
            if (devices.isEmpty()) {
                Text(
                    "No paired Bluetooth devices. Pair your dashcam in Android " +
                        "Settings > Connected devices first.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val (likely, other) = devices.partition { it.likely }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        PickerRow(
                            title = "None — I don't have one",
                            subtitle = null,
                            selected = selectedMac == null,
                            onSelect = { selectedMac = null },
                        )
                    }
                    if (likely.isNotEmpty()) {
                        item {
                            Text(
                                "Likely matches",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(likely, key = { it.mac }) { d ->
                            PickerRow(
                                title = d.name,
                                subtitle = d.mac,
                                selected = selectedMac.equals(d.mac, ignoreCase = true),
                                onSelect = { selectedMac = d.mac },
                            )
                        }
                    }
                    if (other.isNotEmpty()) {
                        item {
                            Text(
                                "Other paired devices",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(other, key = { it.mac }) { d ->
                            PickerRow(
                                title = d.name,
                                subtitle = d.mac,
                                selected = selectedMac.equals(d.mac, ignoreCase = true),
                                onSelect = { selectedMac = d.mac },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val name = devices.firstOrNull { it.mac.equals(selectedMac, ignoreCase = true) }?.name
                onConfirm(selectedMac, name)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun listBondedBle(ctx: Context): List<DashcamCandidate> {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bonded: Set<BluetoothDevice> = try {
        mgr?.adapter?.bondedDevices ?: emptySet()
    } catch (_: SecurityException) { emptySet() }

    val seenSlugs = BatteryStateBus.entries.value.keys
    val slugOf = { name: String -> name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') }

    return bonded.mapNotNull { d ->
        val name = try { d.name } catch (_: SecurityException) { null } ?: return@mapNotNull null
        val likely = seenSlugs.contains(slugOf(name).removePrefix("varia_")) ||
            BatteryScanReceiver.matchesVariaName(name)
        DashcamCandidate(mac = d.address, name = name, likely = likely)
    }.sortedWith(
        compareByDescending<DashcamCandidate> { it.likely }.thenBy { it.name.lowercase() }
    )
}
