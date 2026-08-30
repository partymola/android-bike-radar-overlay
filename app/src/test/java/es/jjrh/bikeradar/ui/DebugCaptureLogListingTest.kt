// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.BikeRadarService
import es.jjrh.bikeradar.CaptureLogManager
import org.junit.After
import org.junit.Assert.assertEquals
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
        BikeRadarService.flushCaptureLogForUi = null
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
    fun sharingFlushesFirstAndOnlyThenAsksTheWarning() {
        // Order is the point: an unflushed in-progress log is shared missing
        // its most recent window, which is the part a reporter cares about.
        val calls = mutableListOf<String>()
        onCaptureLogShareRequested(
            flush = { calls += "flush" },
            warningSeen = false,
            share = { calls += "share" },
            requestWarning = { calls += "warn" },
        )
        assertEquals(listOf("flush", "warn"), calls)
    }

    @Test
    fun sharingFlushesEvenWhenTheWarningIsAlreadySeen() {
        val calls = mutableListOf<String>()
        onCaptureLogShareRequested(
            flush = { calls += "flush" },
            warningSeen = true,
            share = { calls += "share" },
            requestWarning = { calls += "warn" },
        )
        assertEquals(listOf("flush", "share"), calls)
    }

    @Test
    fun theServiceFlushHookIsInstallableInvokableAndClearable() {
        // The contract the Debug screen depends on: present while the service
        // runs, gone after it stops. A hook left installed past onDestroy would
        // call into a released CaptureLogManager on the next share.
        var flushed = 0
        BikeRadarService.flushCaptureLogForUi = { flushed++ }
        BikeRadarService.flushCaptureLogForUi?.invoke()
        assertEquals(1, flushed)

        BikeRadarService.flushCaptureLogForUi = null
        BikeRadarService.flushCaptureLogForUi?.invoke()
        assertEquals("a cleared hook must not still fire", 1, flushed)
    }

    @Test
    fun sharingWorksWithNoServiceRunning() {
        // The hook is null when the service is down, and a log from an earlier
        // session is still shareable then. A non-null assertion here would
        // crash the Debug screen for the commonest case: app open, ride over.
        val calls = mutableListOf<String>()
        onCaptureLogShareRequested(
            flush = null,
            warningSeen = true,
            share = { calls += "share" },
            requestWarning = { calls += "warn" },
        )
        assertEquals(listOf("share"), calls)
    }

    @Test
    fun deleteAllSparesTheLogBeingWritten() {
        // Listing the open file for sharing removed the accident that used to
        // protect Delete all from it. Unlinking it under the live writer loses
        // the whole session in silence: the writer feeds an unlinked inode, no
        // replacement file opens while the writer lives, and the close-time
        // gzip skips because the source is gone.
        val open = writeLog("bike-radar-capture-open.log")
        val closed = writeLog("bike-radar-capture-closed.log.gz")

        val deletable = deletableCaptureLogs(listOf(open, closed), open.name).map { it.name }

        assertEquals(listOf(closed.name), deletable)
    }

    @Test
    fun deleteAllActuallyRemovesTheFilesItIsAllowedTo() {
        // The guard and the deletion have to agree, so the same function does
        // both: a filter that is correct while the loop beside it iterates the
        // unfiltered list is exactly the defect this replaces.
        val open = writeLog("bike-radar-capture-open.log")
        val closed = writeLog("bike-radar-capture-closed.log.gz")

        val deleted = deleteCaptureLogsExceptActive(listOf(open, closed), open.name)

        assertEquals(1, deleted)
        assertTrue("the recording log must survive on disk", open.exists())
        assertTrue("the closed log must be gone from disk", !closed.exists())
    }

    @Test
    fun deleteAllRemovesEverythingWhenNothingIsRecording() {
        // The other direction, so the guard cannot be widened into a no-op:
        // with no active log, Delete all still means all.
        val a = writeLog("bike-radar-capture-open.log")
        val b = writeLog("bike-radar-capture-closed.log.gz")

        val deletable = deletableCaptureLogs(listOf(a, b), activeName = null).map { it.name }

        assertEquals(listOf(a.name, b.name), deletable)
    }

    @Test
    fun aNonCaptureFileIsNotListed() {
        writeLog("notes.txt")

        val listed = enumerateCaptureLogs(app).map { it.name }

        assertTrue("only capture logs belong in this list: $listed", !listed.contains("notes.txt"))
    }
}
