// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress

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

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
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

    @Test
    fun aMissingMqttIntegrationIsReported() {
        HaCredentials(app).save("http://127.0.0.1:${server.address.port}", "tok")
        val prefs = Prefs(app)
        compose.setContent { SettingsHa(rememberNavController(), prefs) }
        compose.onNodeWithText("Test and save").performScrollTo().performClick()
        // Chips render upper case.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("MQTT integration is not enabled", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
