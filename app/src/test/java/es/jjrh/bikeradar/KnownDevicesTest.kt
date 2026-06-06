// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnownDevicesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun store(name: String = "kd-test") = KnownDevices(app.getSharedPreferences(name, Context.MODE_PRIVATE))

    @Test fun loadIsEmptyWhenUnset() {
        assertTrue(store("empty").load().isEmpty())
    }

    @Test fun saveAndLoadRoundTrips() {
        val kd = store("roundtrip")
        kd.save(listOf("Radar" to "AA:BB:CC:DD:EE:FF", "Dashcam" to "11:22:33:44:55:66"))
        assertEquals(
            setOf("Radar" to "AA:BB:CC:DD:EE:FF", "Dashcam" to "11:22:33:44:55:66"),
            kd.load().toSet(),
        )
    }

    @Test fun loadSkipsMalformedEntries() {
        // A pre-existing entry without the name|mac separator must be dropped,
        // not crash or surface a half-parsed pair.
        app.getSharedPreferences("malformed", Context.MODE_PRIVATE).edit()
            .putStringSet("known_devices", setOf("Radar|AA:BB:CC:DD:EE:FF", "no-separator"))
            .apply()
        assertEquals(listOf("Radar" to "AA:BB:CC:DD:EE:FF"), store("malformed").load())
    }

    @Test fun saveOverwritesThePreviousSet() {
        val kd = store("overwrite")
        kd.save(listOf("Old" to "00:00:00:00:00:01"))
        kd.save(listOf("New" to "00:00:00:00:00:02"))
        assertEquals(listOf("New" to "00:00:00:00:00:02"), kd.load())
    }
}
