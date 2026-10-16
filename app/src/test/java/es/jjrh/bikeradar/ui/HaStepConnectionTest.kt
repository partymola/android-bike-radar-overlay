// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.HaIntent
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Setup's Home Assistant test reports the MQTT integration as well as the
 * connection, as Settings does: a Home Assistant without MQTT answers the
 * ping, then nothing the app publishes arrives. Driven through the real
 * step against a loopback Home Assistant, so the wiring is what is tested.
 */
@RunWith(AndroidJUnit4::class)
class HaStepConnectionTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer

    /** Status for the MQTT probe; the ping always answers 200. */
    @Volatile private var probeStatus = 200

    /** When set, the ping counts down [pingArrived] and waits for this before answering. */
    @Volatile private var pingHeld: CountDownLatch? = null
    private val pingArrived = CountDownLatch(1)

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            if (exchange.requestURI.path == "/api/") {
                pingArrived.countDown()
                pingHeld?.await(5, TimeUnit.SECONDS)
            }
            val code = if (exchange.requestURI.path == "/api/") 200 else probeStatus
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun testFromSetup() {
        HaCredentials(app).save(baseUrl, "tok")
        val prefs = Prefs(app).apply { haIntent = HaIntent.YES }
        compose.setContent { UiTheme { HaStep(onContinue = {}, onSkip = {}, prefs = prefs) } }
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
    }

    // Chips render upper case.
    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aWorkingMqttIntegrationIsReported() {
        testFromSetup()
        waitForText("MQTT: ready")
        compose.onNodeWithText("HA: connected", ignoreCase = true).assertExists()
    }

    @Test
    fun aMissingMqttIntegrationIsReported() {
        probeStatus = 404
        testFromSetup()
        waitForText("MQTT integration is not enabled")
        compose.onNodeWithText("HA: connected", ignoreCase = true).assertExists()
    }

    @Test
    fun editingTheAddressAfterATestClearsBothChips() {
        testFromSetup()
        waitForText("MQTT: ready")
        compose.onNodeWithText(baseUrl).performTextReplacement("$baseUrl/")
        compose.onNodeWithText("MQTT: ready", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("HA: connected", ignoreCase = true).assertDoesNotExist()
    }

    /** The result belongs to the address that was tested, not the one typed since. */
    @Test
    fun anAddressEditedWhileTheTestRunsIsNeitherReportedNorSaved() {
        pingHeld = CountDownLatch(1)
        testFromSetup()
        assertTrue(pingArrived.await(5, TimeUnit.SECONDS))
        compose.onNodeWithText(baseUrl).performTextReplacement("http://127.0.0.1:1")
        pingHeld!!.countDown()
        waitForText("Test connection")
        compose.onNodeWithText("HA: connected", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("MQTT: ready", ignoreCase = true).assertDoesNotExist()
        assertEquals(baseUrl, HaCredentials(app).baseUrl)
        assertEquals(0L, Prefs(app).haLastValidatedEpochMs)
    }

    /** The token likewise. */
    @Test
    fun aTokenEditedWhileTheTestRunsIsNeitherReportedNorSaved() {
        pingHeld = CountDownLatch(1)
        testFromSetup()
        assertTrue(pingArrived.await(5, TimeUnit.SECONDS))
        compose.onNodeWithContentDescription("Show token").performClick()
        compose.onNodeWithText("tok").performTextReplacement("other")
        pingHeld!!.countDown()
        waitForText("Test connection")
        compose.onNodeWithText("HA: connected", ignoreCase = true).assertDoesNotExist()
        assertEquals("tok", HaCredentials(app).token)
        assertEquals(0L, Prefs(app).haLastValidatedEpochMs)
    }
}
