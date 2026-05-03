// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Publishes to Home Assistant via the MQTT discovery model.
 *
 * Uses `/api/services/mqtt/publish` rather than `/api/states/<entity>` so
 * entities are properly registry-backed: their state survives HA restarts,
 * and Spook no longer flags automations referencing them as
 * "non-existing entity".
 *
 * HA prerequisite: the MQTT integration must be enabled. The app itself
 * never talks to the MQTT broker - HA does the publish on our behalf.
 *
 * Note: topic prefixes and slugs keep the legacy "varia_" prefix so existing
 * HA entity_ids (`sensor.varia_vue_49548_battery`, `sensor.varia_rearvue8_battery`)
 * and user automations keep working after the rebrand. Renaming them is a
 * conscious opt-in migration, not an implicit upgrade side-effect.
 */
class HaClient(private val baseUrl: String, private val token: String) {
    fun isConfigured(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()

    /**
     * Returns a Throwable describing why the configured URL is unsafe to send
     * the bearer token over, or null if the URL is acceptable. HTTPS is always
     * acceptable; HTTP is acceptable only for LAN hosts. See [HaUrlPolicy].
     */
    private fun cleartextRefusal(): Throwable? = when (val r = HaUrlPolicy.validate(baseUrl)) {
        is HaUrlPolicy.Result.CleartextWanRefused ->
            SecurityException(HaUrlPolicy.refusalMessage(r.host))
        HaUrlPolicy.Result.Malformed -> IllegalArgumentException("HA URL malformed")
        HaUrlPolicy.Result.Empty, HaUrlPolicy.Result.Ok -> null
    }

    suspend fun publishMqtt(topic: String, payload: String, retain: Boolean): Boolean {
        if (!isConfigured()) return false
        cleartextRefusal()?.let {
            Log.w(TAG, "HA mqtt/publish $topic refused: ${it.message}")
            return false
        }
        return withContext(Dispatchers.IO) {
            val url = URL("${baseUrl.trimEnd('/')}/api/services/mqtt/publish")
            val body = JSONObject()
                .put("topic", topic)
                .put("payload", payload)
                .put("retain", retain)
                .put("qos", 0)
                .toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = HA_TIMEOUT_MS
                readTimeout = HA_TIMEOUT_MS
                doOutput = true
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                drainAndReturn(conn, code)
                if (code !in 200..299) {
                    Log.w(TAG, "HA mqtt/publish $topic -> $code")
                }
                code in 200..299
            } catch (t: Throwable) {
                Log.w(TAG, "HA mqtt/publish $topic failed: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
    }

    suspend fun ping(): Result<String> {
        if (!isConfigured()) return Result.failure(Exception("Not configured"))
        cleartextRefusal()?.let { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("${baseUrl.trimEnd('/')}/api/")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = HA_TIMEOUT_MS
                    readTimeout = HA_TIMEOUT_MS
                }
                val code = conn.responseCode
                drainAndReturn(conn, code)
                if (code in 200..299) Result.success("OK ($code)")
                else Result.failure(Exception("HTTP $code"))
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Probes whether HA's MQTT integration is loaded by calling
     * `mqtt.publish` with an empty retained message to a probe topic.
     * HA returns 2xx when the service exists (even if the broker is
     * temporarily offline, HA accepts the call and queues). A 400 or
     * 404 typically means the MQTT integration hasn't been set up.
     * ping() verifies HA reachability; this verifies the downstream
     * feature path the app actually depends on.
     */
    suspend fun probeMqttService(): Result<String> {
        if (!isConfigured()) return Result.failure(Exception("Not configured"))
        cleartextRefusal()?.let { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("${baseUrl.trimEnd('/')}/api/services/mqtt/publish")
                val body = JSONObject()
                    .put("topic", "varia/_probe")
                    .put("payload", "")
                    .put("retain", true)
                    .put("qos", 0)
                    .toString()
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = HA_TIMEOUT_MS
                    readTimeout = HA_TIMEOUT_MS
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                drainAndReturn(conn, code)
                when {
                    code in 200..299 -> Result.success("MQTT OK")
                    code == 400 || code == 404 -> Result.failure(
                        Exception("HA's MQTT integration is not enabled"),
                    )
                    code == 401 || code == 403 -> Result.failure(
                        Exception("Token rejected for mqtt.publish"),
                    )
                    else -> Result.failure(Exception("MQTT probe HTTP $code"))
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Read the response stream to completion so the underlying TCP socket
     * can be returned to the JDK keep-alive pool. Without this (or with
     * `disconnect()`) every publish opens a fresh TCP+TLS connection and
     * fully wakes the radio - which dominates background battery cost when
     * the heartbeat fires every 5 minutes for the whole ride.
     */
    private fun drainAndReturn(conn: HttpURLConnection, code: Int) {
        try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { while (it.read() != -1) Unit }
        } catch (_: Throwable) {
            // Body draining is best-effort; if it fails the keep-alive
            // pool just won't reuse this socket. No correctness impact.
        }
    }

    suspend fun publishBatteryDiscovery(slug: String, deviceName: String): Boolean {
        val topic = "$DISCOVERY_PREFIX/sensor/varia_${slug}_battery/config"
        val clean = cleanDeviceName(deviceName)
        val payload = JSONObject()
            .put("object_id", "varia_${slug}_battery")
            .put("unique_id", "varia_${slug}_battery")
            .put("name", "battery")
            .put("has_entity_name", true)
            .put("state_topic", "varia/$slug/battery")
            .put("device_class", "battery")
            .put("unit_of_measurement", "%")
            .put("state_class", "measurement")
            .put(
                "device",
                JSONObject()
                    .put("identifiers", JSONArray().put("varia_$slug"))
                    .put("name", "Varia $clean")
                    .put("manufacturer", "Garmin")
                    .put("model", "Varia")
                    .put("via_device", "varia_reader"),
            )
            .toString()
        return publishMqtt(topic, payload, retain = true)
    }

    suspend fun cleanupStaleDiscoveryTopics(slugs: List<String>): Boolean {
        if (!isConfigured()) return false
        val topics = buildList {
            for (s in slugs) {
                add("$DISCOVERY_PREFIX/sensor/${s}/${s}_battery/config")
                add("$DISCOVERY_PREFIX/sensor/${s}_battery/config")
                add("$DISCOVERY_PREFIX/sensor/varia_${s}_battery/config")
            }
            add("$DISCOVERY_PREFIX/sensor/varia_probe_83390a_battery/config")
            add("$DISCOVERY_PREFIX/sensor/varia_probe_83390b_battery/config")
        }
        var allOk = true
        for (t in topics) {
            val ok = publishMqtt(t, payload = "", retain = true)
            if (!ok) allOk = false
        }
        return allOk
    }

    private fun cleanDeviceName(raw: String): String =
        raw.replace(Regex("[^\\x20-\\x7E]"), "").trim().ifEmpty { "device" }

    suspend fun publishBatteryState(slug: String, pct: Int): Boolean =
        publishMqtt("varia/$slug/battery", pct.toString(), retain = true)

    /**
     * Publishes the HA MQTT-Discovery config for the close-pass event
     * entity. Uses HA's native `event` platform (discrete, time-stamped
     * events with payload attributes, and a built-in history view) rather
     * than a sensor with json_attributes_topic — close passes are
     * discrete occurrences, not a continuous state.
     *
     * Idempotent: the discovery topic is retained, so republishing on
     * every service start is a no-op on the HA side. Call once per
     * session if the feature is enabled.
     */
    suspend fun publishClosePassDiscovery(slug: String, deviceName: String): Boolean {
        val topic = "$DISCOVERY_PREFIX/event/varia_${slug}_close_pass/config"
        val clean = cleanDeviceName(deviceName)
        val eventTopic = "varia/$slug/close_pass"
        val payload = JSONObject()
            .put("object_id", "varia_${slug}_close_pass")
            .put("unique_id", "varia_${slug}_close_pass")
            .put("name", "Close pass")
            .put("has_entity_name", true)
            .put("state_topic", eventTopic)
            .put("event_types", JSONArray().put("close_pass"))
            .put("value_template", "{{ value_json.event_type }}")
            .put("json_attributes_topic", eventTopic)
            .put(
                "device",
                JSONObject()
                    .put("identifiers", JSONArray().put("varia_$slug"))
                    .put("name", "Varia $clean")
                    .put("manufacturer", "Garmin")
                    .put("model", "Varia")
                    .put("via_device", "varia_reader"),
            )
            .toString()
        return publishMqtt(topic, payload, retain = true)
    }

    /**
     * Publishes a single close-pass event. Two writes: one non-retained
     * event to the main topic (consumed by HA's event entity + HA
     * Recorder / InfluxDB if the MQTT bridge is wired), and one retained
     * write to `/last` so a fresh dashboard card has something to
     * display on load without waiting for the next overtake.
     */
    suspend fun publishClosePassEvent(slug: String, eventJson: JSONObject): Boolean {
        val topic = "varia/$slug/close_pass"
        val payload = JSONObject(eventJson.toString()).put("event_type", "close_pass").toString()
        return coroutineScope {
            val j1 = async { publishMqtt(topic, payload, retain = false) }
            val j2 = async { publishMqtt("$topic/last", payload, retain = true) }
            j1.await() && j2.await()
        }
    }

    /**
     * Publishes the HA MQTT-Discovery config for the per-ride summary
     * sensors. Ten sensors share the single retained state topic
     * `varia/<slug>/ride_summary` and decompose its JSON via value_template,
     * so one MQTT message updates every sensor atomically. All sensors
     * sit under the same `device.identifiers` as the battery + close-pass
     * entities so they group on a single HA device card.
     *
     * `expire_after: 600` flips each sensor to `unavailable` ten minutes
     * after the last publish, so stale ride numbers do not sit on the
     * dashboard once the rider has put the bike away.
     */
    suspend fun publishRideSummaryDiscovery(slug: String, deviceName: String): Boolean {
        val clean = cleanDeviceName(deviceName)
        val stateTopic = "varia/$slug/ride_summary"
        val device = JSONObject()
            .put("identifiers", JSONArray().put("varia_$slug"))
            .put("name", "Varia $clean")
            .put("manufacturer", "Garmin")
            .put("model", "Varia")
            .put("via_device", "varia_reader")
        val sensors = listOf(
            RideSummarySensor("overtakes_total",          "Overtakes",                     "total_increasing", null,       null,    null),
            RideSummarySensor("close_pass_count",         "Close passes",                  "total_increasing", null,       null,    null),
            RideSummarySensor("grazing_count",            "Grazing passes",                "total_increasing", null,       null,    null),
            RideSummarySensor("hgv_close_pass_count",     "HGV close passes",              "total_increasing", null,       null,    null),
            RideSummarySensor("peak_closing_kmh",         "Peak closing speed",            "measurement",      "speed",    "km/h",  null),
            RideSummarySensor("closing_speed_p90_kmh",    "Closing speed (p90)",           "measurement",      "speed",    "km/h",  null),
            RideSummarySensor("min_lateral_clearance_m",  "Tightest clearance",            "measurement",      "distance", "m",     2),
            RideSummarySensor("distance_ridden_km",       "Distance ridden",               "total_increasing", "distance", "km",    2),
            RideSummarySensor("exposure_seconds",         "Time with traffic",             "total_increasing", "duration", "s",     null),
            RideSummarySensor("close_pass_conversion_rate", "Close-pass conversion rate",  "measurement",      null,       "%",     1),
        )
        var allOk = true
        for (s in sensors) {
            val topic = "$DISCOVERY_PREFIX/sensor/varia_${slug}_${s.field}/config"
            val payload = JSONObject()
                .put("object_id", "varia_${slug}_${s.field}")
                .put("unique_id", "varia_${slug}_${s.field}")
                .put("name", s.displayName)
                .put("has_entity_name", true)
                .put("state_topic", stateTopic)
                .put("value_template", "{{ value_json.${s.field} | default('unknown') }}")
                .put("json_attributes_topic", stateTopic)
                .put("state_class", s.stateClass)
                .put("expire_after", RIDE_SUMMARY_EXPIRE_AFTER_S)
                .put("device", device)
            if (s.deviceClass != null) payload.put("device_class", s.deviceClass)
            if (s.unit != null) payload.put("unit_of_measurement", s.unit)
            if (s.precision != null) payload.put("suggested_display_precision", s.precision)
            if (!publishMqtt(topic, payload.toString(), retain = true)) allOk = false
        }
        return allOk
    }

    /**
     * Publishes the current ride-summary snapshot to the shared state topic.
     * The retained JSON includes scalar fields (read by each sensor's
     * value_template) plus structured attributes (`tightest_pass`,
     * `ride_started_at`).
     */
    suspend fun publishRideSummaryState(slug: String, snapshot: RideStatsSnapshot): Boolean {
        // Nullable fields are omitted from the JSON when they have no data
        // yet. The discovery template's `default('unknown')` then renders
        // the entity as Unknown in HA rather than a misleading 0.
        val payload = JSONObject()
            .put("overtakes_total", snapshot.overtakesTotal)
            .put("close_pass_count", snapshot.closePassCount)
            .put("grazing_count", snapshot.grazingCount)
            .put("hgv_close_pass_count", snapshot.hgvClosePassCount)
            .put("distance_ridden_km", snapshot.distanceRiddenKm.toDouble())
            .put("exposure_seconds", snapshot.exposureSeconds)
            .put("close_pass_conversion_rate", snapshot.closePassConversionRatePct.toDouble())
            .put("ride_started_at", java.time.Instant.ofEpochMilli(snapshot.rideStartedAtMs).toString())
        snapshot.peakClosingKmh?.let { payload.put("peak_closing_kmh", it) }
        snapshot.closingSpeedP90Kmh?.let { payload.put("closing_speed_p90_kmh", it) }
        snapshot.minLateralClearanceM?.let { payload.put("min_lateral_clearance_m", it.toDouble()) }
        snapshot.tightestPass?.let { tp ->
            payload.put(
                "tightest_pass",
                JSONObject()
                    .put("ts", java.time.Instant.ofEpochMilli(tp.tsMs).toString())
                    .put("side", tp.side.name.lowercase())
                    .put("vehicle_size", tp.vehicleSize.name)
                    .put("clearance_m", tp.clearanceM.toDouble())
                    .put("closing_kmh", tp.closingKmh)
                    .put("range_y_m", tp.rangeYAtMinM.toDouble()),
            )
        }
        return publishMqtt("varia/$slug/ride_summary", payload.toString(), retain = true)
    }

    private data class RideSummarySensor(
        val field: String,
        val displayName: String,
        val stateClass: String,
        val deviceClass: String?,
        val unit: String?,
        val precision: Int?,
    )

    companion object {
        private const val TAG = "BikeRadar"
        private const val DISCOVERY_PREFIX = "homeassistant"
        // 3 s connect+read keeps the radio awake at most ~6 s on a failed
        // publish during a flaky cell handover; the next heartbeat will
        // retry. HA endpoints used here are mqtt.publish (broker queue
        // write) and /api/ ping - neither does HA-side processing that
        // would justify a longer timeout.
        private const val HA_TIMEOUT_MS = 3000

        // 10 minutes. Past this, the ride-summary sensors flip to
        // `unavailable` so HA dashboards don't show stale numbers from the
        // last ride forever.
        private const val RIDE_SUMMARY_EXPIRE_AFTER_S = 600
    }
}
