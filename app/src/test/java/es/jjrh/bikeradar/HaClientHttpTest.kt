// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
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
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Exercises [HaClient]'s live HTTP path against a loopback
 * `com.sun.net.httpserver.HttpServer` (plain JDK, no test dependency).
 * [HaClientGuardsTest] pins the "don't even open a socket" short-circuits
 * and the pure JSON builders; this pins what actually goes on the wire: the
 * request method, path, bearer header, JSON body, the 2xx/non-2xx/exception
 * branches, and the two-write fan-out for the discrete-event publishers.
 *
 * 127.0.0.1 is a loopback host, so [HaUrlPolicy] permits the cleartext
 * bearer token and the publish path runs end to end.
 */
@RunWith(RobolectricTestRunner::class)
class HaClientHttpTest {

    private data class Recorded(
        val method: String,
        val path: String,
        val auth: String?,
        val contentType: String?,
        val body: String,
    )

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private val requests = CopyOnWriteArrayList<Recorded>()

    /**
     * Maps the request's MQTT topic (null for the no-body ping GET) to the
     * status code to reply with. Defaults to a flat 200; tests override it
     * either wholesale (a flat code) or per-topic (to fail exactly one of a
     * fan-out's two writes).
     */
    @Volatile private var statusFor: (String?) -> Int = { 200 }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A small pool so the parallel async writes in publishClosePassEvent /
        // publishRideEdge are served concurrently rather than serialised.
        executor = Executors.newFixedThreadPool(4)
        server.executor = executor
        server.createContext("/") { exchange ->
            handle(exchange)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        // server.stop does not touch a user-supplied executor; shut it down so
        // its non-daemon threads don't pile up across the test run.
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        requests.add(
            Recorded(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                auth = exchange.requestHeaders.getFirst("Authorization"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = body,
            ),
        )
        val topic = if (body.isNotEmpty()) JSONObject(body).optString("topic").ifEmpty { null } else null
        val payload = "{}".toByteArray()
        exchange.sendResponseHeaders(statusFor(topic), payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }

    /** Reply with [code] to every request regardless of topic. */
    private fun respondAll(code: Int) {
        statusFor = { code }
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun client(token: String = "tok") = HaClient(baseUrl(), token)

    /** A loopback URL whose port has no listener, to drive the catch branch. */
    private fun deadPortUrl(): String {
        val sock = ServerSocket(0)
        val port = sock.localPort
        sock.close()
        return "http://127.0.0.1:$port"
    }

    @Test
    fun publishMqttSendsAuthorizedJsonPostAndReportsSuccess() = runTest {
        respondAll(200)
        val ok = client().publishMqtt("varia/x/battery", "88", retain = true)
        assertTrue(ok)
        assertEquals(1, requests.size)
        val req = requests.single()
        assertEquals("POST", req.method)
        assertEquals("/api/services/mqtt/publish", req.path)
        assertEquals("Bearer tok", req.auth)
        assertEquals("application/json", req.contentType)
        val sent = JSONObject(req.body)
        assertEquals("varia/x/battery", sent.getString("topic"))
        assertEquals("88", sent.getString("payload"))
        assertTrue(sent.getBoolean("retain"))
        assertEquals(0, sent.getInt("qos"))
    }

    @Test
    fun publishMqttReturnsFalseOnServerError() = runTest {
        respondAll(500)
        assertFalse(client().publishMqtt("t", "p", retain = false))
        assertEquals(1, requests.size)
    }

    @Test
    fun publishMqttReturnsFalseWhenConnectionRefused() = runTest {
        val client = HaClient(deadPortUrl(), "tok")
        assertFalse(client.publishMqtt("t", "p", retain = false))
        assertTrue("no request should reach a dead port", requests.isEmpty())
    }

    @Test
    fun pingReturnsSuccessWithCodeOn2xx() = runTest {
        respondAll(200)
        val r = client().ping()
        assertTrue(r.isSuccess)
        assertTrue(r.getOrThrow().contains("200"))
        val req = requests.single()
        assertEquals("GET", req.method)
        assertEquals("/api/", req.path)
        assertEquals("Bearer tok", req.auth)
    }

    @Test
    fun pingReturnsFailureOnNon2xx() = runTest {
        respondAll(503)
        val r = client().ping()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("503"))
    }

    @Test
    fun pingReturnsFailureOnConnectionRefused() = runTest {
        val r = HaClient(deadPortUrl(), "tok").ping()
        assertTrue(r.isFailure)
    }

    @Test
    fun probeMqttServiceSucceedsOn2xx() = runTest {
        respondAll(200)
        val r = client().probeMqttService()
        assertTrue(r.isSuccess)
        assertEquals("MQTT OK", r.getOrThrow())
        val req = requests.single()
        assertEquals("POST", req.method)
        assertEquals("/api/services/mqtt/publish", req.path)
        assertEquals("varia/_probe", JSONObject(req.body).getString("topic"))
    }

    @Test
    fun probeMqttServiceMaps404ToIntegrationNotEnabled() = runTest {
        respondAll(404)
        val r = client().probeMqttService()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("MQTT integration is not enabled"))
    }

    @Test
    fun probeMqttServiceMaps400ToIntegrationNotEnabled() = runTest {
        respondAll(400)
        val r = client().probeMqttService()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("MQTT integration is not enabled"))
    }

