// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the three HA-step branches: the UNSET chooser,
 * the YES fields block (empty + populated), and the NO skipped card.
 * Locks the visual contract so a future refactor that breaks the
 * chooser, pill, or skipped-card layout fails the local QC gate.
 *
 * Tests render the leaf composables directly so no Prefs / HaCredentials
 * scaffolding is needed - those are exercised by ScreenSmokeTest. Here
 * we only assert layout.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class HaStepSnapshotTest {

    /**
     * Mirrors the parent Column inside [HaStep] - same horizontal padding
     * and 14 dp vertical gap - so the leaf composables sit at the same
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
        captureRoboImage {
            UiTheme {
                StepShell {
                    HaIntentChooser(onUseHa = {}, onNotForMe = {})
                }
            }
        }
    }

    @Test
    fun yesEmptyFields() {
        captureRoboImage {
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
        captureRoboImage {
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
        captureRoboImage {
            UiTheme {
                StepShell {
                    HaSkippedCard(onChangeMind = {})
                }
            }
        }
    }
}
