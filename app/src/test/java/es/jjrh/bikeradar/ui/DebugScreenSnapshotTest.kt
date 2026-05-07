// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_9_PRO_XL
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Paparazzi goldens for the two stateless leaves of the Debug screen:
 *  - [DebugScenarioControls] for the Replay / Synthetic state row
 *  - [DebugCaptureLogList] for the capture-log file list
 *
 * The radar-state log feed is intentionally out of scope — it's a
 * streaming source unsuitable for static goldens. We also avoid the
 * media-projection consent flow by snapshotting these two stateless
 * leaves directly.
 *
 * Capture-log timestamps are pinned via [File.setLastModified] so the
 * formatted "yyyy-MM-dd HH:mm" string in the card is deterministic.
 *
 * CI does not run these — Paparazzi 2.0.0-SNAPSHOT's layoutlib loader
 * fails on cold-cache JVMs. Run locally with `:app:verifyPaparazziDebug`;
 * regenerate with `:app:recordPaparazziDebug --rerun-tasks`.
 */
class DebugScreenSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PIXEL_9_PRO_XL)

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** A pinned wall-clock timestamp so card mtimes don't drift run to run. */
    private val pinnedMs = 1_700_000_000_000L  // 2023-11-14 22:13 UTC

    /** Build a deterministic capture-log [File] with a fixed size and mtime. */
    private fun fakeLog(name: String, kb: Int, mtime: Long): File {
        val f = tempFolder.newFile(name)
        f.writeBytes(ByteArray(kb * 1024))
        f.setLastModified(mtime)
        return f
    }

    @Test
    fun scenarioIdle() {
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
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
        paparazzi.snapshot {
            UiTheme {
                DebugCaptureLogList(
                    logFiles = files,
                    onShare = {},
                )
            }
        }
    }
}
