// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi goldens for the three HA-step branches: the UNSET chooser,
 * the YES fields block (empty + populated), and the NO skipped card.
 * Locks the visual contract so a future refactor that breaks the
 * chooser, pill, or skipped-card layout fails the local QC gate.
 *
 * Tests render the leaf composables directly so no Prefs / HaCredentials
 * scaffolding is needed — those are exercised by ScreenSmokeTest. Here
 * we only assert layout.
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class HaStepSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    /**
     * Mirrors the parent Column inside [HaStep] — same horizontal padding
     * and 14 dp vertical gap — so the leaf composables sit at the same
     * positions they would on the real screen.
     */
    @Composable
    private fun StepShell(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }

    @Test
    fun unsetChooser() {
        paparazzi.snapshot {
            UiTheme {
                StepShell {
                    HaIntentChooser(onUseHa = {}, onNotForMe = {})
                }
            }
        }
    }

    @Test
    fun yesEmptyFields() {
        paparazzi.snapshot {
            UiTheme {
                StepShell {
                    HaFieldsBlock(
                        urlField = "",
                        onUrlChange = {},
                        tokenField = "",
                        onTokenChange = {},
                        tokenVisible = false,
                        onToggleTokenVisible = {},
                        pingResult = null,
                        pinging = false,
                        canSubmit = false,
                        onTest = {},
                        onChangeIntent = {},
                    )
                }
            }
        }
    }

    @Test
    fun yesPrefilled() {
        paparazzi.snapshot {
            UiTheme {
                StepShell {
                    HaFieldsBlock(
                        urlField = "https://homeassistant.local:8123",
                        onUrlChange = {},
                        tokenField = "eyJ0eXAiOiJKV1QiLCJh.fake.token",
                        onTokenChange = {},
                        tokenVisible = false,
                        onToggleTokenVisible = {},
                        pingResult = null,
                        pinging = false,
                        canSubmit = true,
                        onTest = {},
                        onChangeIntent = {},
                    )
                }
            }
        }
    }

    @Test
    fun noSkipped() {
        paparazzi.snapshot {
            UiTheme {
                StepShell {
                    HaSkippedCard(onChangeMind = {})
                }
            }
        }
    }
}
