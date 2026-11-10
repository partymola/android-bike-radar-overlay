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
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
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
 * Settings' Test and save reports the MQTT integration from the shared
 * connection test, driven through the real screen against a loopback Home
 * Assistant. The setup half is `HaStepConnectionTest`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsHaConnectionTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer

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
            val code = if (exchange.requestURI.path == "/api/") 200 else 404
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

    private fun openSettings() {
        HaCredentials(app).save(baseUrl, "tok")
        val prefs = Prefs(app)
        compose.setContent { SettingsHa(rememberNavController(), prefs) }
    }

    // Chips render upper case.
    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aMissingMqttIntegrationIsReported() {
        openSettings()
        compose.onNodeWithText("Test and save").performScrollTo().performClick()
        waitForText("MQTT integration is not enabled")
    }

    /** The same check setup's Continue runs. */
    @Test
    fun aCleartextAddressOutsideTheHomeNetworkIsNotSavedWithoutTesting() {
        openSettings()
        compose.onNodeWithText(baseUrl).performTextReplacement("http://example.com:8123")
        compose.onNodeWithText("Save without testing").performScrollTo().performClick()
        waitForText("Refused")
        assertEquals(baseUrl, HaCredentials(app).baseUrl)
    }

    /** The result belongs to the address that was tested, not the one typed since. */
    @Test
    fun anAddressEditedWhileTheTestRunsIsNeitherReportedNorSaved() {
        pingHeld = CountDownLatch(1)
        openSettings()
        compose.onNodeWithText("Test and save").performScrollTo().performClick()
        assertTrue(pingArrived.await(5, TimeUnit.SECONDS))
        compose.onNodeWithText(baseUrl).performTextReplacement("http://127.0.0.1:1")
        pingHeld!!.countDown()
        waitForText("Test and save")
        compose.onNodeWithText("HA: saved", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("MQTT integration is not enabled", substring = true, ignoreCase = true).assertDoesNotExist()
        assertEquals(baseUrl, HaCredentials(app).baseUrl)
        assertEquals(0L, Prefs(app).haLastValidatedEpochMs)
    }

    /** The token likewise. */
    @Test
    fun aTokenEditedWhileTheTestRunsIsNeitherReportedNorSaved() {
        pingHeld = CountDownLatch(1)
        openSettings()
        compose.onNodeWithText("Test and save").performScrollTo().performClick()
        assertTrue(pingArrived.await(5, TimeUnit.SECONDS))
        compose.onNodeWithContentDescription("Show token").performClick()
        compose.onNodeWithText("tok").performTextReplacement("other")
        pingHeld!!.countDown()
        waitForText("Test and save")
        compose.onNodeWithText("HA: saved", ignoreCase = true).assertDoesNotExist()
        assertEquals("tok", HaCredentials(app).token)
        assertEquals(0L, Prefs(app).haLastValidatedEpochMs)
    }
}
