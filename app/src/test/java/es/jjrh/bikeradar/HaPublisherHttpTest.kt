// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Configured-path coverage for [HaPublisher]: battery discovery+state and its
 * throttle/rollback, ride-edge, and the full ride-summary branch matrix
 * (no-mac, unknown-slug, uppercase-MAC fallback, discovery/state failure).
 * Drives a real loopback JDK [HttpServer] (the HaClientHttpTest pattern) rather
 * than a mock - HaClient talks straight to HttpURLConnection with no interface
 * seam, so a local server is the cleanest way to exercise the orchestration
 * without faking the wire. The HA-unconfigured no-op contract lives in
 * HaPublisherTest.
 */
@RunWith(RobolectricTestRunner::class)
class HaPublisherHttpTest {

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private val topics = CopyOnWriteArrayList<String>()

    /** Status to reply with, keyed by the request's MQTT topic. */
    @Volatile private var statusFor: (String) -> Int = { 200 }

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(4)
        server.executor = executor
        server.createContext("/") { ex: HttpExchange ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val topic = if (body.isNotEmpty()) JSONObject(body).optString("topic") else ""
            if (topic.isNotEmpty()) topics.add(topic)
            val out = "{}".toByteArray()
            ex.sendResponseHeaders(statusFor(topic), out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun creds() = HaCredentials(app).apply {
        save("http://127.0.0.1:${server.address.port}", "tok")
    }

    private fun publisher(
        rideStats: () -> RideStatsAccumulator = { RideStatsAccumulator() },
        currentRadarMac: () -> String? = { null },
        macToSlug: () -> ConcurrentHashMap<String, String> = { ConcurrentHashMap() },
        loadKnownDevices: () -> List<Pair<String, String>> = { emptyList() },
    ) = HaPublisher(
        scope = CoroutineScope(Dispatchers.Unconfined),
        creds = creds(),
        rideStats = rideStats,
        currentRadarMac = currentRadarMac,
        macToSlug = macToSlug,
        loadKnownDevices = loadKnownDevices,
        slug = { it.lowercase(Locale.ROOT) },
    )

    private fun macMap(mac: String, slug: String) = ConcurrentHashMap<String, String>().apply { put(mac, slug) }

    /** Spin-wait for a published topic (ride-edge is fire-and-forget). */
    private fun awaitTopic(timeoutMs: Long = 2_000, predicate: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (topics.any(predicate)) return
            Thread.sleep(20)
        }
        throw AssertionError("expected topic not published within ${timeoutMs}ms; saw $topics")
    }

    // ── battery ──────────────────────────────────────────────────────────────

    @Test fun batteryPublishPostsDiscoveryThenStateAndReportsSuccess() = runBlocking {
        assertTrue(publisher().publishBatteryToHa("Radar", 80))
        assertEquals(
            listOf("homeassistant/sensor/varia_radar_battery/config", "varia/radar/battery"),
            topics.toList(),
        )
    }

    @Test fun batteryPublishReturnsFalseWhenDiscoveryFails() = runBlocking {
        statusFor = { 500 }
        assertFalse(publisher().publishBatteryToHa("Radar", 80))
    }

    @Test fun batteryStateFailureAfterSuccessfulDiscoveryReportsFalse() = runBlocking {
        // Discovery (config) succeeds, the state write fails - exercises the
        // state-publish failure branch, distinct from a discovery failure.
        statusFor = { if (it.endsWith("/config")) 200 else 500 }
        assertFalse(publisher().publishBatteryToHa("Radar", 80))
        assertTrue("discovery still went out", topics.any { it.endsWith("/config") })
        assertTrue("state write was attempted", topics.contains("varia/radar/battery"))
    }

    @Test fun discoveryFailureRollsBackSoTheNextCallRetriesDiscovery() = runBlocking {
        val p = publisher()
        statusFor = { 500 }
        assertFalse(p.publishBatteryToHa("Radar", 80))
        statusFor = { 200 }
        assertTrue(p.publishBatteryToHa("Radar", 80))
        assertEquals("discovery retried after rollback", 2, topics.count { it.endsWith("/config") })
    }

    @Test fun batteryThrottleSkipsRepeatWithinHeartbeatAndRepublishesOnChange() = runBlocking {
        val p = publisher()
        p.maybePublishBatteryToHa("Radar", 80)
        val afterFirst = topics.size
        assertTrue(afterFirst > 0)
        p.maybePublishBatteryToHa("Radar", 80)
        assertEquals("same pct within heartbeat is throttled", afterFirst, topics.size)
        p.maybePublishBatteryToHa("Radar", 81)
        assertTrue("a pct change re-publishes", topics.size > afterFirst)
    }

    // ── ride edge ────────────────────────────────────────────────────────────

    @Test fun rideEdgePublishesToTheRideEdgeTopic() {
        // The timestamp is opaque to the publisher (forwarded into the payload);
        // the test only pins that the edge topic gets published.
        publisher().publishRideEdgeIfHa("started", "stamp")
        awaitTopic { it == "varia/ride/edge" }
    }

    // ── ride summary ─────────────────────────────────────────────────────────

    private fun changedStats() = { RideStatsAccumulator().apply { observeAlertCue("beep") } }

    @Test fun rideSummaryPublishesWhenChangedThenIsIdempotent() = runBlocking {
        val stats = RideStatsAccumulator().apply { observeAlertCue("beep") }
        val p = publisher(
            rideStats = { stats },
            currentRadarMac = { "AA:BB:CC:DD:EE:FF" },
            macToSlug = { macMap("AA:BB:CC:DD:EE:FF", "radar") },
            loadKnownDevices = { listOf("Radar" to "AA:BB:CC:DD:EE:FF") },
        )
        p.publishRideSummaryIfChanged()
        val afterFirst = topics.size
        assertTrue("a changed ride summary publishes", afterFirst > 0)
        p.publishRideSummaryIfChanged()
        assertEquals("an unchanged summary does not re-publish", afterFirst, topics.size)
    }

    @Test fun rideSummaryIsNoOpWithoutARadarMac() = runBlocking {
        publisher(rideStats = changedStats(), currentRadarMac = { null }).publishRideSummaryIfChanged()
        assertTrue("nothing published without a radar mac", topics.isEmpty())
    }

    @Test fun rideSummaryIsNoOpWhenSlugUnknown() = runBlocking {
        publisher(
            rideStats = changedStats(),
            currentRadarMac = { "AA:BB:CC:DD:EE:FF" },
            macToSlug = { ConcurrentHashMap() }, // no mapping
        ).publishRideSummaryIfChanged()
        assertTrue("nothing published when the slug is unknown", topics.isEmpty())
    }

    @Test fun rideSummaryResolvesSlugViaUppercaseFallbackAndDefaultsDeviceName() = runBlocking {
        // Lower-case live mac, map keyed upper-case -> uppercase fallback resolves;
        // empty known-devices -> deviceName defaults to "radar".
        publisher(
            rideStats = changedStats(),
            currentRadarMac = { "aa:bb:cc:dd:ee:ff" },
            macToSlug = { macMap("AA:BB:CC:DD:EE:FF", "radar") },
            loadKnownDevices = { emptyList() },
        ).publishRideSummaryIfChanged()
        assertTrue(
            "resolved to the radar slug via the uppercase fallback",
            topics.any { it.startsWith("homeassistant/sensor/varia_radar_") },
        )
    }

    @Test fun rideSummaryDiscoveryFailureLeavesItUnpublished() = runBlocking {
        val stats = RideStatsAccumulator().apply { observeAlertCue("beep") }
        statusFor = { if (it.endsWith("/config")) 500 else 200 }
        publisher(
            rideStats = { stats },
            currentRadarMac = { "AA:BB:CC:DD:EE:FF" },
            macToSlug = { macMap("AA:BB:CC:DD:EE:FF", "radar") },
        ).publishRideSummaryIfChanged()
        assertTrue("discovery failure leaves the summary unpublished (still dirty)", stats.changedSinceLast())
    }

    @Test fun rideSummaryStateFailureDoesNotMarkPublished() = runBlocking {
        val stats = RideStatsAccumulator().apply { observeAlertCue("beep") }
        statusFor = { if (it.endsWith("/config")) 200 else 500 } // discovery ok, state fails
        publisher(
            rideStats = { stats },
            currentRadarMac = { "AA:BB:CC:DD:EE:FF" },
            macToSlug = { macMap("AA:BB:CC:DD:EE:FF", "radar") },
        ).publishRideSummaryIfChanged()
        assertTrue("a failed state publish must not mark published", stats.changedSinceLast())
    }
}
