// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.test.runTest
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
}
