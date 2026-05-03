// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanSettings
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Smoke tests for BatteryScanReceiver. Only the MATCH_LOST-without-results
 * branch has a real crash risk (NPE on a missing parcelable), so it gets an
 * explicit test. The full match-and-forward path needs a real ScanResult
 * which is not constructible from JVM tests; that branch is covered by
 * live testing.
 */
@RunWith(RobolectricTestRunner::class)
class BatteryScanReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val receiver = BatteryScanReceiver()

    @Test
    fun matchLostBranchHandlesEmptyList() {
        // CALLBACK_TYPE_MATCH_LOST with no results list should log and return,
        // not throw on a missing parcelable extra.
        val intent = Intent().apply {
            putExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_MATCH_LOST)
        }
        receiver.onReceive(app, intent)
        assertNull(shadowOf(app).peekNextStartedService())
    }
}
