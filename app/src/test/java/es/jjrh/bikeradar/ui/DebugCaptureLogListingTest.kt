// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.CaptureLogManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The Debug screen's capture-log list must show the log that is STILL BEING
 * WRITTEN.
 *
 * This is the whole retrieval path for a remote hardware report. The setup
 * transcript deliberately stays open across the reconnect loop so that a radar
 * retrying every second or so accumulates one file instead of dozens, which
 * means the file a reporter needs is, by design, always the open one. Filtering
 * open files out of the list therefore hid exactly the file the feature exists
 * to produce, and the only way to get at it was a toggle-off step that works
 * only while the radar is still switched on. A radar that is off, or out of
 * range, parks the reconnect loop on a wait that never completes, so that step
 * silently does nothing and the reporter is left with an empty list.
 *
 * If this test goes red because an active-name exclusion came back, the fix is
 * not to change the test.
 */
@RunWith(RobolectricTestRunner::class)
class DebugCaptureLogListingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun capturesDir(): File = File(app.getExternalFilesDir(null), CaptureLogManager.CAPTURE_DIR).apply { mkdirs() }

    private fun writeLog(name: String, bytes: String = "# header\n"): File = File(capturesDir(), name).apply { writeText(bytes) }

    @After
    fun tearDown() {
        BikeRadarService.activeCaptureLogName = null
        capturesDir().listFiles()?.forEach { it.delete() }
    }

    @Test
    fun theLogCurrentlyBeingWrittenIsListed() {
        val open = writeLog("bike-radar-capture-open.log")
        BikeRadarService.activeCaptureLogName = open.name

        val listed = enumerateCaptureLogs(app).map { it.name }

        assertTrue(
            "the in-progress log must be listed, it is the one a reporter needs: $listed",
            listed.contains(open.name),
        )
    }

    @Test
    fun closedLogsAreListedAlongsideTheOpenOne() {
        val closed = writeLog("bike-radar-capture-closed.log.gz")
        val open = writeLog("bike-radar-capture-open.log")
        BikeRadarService.activeCaptureLogName = open.name

        val listed = enumerateCaptureLogs(app).map { it.name }

        assertTrue("closed log missing: $listed", listed.contains(closed.name))
        assertTrue("open log missing: $listed", listed.contains(open.name))
    }

    @Test
    fun anEmptyLogIsStillSkipped() {
        // The size gate is separate from the open/closed question and stays:
        // a zero-byte file gives a reporter nothing and a share of it fails.
        val empty = writeLog("bike-radar-capture-empty.log", bytes = "")
        BikeRadarService.activeCaptureLogName = empty.name

        val listed = enumerateCaptureLogs(app).map { it.name }

        assertTrue("a zero-byte log must not be offered: $listed", !listed.contains(empty.name))
    }

    @Test
    fun aNonCaptureFileIsNotListed() {
        writeLog("notes.txt")

        val listed = enumerateCaptureLogs(app).map { it.name }

        assertTrue("only capture logs belong in this list: $listed", !listed.contains("notes.txt"))
    }
}
