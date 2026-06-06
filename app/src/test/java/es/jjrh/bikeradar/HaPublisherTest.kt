// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the optional-HA contract: with HA unconfigured (the radar-only rider
 * who never set it up), every publish path is a clean no-op. Robolectric for
 * HaCredentials' SharedPreferences + the in-memory cryptor.
 *
 * The configured path publishes over real MQTT/HTTP and is not unit-testable
 * here; HaClient owns that and its own tests cover the wire format.
 */
@RunWith(RobolectricTestRunner::class)
class HaPublisherTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
    }

    @After fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun publisher(
        creds: HaCredentials = HaCredentials(app),
        rideStats: () -> RideStatsAccumulator = { RideStatsAccumulator() },
    ) = HaPublisher(
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        creds = creds,
        rideStats = rideStats,
        currentRadarMac = { "AA:BB:CC:DD:EE:FF" },
        macToSlug = { ConcurrentHashMap<String, String>().apply { put("AA:BB:CC:DD:EE:FF", "radar") } },
        loadKnownDevices = { listOf("radar" to "AA:BB:CC:DD:EE:FF") },
        slug = { it.lowercase(java.util.Locale.ROOT) },
    )

    @Test fun publishBatteryReturnsTrueWhenHaNotConfigured() {
        // The deliberate no-op-success: an unconfigured HA must report success
        // so the caller arms its 5-min throttle instead of retrying every advert.
        val ok = runBlocking { publisher().publishBatteryToHa("Radar", 80) }
        assertTrue("unconfigured battery publish must report success", ok)
    }

    @Test fun rideSummaryShortCircuitsBeforeTouchingStatsWhenHaNotConfigured() {
        // The HA-configured gate runs first, so an unconfigured rider never even
        // reads the ride-stats accumulator.
        var statsRead = false
        runBlocking {
            publisher(
                rideStats = {
                    statsRead = true
                    RideStatsAccumulator()
                },
            ).publishRideSummaryIfChanged()
        }
        assertFalse("ride-stats must not be consulted when HA is unconfigured", statsRead)
    }
}
