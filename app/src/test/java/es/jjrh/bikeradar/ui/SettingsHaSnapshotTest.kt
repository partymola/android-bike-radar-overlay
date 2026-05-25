// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import es.jjrh.bikeradar.HaHealth
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the [SettingsHaContent] leaf. The body owns
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
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class SettingsHaSnapshotTest {

    @Test
    fun empty() {
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
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
