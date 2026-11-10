// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.HaFailure
import es.jjrh.bikeradar.HaFailureException
import es.jjrh.bikeradar.HaHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * What each failed connection test shows on setup's Home Assistant step and on
 * Settings: a chip saying what failed, one line saying what to do, both in the
 * rider's language, and the same words read out by TalkBack. A failure the app
 * does not recognise shows its status or class name, never its message, which
 * is English and can carry the rider's host.
 */
@RunWith(AndroidJUnit4::class)
class HaTestResultCopyTest {

    @get:Rule val compose = createComposeRule()

    private class Case(
        val error: Throwable,
        val mqtt: Boolean,
        val chip: String,
        val fix: String,
    )

    private fun failure(f: HaFailure, detail: String? = null) = HaFailureException(f, detail, "secret.example.com said no")

    private val english = listOf(
        Case(failure(HaFailure.URL_INVALID), false, "URL: not valid", "Not saved. The URL must start with https:// or http:// and have no spaces, like https://homeassistant.local:8123."),
        Case(failure(HaFailure.INSECURE_REFUSED), false, "URL: HTTPS needed", "Nothing was sent. Outside your home network the URL must start with https://, so your token never travels unprotected."),
        Case(failure(HaFailure.HOST_UNREACHABLE, "ConnectException"), false, "HA: unreachable", "Nothing answered at that URL. Check the address and port, and that Home Assistant is on and this phone can reach it from this network."),
        Case(failure(HaFailure.TLS, "SSLHandshakeException"), false, "HA: secure connection failed", "Android does not trust that server's certificate, or that port is not HTTPS. On your home network you can use http:// instead."),
        Case(failure(HaFailure.AUTH, "401"), false, "HA: token rejected", "Home Assistant rejected the token. Create a new long-lived access token and paste it here."),
        Case(failure(HaFailure.NOT_HOME_ASSISTANT, "404"), false, "HA: not at this URL", "Something answered, but not Home Assistant (code 404). Check the address and port, usually 8123."),
        Case(failure(HaFailure.SERVER_ERROR, "503"), false, "HA: server error", "Home Assistant answered with an error (code 503). Try again in a moment."),
        Case(failure(HaFailure.UNKNOWN, "418"), false, "HA: couldn't connect", "The test failed for a reason the app doesn't recognise (418). Check the URL and token and try again."),
        Case(IOException("secret.example.com said no"), false, "HA: couldn't connect", "The test failed for a reason the app doesn't recognise (IOException). Check the URL and token and try again."),
        Case(failure(HaFailure.MQTT_MISSING, "404"), true, "MQTT: not configured in HA", "Home Assistant answers, but MQTT may not be set up in it. Check that the MQTT integration is added in HA: Settings → Devices & services."),
        Case(failure(HaFailure.MQTT_AUTH, "403"), true, "MQTT: token not allowed", "Home Assistant answers, but won't let this token publish."),
        Case(failure(HaFailure.MQTT_FAILED, "503"), true, "MQTT: check failed", "Home Assistant answers, but the MQTT check failed (503). Try again."),
    )

    private val spanish = listOf(
        Case(failure(HaFailure.URL_INVALID), false, "URL: no válida", "No se ha guardado. La URL debe empezar por https:// o http:// y no llevar espacios, como https://homeassistant.local:8123."),
        Case(failure(HaFailure.INSECURE_REFUSED), false, "URL: hace falta HTTPS", "No se ha enviado nada. Fuera de tu red doméstica la URL debe empezar por https://, para que tu token nunca viaje sin cifrar."),
        Case(failure(HaFailure.HOST_UNREACHABLE, "ConnectException"), false, "HA: sin conexión", "Nada responde en esa URL. Revisa la dirección y el puerto, y que Home Assistant esté encendido y accesible desde esta red."),
        Case(failure(HaFailure.TLS, "SSLHandshakeException"), false, "HA: fallo de conexión segura", "Android no confía en el certificado de ese servidor, o ese puerto no es HTTPS. En tu red doméstica puedes usar http://."),
        Case(failure(HaFailure.AUTH, "401"), false, "HA: token rechazado", "Home Assistant ha rechazado el token. Crea un token de acceso de larga duración nuevo y pégalo aquí."),
        Case(failure(HaFailure.NOT_HOME_ASSISTANT, "404"), false, "HA: no está en esa URL", "Algo ha respondido, pero no es Home Assistant (código 404). Revisa la dirección y el puerto, normalmente 8123."),
        Case(failure(HaFailure.SERVER_ERROR, "503"), false, "HA: error del servidor", "Home Assistant ha respondido con un error (código 503). Inténtalo de nuevo en un momento."),
        Case(failure(HaFailure.UNKNOWN, "418"), false, "HA: conexión fallida", "La prueba ha fallado por un motivo que la app no reconoce (418). Revisa la URL y el token e inténtalo de nuevo."),
        Case(IOException("secret.example.com said no"), false, "HA: conexión fallida", "La prueba ha fallado por un motivo que la app no reconoce (IOException). Revisa la URL y el token e inténtalo de nuevo."),
        Case(failure(HaFailure.MQTT_MISSING, "404"), true, "MQTT: sin configurar en HA", "Home Assistant responde, pero puede que no tenga MQTT configurado. Comprueba que la integración MQTT esté añadida en HA: Ajustes → Dispositivos y servicios."),
        Case(failure(HaFailure.MQTT_AUTH, "403"), true, "MQTT: token sin permiso", "Home Assistant responde, pero no deja publicar con este token."),
        Case(failure(HaFailure.MQTT_FAILED, "503"), true, "MQTT: prueba fallida", "Home Assistant responde, pero la prueba de MQTT ha fallado (503). Inténtalo de nuevo."),
    )

