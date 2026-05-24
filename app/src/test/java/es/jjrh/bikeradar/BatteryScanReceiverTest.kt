// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers BatteryScanReceiver's full advert-broadcast handling: the
 * scan-error and match-lost short-circuits, and the match-and-forward
 * gate that decides whether an advertisement turns into an
 * ACTION_READ_DEVICE start of [BikeRadarService]. The bonded check is the
 * security-relevant branch - without it any peer spoofing the Garmin
 * company UUID plus a name matching the heuristic could trigger GATT
 * churn or slug injection - so both bonded and unbonded paths are pinned.
 */
@RunWith(RobolectricTestRunner::class)
class BatteryScanReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val receiver = BatteryScanReceiver()
    private val adapter =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // The 4-arg ScanResult constructor is deprecated on the framework side,
    // but it's the only one a JVM test can build without the full extended-
    // advertising parameter set; the receiver only reads device + name.
    @Suppress("DEPRECATION")
    private fun scanResultFor(mac: String, name: String, bondState: Int): ScanResult {
        val device = adapter.getRemoteDevice(mac)
        shadowOf(device).setName(name)
        shadowOf(device).setBondState(bondState)
        return ScanResult(device, null, -50, 0L)
    }

    private fun batchIntent(callbackType: Int, vararg results: ScanResult): Intent = Intent().apply {
        putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, callbackType)
        putParcelableArrayListExtra(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            ArrayList(results.toList()),
        )
    }

    @Test
    fun scanErrorBranchReturnsWithoutForwarding() {
        val intent = Intent().apply {
            putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 2)
        }
        receiver.onReceive(app, intent)
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun matchLostBranchHandlesEmptyList() {
        val intent = Intent().apply {
            putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_MATCH_LOST)
        }
        receiver.onReceive(app, intent)
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun matchLostWithResultsLogsButNeverForwards() {
        // The match-lost path walks its results to log departures; it must
        // never start a service even for a bonded, name-matching device.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_MATCH_LOST, r))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun emptyResultListReturnsWithoutForwarding() {
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun bondedMatchingDeviceForwardsAReadDeviceStart() {
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        val started = shadowOf(app).peekNextStartedService()
        assertEquals(BikeRadarService.ACTION_READ_DEVICE, started.action)
        assertEquals("RearVue8", started.getStringExtra(BikeRadarService.EXTRA_NAME))
        assertEquals("AA:BB:CC:DD:EE:FF", started.getStringExtra(BikeRadarService.EXTRA_MAC))
    }

    @Test
    fun unbondedMatchingDeviceIsSkipped() {
        // The defence-in-depth gate: a matching name from an unpaired device
        // must not start a GATT read.
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "RearVue8", BluetoothDevice.BOND_NONE)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun nonMatchingNameIsSkippedEvenWhenBonded() {
        val r = scanResultFor("AA:BB:CC:DD:EE:FF", "Pixel Buds", BluetoothDevice.BOND_BONDED)
        receiver.onReceive(app, batchIntent(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun matchesVariaNameAcceptsTheKnownHeuristicKeywords() {
        for (n in listOf("Varia RTL515", "Vue 49548", "RearVue8", "RTL510", "Garmin Edge")) {
            assertTrue("expected $n to match", BatteryScanReceiver.matchesVariaName(n))
        }
        // Case-insensitive.
        assertTrue(BatteryScanReceiver.matchesVariaName("rearvue8"))
    }

    @Test
    fun matchesVariaNameRejectsUnrelatedNames() {
        for (n in listOf("Pixel Buds", "AirPods", "", "Wahoo")) {
            assertFalse("expected $n to be rejected", BatteryScanReceiver.matchesVariaName(n))
        }
    }
}