    @Test
    fun probeMqttServiceMaps401ToTokenRejected() = runTest {
        respondAll(401)
        val r = client().probeMqttService()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Token rejected"))
    }

    @Test
    fun probeMqttServiceMaps403ToTokenRejected() = runTest {
        // 401 and 403 share the "Token rejected" branch; pin 403 too so a
        // regression dropping it from that condition is caught.
        respondAll(403)
        val r = client().probeMqttService()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Token rejected"))
    }

    @Test
    fun probeMqttServiceMapsOtherCodeToGenericFailure() = runTest {
        respondAll(503)
        val r = client().probeMqttService()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("MQTT probe HTTP 503"))
    }

    @Test
    fun probeMqttServiceReturnsFailureOnConnectionRefused() = runTest {
        val r = HaClient(deadPortUrl(), "tok").probeMqttService()
        assertTrue(r.isFailure)
    }

    @Test
    fun pingPropagatesCleartextRefusalWithoutOpeningASocket() = runTest {
        // 8.8.8.8 is a non-LAN host, so HaUrlPolicy refuses the cleartext
        // bearer token. ping() must surface that refusal as the failure
        // reason (distinct from a network error) and never hit the wire.
        val r = HaClient(baseUrl = "http://8.8.8.8", token = "tok").ping()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Refused"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun probeMqttServicePropagatesCleartextRefusalWithoutOpeningASocket() = runTest {
        val r = HaClient(baseUrl = "http://8.8.8.8", token = "tok").probeMqttService()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Refused"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun publishClosePassEventDoesTwoWritesAndTagsEventType() = runTest {
        respondAll(200)
        val event = JSONObject().put("side", "left").put("clearance_m", 0.9)
        val ok = client().publishClosePassEvent("rearvue8", event)
        assertTrue(ok)
        assertEquals(2, requests.size)
        // Both writes carry the same payload with event_type added; one to the
        // base topic, one to /last. Inspect the bodies, not request order
        // (the two async writes race).
        val topics = requests.map { JSONObject(it.body).getString("topic") }.toSet()
        assertEquals(
            setOf("varia/rearvue8/close_pass", "varia/rearvue8/close_pass/last"),
            topics,
        )
        for (req in requests) {
            val sent = JSONObject(JSONObject(req.body).getString("payload"))
            assertEquals("close_pass", sent.getString("event_type"))
            assertEquals("left", sent.getString("side"))
        }
    }

    @Test
    fun publishClosePassEventReturnsFalseWhenOnlyTheRetainedWriteFails() = runTest {
        // Fail exactly the /last write, leaving the base-topic write at 200.
        // This pins the `&&` in `j1.await() && j2.await()`: a regression to
        // `||` would let one success mask the failure and report true.
        statusFor = { topic -> if (topic != null && topic.endsWith("/last")) 500 else 200 }
        val ok = client().publishClosePassEvent("rearvue8", JSONObject().put("k", "v"))
        assertFalse(ok)
        assertEquals(2, requests.size)
    }

    @Test
    fun publishRideEdgeDoesTwoWritesWithEdgeTypeAndTimestamp() = runTest {
        respondAll(200)
        val ok = client().publishRideEdge("started", EPOCH_ISO)
        assertTrue(ok)
        assertEquals(2, requests.size)
        val topics = requests.map { JSONObject(it.body).getString("topic") }.toSet()
        assertEquals(setOf("varia/ride/edge", "varia/ride/edge/last"), topics)
        for (req in requests) {
            val sent = JSONObject(JSONObject(req.body).getString("payload"))
            assertEquals("ride_started", sent.getString("event_type"))
            assertEquals(EPOCH_ISO, sent.getString("timestamp"))
        }
    }

    @Test
    fun publishRideEdgeReturnsFalseWhenOnlyTheRetainedWriteFails() = runTest {
        statusFor = { topic -> if (topic != null && topic.endsWith("/last")) 500 else 200 }
        assertFalse(client().publishRideEdge("ended", EPOCH_ISO))
        assertEquals(2, requests.size)
    }

    @Test
    fun publishBatteryDiscoveryPostsRetainedConfig() = runTest {
        respondAll(200)
        assertTrue(client().publishBatteryDiscovery("rearvue8", "RearVue8"))
        val req = requests.single()
        val sent = JSONObject(req.body)
        assertEquals(
            "homeassistant/sensor/varia_rearvue8_battery/config",
            sent.getString("topic"),
        )
        assertTrue(sent.getBoolean("retain"))
    }

    @Test
    fun publishBatteryStatePostsPercentToStateTopic() = runTest {
        respondAll(200)
        assertTrue(client().publishBatteryState("rearvue8", 73))
        val sent = JSONObject(requests.single().body)
        assertEquals("varia/rearvue8/battery", sent.getString("topic"))
        assertEquals("73", sent.getString("payload"))
    }

    @Test
    fun publishFrontModeDiscoveryAndStatePost() = runTest {
        respondAll(200)
        assertTrue(client().publishFrontModeDiscovery("vue49548", "Vue 49548"))
        assertTrue(client().publishFrontModeState("vue49548", "Day Flash"))
        assertEquals(2, requests.size)
        assertEquals(
            "homeassistant/sensor/varia_vue49548_front_mode/config",
            JSONObject(requests[0].body).getString("topic"),
        )
        val state = JSONObject(requests[1].body)
        assertEquals("varia/vue49548/front_mode", state.getString("topic"))
        assertEquals("Day Flash", state.getString("payload"))
    }

    @Test
    fun cleanupStaleDiscoveryTopicsClearsAllWhenServerAccepts() = runTest {
        respondAll(200)
        assertTrue(client().cleanupStaleDiscoveryTopics(listOf("rearvue8")))
        // Three legacy shapes per slug + two fixed probe topics, all retained
        // empty payloads (a delete in MQTT discovery terms).
        assertEquals(5, requests.size)
        assertTrue(requests.all { JSONObject(it.body).getString("payload").isEmpty() })
        assertTrue(requests.all { JSONObject(it.body).getBoolean("retain") })
    }

    @Test
    fun cleanupStaleDiscoveryTopicsReturnsFalseWhenAnyWriteFails() = runTest {
        respondAll(500)
        assertFalse(client().cleanupStaleDiscoveryTopics(listOf("rearvue8")))
    }

    @Test
    fun publishRideSummaryDiscoveryPostsTwelveRetainedSensors() = runTest {
        respondAll(200)
        assertTrue(client().publishRideSummaryDiscovery("rearvue8", "RearVue8"))
        assertEquals(12, requests.size)
        assertTrue(requests.all { JSONObject(it.body).getBoolean("retain") })
    }

    @Test
    fun publishRideSummaryStateSerialisesFullSnapshot() = runTest {
        respondAll(200)
        val snapshot = RideStatsSnapshot(
            overtakesTotal = 12,
            closePassCount = 3,
            grazingCount = 1,
            hgvClosePassCount = 1,
            peakClosingKmh = 47,
            closingSpeedP90Kmh = 39,
            minLateralClearanceM = 0.85f,
            distanceRiddenKm = 9.4f,
            exposureSeconds = 320,
            closePassConversionRatePct = 25.0f,
            tightestPass = TightestPass(
                tsMs = 1_716_534_000_000L,
                side = ClosePassDetector.Side.LEFT,
                vehicleSize = VehicleSize.TRUCK,
                clearanceM = 0.85f,
                closingKmh = 47,
                rangeYAtMinM = 2.1f,
            ),
            rideStartedAtMs = 1_716_533_000_000L,
            alertsPerKm = 0.32f,
            alertsPerHourOfRide = 11.3f,
        )
        assertTrue(client().publishRideSummaryState("rearvue8", snapshot))
        val sent = JSONObject(JSONObject(requests.single().body).getString("payload"))
        assertEquals(12, sent.getInt("overtakes_total"))
        assertEquals(47, sent.getInt("peak_closing_kmh"))
        assertEquals(0.32, sent.getDouble("alerts_per_km"), 1e-4)
        assertEquals(11.3, sent.getDouble("alerts_per_hour_of_ride"), 1e-4)
        val tightest = sent.getJSONObject("tightest_pass")
        assertEquals("left", tightest.getString("side"))
        assertEquals("TRUCK", tightest.getString("vehicle_size"))
    }

    @Test
    fun publishRideSummaryStateOmitsNullableFieldsWhenAbsent() = runTest {
        respondAll(200)
        val snapshot = RideStatsSnapshot(
            overtakesTotal = 0,
            closePassCount = 0,
            grazingCount = 0,
            hgvClosePassCount = 0,
            peakClosingKmh = null,
            closingSpeedP90Kmh = null,
            minLateralClearanceM = null,
            distanceRiddenKm = 0f,
            exposureSeconds = 0,
            closePassConversionRatePct = 0f,
            tightestPass = null,
            rideStartedAtMs = 1_716_533_000_000L,
            alertsPerKm = null,
            alertsPerHourOfRide = null,
        )
        assertTrue(client().publishRideSummaryState("rearvue8", snapshot))
        val sent = JSONObject(JSONObject(requests.single().body).getString("payload"))
        assertFalse(sent.has("peak_closing_kmh"))
        assertFalse(sent.has("closing_speed_p90_kmh"))
        assertFalse(sent.has("min_lateral_clearance_m"))
        assertFalse(sent.has("tightest_pass"))
        assertFalse(sent.has("alerts_per_km"))
        assertFalse(sent.has("alerts_per_hour_of_ride"))
    }

    private companion object {
        // Opaque ISO timestamp fixture; publishRideEdge passes it through
        // verbatim, so the exact instant is irrelevant to the round-trip.
        const val EPOCH_ISO = "1970-01-01T00:00:00Z"
    }
}
