// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards on the [HaClient] entrypoints that have no business hitting the
 * network: empty-credential and malformed-URL paths must short-circuit
 * BEFORE any HttpURLConnection is opened. A regression here ships as
 * "phone tries to talk to localhost on every overtake" — a battery,
 * privacy, and stability footgun.
 *
 * The full publish path is exercised live; this layer only verifies the
 * "don't even try" branches that would otherwise be silent.
 */
@RunWith(RobolectricTestRunner::class)
class HaClientGuardsTest {

    @Test
    fun isConfiguredFalseForEmpty() {
        assertFalse(HaClient(baseUrl = "", token = "").isConfigured())
    }

    @Test
    fun isConfiguredFalseForBlankWhitespace() {
        assertFalse(HaClient(baseUrl = "   ", token = "tok").isConfigured())
        assertFalse(HaClient(baseUrl = "https://h", token = "  ").isConfigured())
    }

    @Test
    fun isConfiguredTrueWhenBothPresent() {
        assertTrue(HaClient(baseUrl = "https://h.example", token = "tok").isConfigured())
    }

    @Test
    fun publishMqttReturnsFalseWhenUnconfigured() = runTest {
        val client = HaClient(baseUrl = "", token = "")
        assertFalse(client.publishMqtt("topic", "payload", retain = false))
    }

    @Test
    fun publishMqttReturnsFalseForCleartextWanUrl() = runTest {
        // 8.8.8.8 is a non-LAN address; HaUrlPolicy must refuse cleartext
        // bearer-token transmission to it. publishMqtt should bail before
        // opening a connection.
        val client = HaClient(baseUrl = "http://8.8.8.8", token = "tok")
        assertFalse(client.publishMqtt("topic", "payload", retain = false))
    }

    @Test
    fun publishMqttReturnsFalseForMalformedUrl() = runTest {
        val client = HaClient(baseUrl = "not a url at all", token = "tok")
        assertFalse(client.publishMqtt("topic", "payload", retain = false))
    }

    @Test
    fun pingFailsWhenUnconfigured() = runTest {
        val r = HaClient(baseUrl = "", token = "").ping()
        assertTrue(r.isFailure)
    }

    @Test
    fun probeMqttServiceFailsWhenUnconfigured() = runTest {
        val r = HaClient(baseUrl = "", token = "").probeMqttService()
        assertTrue(r.isFailure)
    }

    @Test
    fun publishBatteryDiscoveryReturnsFalseWhenUnconfigured() = runTest {
        val client = HaClient(baseUrl = "", token = "")
        assertFalse(client.publishBatteryDiscovery("rearvue8", "RearVue8"))
    }

    @Test
    fun publishBatteryStateReturnsFalseWhenUnconfigured() = runTest {
        val client = HaClient(baseUrl = "", token = "")
        assertFalse(client.publishBatteryState("rearvue8", 75))
    }

    @Test
    fun publishClosePassDiscoveryReturnsFalseWhenUnconfigured() = runTest {
        val client = HaClient(baseUrl = "", token = "")
        assertFalse(client.publishClosePassDiscovery("rearvue8", "RearVue8"))
    }

    @Test
    fun closePassDiscoveryPayloadOmitsValueTemplate() {
        // Regression test for the v0.6.0 close-pass bug: a `value_template`
        // rendering to a bare string caused HA's MQTT-event integration
        // to drop every event with "No valid JSON event payload detected".
        // The published event JSON already has a top-level `event_type`
        // field, so the integration parses it natively when no template
        // is set.
        val payload = HaClient(baseUrl = "https://h.example", token = "tok")
            .buildClosePassDiscoveryPayload("rearvue8", "RearVue8")
        assertFalse(
            "value_template breaks HA's mqtt event entity",
            payload.has("value_template"),
        )
        // Sanity: the discovery still lists close_pass as an accepted
        // event type, otherwise the integration rejects events whose
        // event_type isn't in the configured set.
        val eventTypes = payload.getJSONArray("event_types")
        assertTrue(
            "event_types must include close_pass",
            (0 until eventTypes.length()).any { eventTypes.getString(it) == "close_pass" },
        )
        // HA binds entity_id to (unique_id, object_id) on first publish;
        // diverging the two creates a duplicate entity that ignores the
        // dashboard's bindings (issue home-assistant/core#124259).
        assertEquals(
            "unique_id must equal object_id to preserve entity binding",
            payload.getString("unique_id"),
            payload.getString("object_id"),
        )
        // event-state and attributes share the topic; if they ever
        // diverge HA can't correlate the event with its payload.
        assertEquals(
            "state_topic must equal json_attributes_topic",
            payload.getString("state_topic"),
            payload.getString("json_attributes_topic"),
        )
    }

    @Test
    fun rideSummaryDiscoveryUsesPayloadNoneSentinel() {
        // Regression test for the ride-summary "no value" bug: three
        // nullable numeric sensors (peak_closing_kmh, closing_speed_p90_kmh,
        // min_lateral_clearance_m) are omitted from the state JSON until they
        // have data. The value_template's default() must render HA's
        // PAYLOAD_NONE sentinel ("None") so the sensor goes Unknown. The
        // bare "unknown" sentinel makes HA try to parse it as a float for
        // these speed/distance sensors and reject the payload.
        val payloads = HaClient(baseUrl = "https://h.example", token = "tok")
            .buildRideSummaryDiscoveryPayloads("rearvue8", "RearVue8")
        for ((_, payload) in payloads) {
            val template = payload.getString("value_template")
            assertFalse(
                "value_template for ${payload.getString("object_id")} must not use the " +
                    "bare 'unknown' sentinel (HA only maps 'None' to Unknown)",
                template.contains("default('unknown')"),
            )
            assertTrue(
                "value_template for ${payload.getString("object_id")} must use " +
                    "default('None') so missing values render as HA's PAYLOAD_NONE",
                template.contains("default('None')"),
            )
        }
    }
}
