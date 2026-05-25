// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Roborazzi goldens for the two stateless leaves of the Debug screen:
 *  - [DebugScenarioControls] for the Replay / Synthetic state row
 *  - [DebugCaptureLogList] for the capture-log file list
 *
 * The radar-state log feed is intentionally out of scope - it's a
 * streaming source unsuitable for static goldens. We also avoid the
 * media-projection consent flow by snapshotting these two stateless
 * leaves directly.
 *
 * Capture-log timestamps are pinned via [File.setLastModified] so the
 * formatted "yyyy-MM-dd HH:mm" string in the card is deterministic.
 *
 * Renders via Robolectric Native Graphics (runs in cold-cache CI). Verify
 * with `:app:verifyRoborazziDebug`; regenerate with `:app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w448dp-h997dp-xxhdpi")
class DebugScreenSnapshotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** A pinned wall-clock timestamp so card mtimes don't drift run to run. */
    private val pinnedMs = 1_700_000_000_000L // fixed epoch for deterministic snapshots

    /** Build a deterministic capture-log [File] with a fixed size and mtime. */
    private fun fakeLog(name: String, kb: Int, mtime: Long): File {
        val f = tempFolder.newFile(name)
        f.writeBytes(ByteArray(kb * 1024))
        f.setLastModified(mtime)
        return f
    }

    @Test
    fun scenarioIdle() {
        captureRoboImage {
            UiTheme {
                DebugScenarioControls(
                    replayRunning = false,
                    syntheticRunning = false,
                    onStartReplay = {},
                    onStopReplay = {},
                    onStartSynthetic = {},
                    onStopSynthetic = {},
                )
            }
        }
    }

    @Test
    fun replayRunning() {
        captureRoboImage {
            UiTheme {
                DebugScenarioControls(
                    replayRunning = true,
                    syntheticRunning = false,
                    onStartReplay = {},
                    onStopReplay = {},
                    onStartSynthetic = {},
                    onStopSynthetic = {},
                )
            }
        }
    }

    @Test
    fun syntheticRunning() {
        captureRoboImage {
            UiTheme {
                DebugScenarioControls(
                    replayRunning = false,
                    syntheticRunning = true,
                    onStartReplay = {},
                    onStopReplay = {},
                    onStartSynthetic = {},
                    onStopSynthetic = {},
                )
            }
        }
    }

    @Test
    fun logsEmpty() {
        captureRoboImage {
            UiTheme {
                DebugCaptureLogList(
                    logFiles = emptyList(),
                    onShare = {},
                )
            }
        }
    }

    @Test
    fun logsPresent() {
        // 5 fake logs spaced 1h apart so the date column is varied
        // without interleaving randomness.
        val files = (0 until 5).map { i ->
            fakeLog(
                name = "bike-radar-capture-$i.log",
                kb = 12 + i * 4,
                mtime = pinnedMs - i * 3_600_000L,
            )
        }
        captureRoboImage {
            UiTheme {
                DebugCaptureLogList(
                    logFiles = files,
                    onShare = {},
                )
            }
        }
    }
}
