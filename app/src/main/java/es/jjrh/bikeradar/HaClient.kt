// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    suspend fun publishMqtt(topic: String, payload: String, retain: Boolean): Boolean {
        if (!isConfigured()) return false
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
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "HA mqtt/publish $topic -> $code")
                }
                code in 200..299
            } catch (t: Throwable) {
                Log.w(TAG, "HA mqtt/publish $topic failed: ${t.javaClass.simpleName}: ${t.message}")
                false
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun ping(): Result<String> {
        if (!isConfigured()) return Result.failure(Exception("Not configured"))
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("${baseUrl.trimEnd('/')}/api/")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) Result.success("OK ($code)")
                    else Result.failure(Exception("HTTP $code"))
                } finally {
                    conn.disconnect()
                }
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
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                }
                try {
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    val code = conn.responseCode
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
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
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
        val ok1 = publishMqtt(topic, payload, retain = false)
        val ok2 = publishMqtt("$topic/last", payload, retain = true)
        return ok1 && ok2
    }

    companion object {
        private const val TAG = "BikeRadar"
        private const val DISCOVERY_PREFIX = "homeassistant"
    }
}
