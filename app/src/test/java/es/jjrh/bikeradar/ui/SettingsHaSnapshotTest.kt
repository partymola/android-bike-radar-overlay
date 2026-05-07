// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import es.jjrh.bikeradar.HaHealth
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the [SettingsHaContent] leaf. The body owns
 * the saved-creds slot and the test/save coroutines; this leaf only
 * renders the resolved state, so snapshot variants are the visually
 * distinct combinations of (fields populated?) × (HA health).
 *
 * Variants:
 *  - empty: no fields filled, never tested
 *  - populated: fields filled, no test result yet
 *  - savedHealthOk: fields populated + healthy MQTT discovery
 *  - savedHealthError: fields populated + recent HA error
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class SettingsHaSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @Test
    fun empty() {
        paparazzi.snapshot {
            UiTheme {
                SettingsHaContent(
                    urlField = "",
                    onUrlChange = {},
                    tokenField = "",
                    onTokenChange = {},
                    tokenVisible = false,
                    onToggleTokenVisible = {},
                    pingResult = null,
                    mqttResult = null,
                    pinging = false,
                    haHealth = HaHealth.Unknown,
                    haConfigured = false,
                    onBack = {},
                    onTestAndSave = {},
                    onSaveWithoutTesting = {},
                    onClear = {},
                )
            }
        }
    }

    @Test
    fun populated() {
        paparazzi.snapshot {
            UiTheme {
                SettingsHaContent(
                    urlField = "https://homeassistant.local:8123",
                    onUrlChange = {},
                    tokenField = "eyJ0eXAiOiJKV1QiLCJh.fake.token",
                    onTokenChange = {},
                    tokenVisible = false,
                    onToggleTokenVisible = {},
                    pingResult = null,
                    mqttResult = null,
                    pinging = false,
                    haHealth = HaHealth.Unknown,
                    haConfigured = false,
                    onBack = {},
                    onTestAndSave = {},
                    onSaveWithoutTesting = {},
                    onClear = {},
                )
            }
        }
    }

    @Test
    fun savedHealthOk() {
        paparazzi.snapshot {
            UiTheme {
                SettingsHaContent(
                    urlField = "https://homeassistant.local:8123",
                    onUrlChange = {},
                    tokenField = "eyJ0eXAiOiJKV1QiLCJh.fake.token",
                    onTokenChange = {},
                    tokenVisible = false,
                    onToggleTokenVisible = {},
                    pingResult = Result.success("ok"),
                    mqttResult = Result.success("ready"),
                    pinging = false,
                    haHealth = HaHealth.Ok,
                    haConfigured = true,
                    onBack = {},
                    onTestAndSave = {},
                    onSaveWithoutTesting = {},
                    onClear = {},
                )
            }
        }
    }

    @Test
    fun savedHealthError() {
        paparazzi.snapshot {
            UiTheme {
                SettingsHaContent(
                    urlField = "https://homeassistant.local:8123",
                    onUrlChange = {},
                    tokenField = "eyJ0eXAiOiJKV1QiLCJh.fake.token",
                    onTokenChange = {},
                    tokenVisible = false,
                    onToggleTokenVisible = {},
                    pingResult = Result.failure(Exception("connection refused")),
                    mqttResult = null,
                    pinging = false,
                    haHealth = HaHealth.Error("connection refused", atMs = 0L),
                    haConfigured = true,
                    onBack = {},
                    onTestAndSave = {},
                    onSaveWithoutTesting = {},
                    onClear = {},
                )
            }
        }
    }
}