    private val shown = mutableStateOf<Case?>(null)

    private val Case.ping: Result<String> get() = if (mqtt) Result.success("OK (200)") else Result.failure(error)
    private val Case.mqttResult: Result<String>? get() = if (mqtt) Result.failure(error) else null

    private fun showSetup() = compose.setContent {
        val case = shown.value
        UiTheme {
            Column {
                HaFieldsBlock(
                    urlField = "https://homeassistant.local:8123",
                    onUrlChange = {},
                    tokenField = "tok",
                    onTokenChange = {},
                    tokenVisible = false,
                    onToggleTokenVisible = {},
                    pingResult = case?.ping,
                    mqttResult = case?.mqttResult,
                    pinging = false,
                    canSubmit = true,
                    onTest = {},
                    onChangeIntent = {},
                )
            }
        }
    }

    private fun showSettings() = compose.setContent {
        val case = shown.value
        UiTheme {
            SettingsHaContent(
                urlField = "https://homeassistant.local:8123",
                onUrlChange = {},
                tokenField = "tok",
                onTokenChange = {},
                tokenVisible = false,
                onToggleTokenVisible = {},
                pingResult = case?.ping,
                mqttResult = case?.mqttResult,
                pinging = false,
                haHealth = HaHealth.Unknown,
                haConfigured = true,
                onBack = {},
                onTestAndSave = {},
                onSaveWithoutTesting = {},
                onClear = {},
            )
        }
    }

    private val liveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private fun assertEachCase(cases: List<Case>, okChip: String, mqttOk: String, prefix: String) {
        for (case in cases) {
            assertTrue("${case.chip} is over 30 characters", case.chip.length <= 30)
            shown.value = case
            compose.waitForIdle()
            // Chips render upper case.
            compose.onNode(hasText(case.chip, ignoreCase = true)).assertExists()
            compose.onNode(hasText(case.fix)).assertExists()
            val chips = if (case.mqtt) "$okChip. ${case.chip}" else case.chip
            val description = compose.onNode(liveRegion).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            assertEquals(listOf("$prefix$chips. ${case.fix}"), description)
            if (case.mqtt) compose.onNode(hasText(okChip, ignoreCase = true)).assertExists()
            if (!case.mqtt) compose.onNode(hasText(mqttOk, ignoreCase = true)).assertDoesNotExist()
            assertTrue(compose.onAllNodesWithText("secret.example.com", substring = true).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun setupSaysWhatFailedAndWhatToDo() {
        showSetup()
        assertEachCase(english, "HA: connected", "MQTT: ready", "Home Assistant test: ")
    }

    @Test
    @Config(qualifiers = "es")
    fun setupSaysItInSpanish() {
        showSetup()
        assertEachCase(spanish, "HA: conectado", "MQTT: listo", "Prueba de Home Assistant: ")
    }

    @Test
    fun settingsSaysWhatFailedAndWhatToDo() {
        showSettings()
        assertEachCase(english, "HA: saved", "MQTT: ready", "Home Assistant test: ")
    }

    @Test
    @Config(qualifiers = "es")
    fun settingsSaysItInSpanish() {
        showSettings()
        assertEachCase(spanish, "HA: guardado", "MQTT: listo", "Prueba de Home Assistant: ")
    }

    @Test
    fun aPassingTestIsReadOutWithNoLineUnderTheChips() {
        compose.setContent {
            UiTheme {
                HaTestResult(Result.success("OK (200)"), Result.success("MQTT OK"), "HA: saved", androidx.compose.ui.text.TextStyle())
            }
        }
        val description = compose.onNode(liveRegion).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("Home Assistant test: HA: saved. MQTT: ready"), description)
        val texts = compose.onNode(liveRegion).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf("HA: SAVED", "MQTT: READY"), texts)
    }

    private val lastErrorShown = mutableStateOf<HaFailure>(HaFailure.UNKNOWN)

    private fun showCard() = compose.setContent {
        UiTheme {
            SettingsHaContent(
                urlField = "https://homeassistant.local:8123",
                onUrlChange = {},
                tokenField = "tok",
                onTokenChange = {},
                tokenVisible = false,
                onToggleTokenVisible = {},
                pingResult = null,
                mqttResult = null,
                pinging = false,
                haHealth = HaHealth.Error("secret.example.com said no", atMs = 0L, cause = lastErrorShown.value),
                haConfigured = true,
                onBack = {},
                onTestAndSave = {},
                onSaveWithoutTesting = {},
                onClear = {},
            )
        }
    }

    /** Every cause a publish can leave, as the card's last error. */
    @Test
    fun theCardsLastErrorSaysWhatFailed() {
        showCard()
        for (
        (cause, text) in listOf(
            HaFailure.AUTH to "Last error: HA: token rejected",
            HaFailure.NOT_HOME_ASSISTANT to "Last error: HA: not at this URL",
            HaFailure.INSECURE_REFUSED to "Last error: URL: HTTPS needed",
            HaFailure.URL_INVALID to "Last error: URL: not valid",
            HaFailure.HOST_UNREACHABLE to "Last error: HA: unreachable",
            HaFailure.TLS to "Last error: HA: secure connection failed",
            HaFailure.SERVER_ERROR to "Last error: HA: server error",
            HaFailure.UNKNOWN to "Last error: HA: couldn't connect",
        )
        ) {
            lastErrorShown.value = cause
            compose.waitForIdle()
            compose.onNode(hasText(text)).assertExists()
        }
        assertTrue(compose.onAllNodesWithText("secret.example.com", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "es")
    fun theCardsLastErrorInSpanish() {
        lastErrorShown.value = HaFailure.AUTH
        showCard()
        compose.onNode(hasText("Último error: HA: token rechazado")).assertExists()
    }
}
