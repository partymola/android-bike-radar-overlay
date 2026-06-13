// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Robolectric (for android.util.Log). Pins the journal contract the
 * Debug screen and diagnostic bundle read against: stamped append,
 * newest-first tail, cross-instance visibility (service writes, screen
 * reads), the size-cap trim, and the no-storage no-op.
 */
@RunWith(RobolectricTestRunner::class)
class LinkEventJournalTest {

    @Test fun appendThenTailReturnsNewestFirstWithStamp() {
        val root = Files.createTempDirectory("linkjournal").toFile()
        var t = 1_000_000_000_000L
        val j = LinkEventJournal({ root }, nowMs = { t })
        j.log("radar link start TestRadar")
        t += 1_000
        j.log("radar handshake complete")
        val tail = j.readTail()
        assertEquals(2, tail.size)
        assertTrue("newest first", tail[0].endsWith("radar handshake complete"))
        assertTrue("oldest last", tail[1].endsWith("radar link start TestRadar"))
        // Stamp prefix: "yyyy-MM-dd HH:mm:ss " before the event text.
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} radar link start TestRadar$""").matches(tail[1]))
    }

    @Test fun tailIsVisibleAcrossInstances() {
        val root = Files.createTempDirectory("linkjournal").toFile()
        LinkEventJournal({ root }).log("service started")
        val reader = LinkEventJournal({ root })
        assertEquals(1, reader.readTail().size)
    }

    @Test fun tailHonoursMaxLines() {
        val root = Files.createTempDirectory("linkjournal").toFile()
        val j = LinkEventJournal({ root })
        repeat(10) { j.log("event $it") }
        val tail = j.readTail(maxLines = 3)
        assertEquals(3, tail.size)
        assertTrue(tail[0].endsWith("event 9"))
        assertTrue(tail[2].endsWith("event 7"))
    }

    @Test fun oversizedFileIsTrimmedToKeepLines() {
        val root = Files.createTempDirectory("linkjournal").toFile()
        val j = LinkEventJournal({ root })
        val file = File(File(root, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME)
        file.parentFile.mkdirs()
        // Pre-fill past the byte cap, then one append triggers the trim.
        val filler = "x".repeat(200)
        val lines = (LinkEventJournal.MAX_BYTES / filler.length + 10).toInt()
        file.writeText(buildString { repeat(lines) { appendLine("old $it $filler") } })
        j.log("fresh event")
        val kept = file.readLines().filter { it.isNotBlank() }
        assertEquals(LinkEventJournal.KEEP_LINES, kept.size)
        assertTrue("newest line survives the trim", kept.last().endsWith("fresh event"))
    }

    @Test fun noStorageIsANoOp() {
        val j = LinkEventJournal({ null })
        j.log("must not throw")
        assertTrue(j.readTail().isEmpty())
    }

    @Test fun emptyDirReadsEmpty() {
        val root = Files.createTempDirectory("linkjournal").toFile()
        assertTrue(LinkEventJournal({ root }).readTail().isEmpty())
    }
}
