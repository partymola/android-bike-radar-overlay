// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * Share hands over a crash report as a file, and the journal can be copied over
 * USB, so what an upgraded install already holds is masked once, in place.
 *
 * A directory holding a file is what stops the pass here: it sits where the
 * sibling goes, and unlike a read-only file it stops a test run as root too.
 */
@RunWith(RobolectricTestRunner::class)
class StoredFilesMaskTest {

    private val app: BikeRadarApp = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private lateinit var root: File
    private lateinit var crashes: File
    private lateinit var journalDir: File
    private lateinit var journal: File

    private val rawReport = "version: 1.10.0 (4200)\nexception: java.lang.IllegalStateException: gatt XYZ73521 at 11:22:33:44:55:66\n\tat a.Foo.run(Foo.kt:1234)\n"
    private val maskedReport = "version: 1.10.0 (4200)\nexception: java.lang.IllegalStateException: gatt XYZ##### at <redacted>\n\tat a.Foo.run(Foo.kt:1234)\n"
    private val rawLines = "1999-01-01 12:34:56 radar link start Cam 58213\n1999-01-01 12:34:57 radar reconnect in 8000ms (backoff=8000ms)\n"
    private val maskedLines = "1999-01-01 12:34:56 radar link start Cam #####\n1999-01-01 12:34:57 radar reconnect in 8000ms (backoff=8000ms)\n"

    @Before
    fun setUp() {
        root = requireNotNull(app.getExternalFilesDir(null))
        crashes = File(root, CrashLogger.CRASH_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        journalDir = File(root, LinkEventJournal.JOURNAL_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        journal = File(journalDir, LinkEventJournal.FILE_NAME)
        prefs = Prefs(app).apply { storedFilesMasked = false }
        ShadowLog.clear()
    }

    private fun redactionWarnings() = ShadowLog.getLogsForTag("BikeRadar.Redaction").map { it.msg }

    private fun report(name: String, text: String = rawReport) = File(crashes, "${CrashLogger.FILE_PREFIX}$name${CrashLogger.FILE_SUFFIX}").apply { writeText(text) }

    private fun blockSiblingOf(file: File): File = File(file.parentFile, ".masking-${file.name}").apply {
        mkdirs()
        File(this, "x").writeText("x")
    }

    private fun pass() = LogRedaction.maskStoredFilesOnce(prefs, root)

    @Test
    fun theAppStartMasksWhatIsOnDisk() {
        val old = report("old")
        journal.writeText(rawLines)
        app.onCreate()
        assertEquals(maskedReport, old.readText())
        assertEquals(maskedLines, journal.readText())
        assertTrue(prefs.storedFilesMasked)
    }

    @Test
    fun itRunsOnlyOnce() {
        pass()
        assertTrue(prefs.storedFilesMasked)
        assertTrue(
            "it is about this phone's files, so it stays out of the backup",
            app.getSharedPreferences("bike_radar_learned", Context.MODE_PRIVATE).getBoolean("stored_files_masked", false),
        )
        val later = report("later")
        journal.writeText(rawLines)
        pass()
        assertEquals("a pass that succeeded does not run again", rawReport, later.readText())
        assertEquals(rawLines, journal.readText())
    }

    @Test
    fun aReportKeepsItsDateAndOtherFilesAreLeftAlone() {
        val old = report("old").apply { setLastModified(1_000_000L) }
        val other = File(crashes, "notes-XYZ73521.txt").apply { writeText("XYZ73521") }
        val otherLines = File(journalDir, "notes-XYZ73521.txt").apply { writeText("XYZ73521") }
        pass()
        assertEquals(maskedReport, old.readText())
        assertEquals("the Debug list and the prune sort on the date", 1_000_000L, old.lastModified())
        assertEquals(emptyList<String>(), redactionWarnings())
        assertEquals("XYZ73521", other.readText())
        assertEquals("XYZ73521", otherLines.readText())
    }

    @Test
    fun aDateThatCannotBeSetDoesNotStopTheMasking() {
        val old = report("old")
        assertTrue(LogRedaction.maskFile(old, setDate = { _, _ -> false }, mask = CrashLogger::maskReport))
        assertEquals(maskedReport, old.readText())
        assertEquals(listOf(old.name), crashes.list()!!.toList())
        assertEquals("one line, naming no file", listOf("could not keep a masked file's date"), redactionWarnings())
    }

    @Test
    fun aRenameThatFailsIsNotASuccess() {
        val old = report("old")
        assertFalse(LogRedaction.maskFile(old, rename = { _, _ -> false }, mask = CrashLogger::maskReport))
        assertEquals("the report stays whole", rawReport, old.readText())
    }

    @Test
    fun oneReportThatCannotBeMaskedLeavesNoOtherRaw() {
        val bad = report("a")
        val obstacle = blockSiblingOf(bad)
        val good = report("b")
        journal.writeText(rawLines)
        pass()
        assertEquals("a failed write leaves the report whole", rawReport, bad.readText())
        assertEquals("the reports after it are still masked", maskedReport, good.readText())
        assertEquals("and so is the journal", maskedLines, journal.readText())
        assertFalse("the pass did not finish", prefs.storedFilesMasked)

        obstacle.deleteRecursively()
        pass()
        assertEquals("the next start retries", maskedReport, bad.readText())
        assertTrue(prefs.storedFilesMasked)
    }

    @Test
    fun aJournalThatCannotBeMaskedIsRetriedAndStillReadsMasked() {
        journal.writeText(rawLines)
        val obstacle = blockSiblingOf(journal)
        pass()
        assertEquals(rawLines, journal.readText())
        assertFalse(prefs.storedFilesMasked)
        assertEquals("1999-01-01 12:34:56 radar link start Cam #####", LinkEventJournal({ root }).readTail().last())

        obstacle.deleteRecursively()
        pass()
        assertEquals(maskedLines, journal.readText())
        assertTrue(prefs.storedFilesMasked)
    }

    @Test
    fun aDeathMidPassLeavesNothingBehind() {
        val leftReport = File(crashes, ".masking-${CrashLogger.FILE_PREFIX}gone${CrashLogger.FILE_SUFFIX}").apply { writeText("half") }
        val leftLines = File(journalDir, ".masking-${LinkEventJournal.FILE_NAME}").apply { writeText("half") }
        pass()
        assertFalse(leftReport.exists())
        assertFalse(leftLines.exists())
        assertTrue(prefs.storedFilesMasked)
    }

    @Test
    fun aCleanFileIsNotRewritten() {
        // The obstacle fails any write, so the pass finishes only if none is tried.
        val clean = report("clean", "exception: java.lang.IllegalStateException: boom\n")
        blockSiblingOf(clean)
        journal.writeText("1999-01-01 12:34:56 radar handshake complete\n")
        blockSiblingOf(journal)
        pass()
        assertTrue(prefs.storedFilesMasked)
    }

    @Test
    fun nothingOnDiskIsASuccess() {
        crashes.deleteRecursively()
        journalDir.deleteRecursively()
        pass()
        assertTrue(prefs.storedFilesMasked)
    }

    @Test
    fun noStorageOrAnUnreadableFolderIsTriedAgain() {
        LogRedaction.maskStoredFilesOnce(prefs, null)
        assertFalse(prefs.storedFilesMasked)

        crashes.deleteRecursively()
        crashes.writeText("not a folder")
        pass()
        assertFalse(prefs.storedFilesMasked)
    }
}
