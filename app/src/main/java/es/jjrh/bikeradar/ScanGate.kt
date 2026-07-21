// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothDevice

/**
 * Pure accept/reject gate for an active BLE scan result, so the decision is
 * unit-testable without a scanner or a spoofed peripheral.
 *
 * A rear radar is paired through the system Bluetooth settings before this app
 * ever connects (programmatic bonding is deliberately not used - see the
 * pairing note in AGENTS.md), so a legitimate device is always BONDED by the
 * time a scan sees it. Requiring [BluetoothDevice.BOND_BONDED] here - matching
 * the passive [BatteryScanReceiver] path - stops a BLE-range peer that merely
 * advertises a matching local name from entering the connect path and being
 * persisted to the known-device cache for replay. The advertised name is
 * attacker-controlled, so a name match alone is not sufficient authority to act.
 */
object ScanGate {

    /** Act on a scan result only when its name matches a known device AND the
     *  device is bonded. */
    fun shouldActOnScanResult(nameMatches: Boolean, bondState: Int): Boolean = nameMatches && bondState == BluetoothDevice.BOND_BONDED
}
