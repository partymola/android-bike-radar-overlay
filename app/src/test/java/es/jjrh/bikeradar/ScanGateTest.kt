// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the active-scan bond gate: a scan result is acted on only when its name
 * matches AND the device is bonded. The spoof case (matching name, not paired)
 * must be rejected. The BOND_* values are compile-time constants, so no
 * Robolectric / Android runtime is needed.
 */
class ScanGateTest {

    @Test fun matchingAndBondedIsAccepted() {
        assertTrue(ScanGate.shouldActOnScanResult(nameMatches = true, bondState = BluetoothDevice.BOND_BONDED))
    }

    @Test fun matchingButUnbondedIsRejected() {
        // The spoof case: a BLE-range peer advertises a matching name
        // but was never paired.
        assertFalse(ScanGate.shouldActOnScanResult(nameMatches = true, bondState = BluetoothDevice.BOND_NONE))
        assertFalse(ScanGate.shouldActOnScanResult(nameMatches = true, bondState = BluetoothDevice.BOND_BONDING))
    }

    @Test fun nonMatchingIsRejectedEvenIfBonded() {
        assertFalse(ScanGate.shouldActOnScanResult(nameMatches = false, bondState = BluetoothDevice.BOND_BONDED))
    }

    @Test fun nonMatchingUnbondedIsRejected() {
        assertFalse(ScanGate.shouldActOnScanResult(nameMatches = false, bondState = BluetoothDevice.BOND_NONE))
    }
}
