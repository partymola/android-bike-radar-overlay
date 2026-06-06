// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Robolectric (for android.util.Log used by the manager + CaptureLogFiles).
 * Covers the open/close/gzip lifecycle and the opt-in guard - the part that was
 * untested before the extraction (prune + file-shape are covered by
 * CaptureLogFilesTest and BikeRadarServiceSmokeTest).
 */
@RunWith(RobolectricTestRunner::class)
class CaptureLogManagerTest {

    @Test fun openIsANoOpWhenLoggingDisabled() {
        val root = Files.createTempDirectory("caplog").toFile()
        var name: String? = "sentinel"
        val m = CaptureLogManager(
            externalFilesDir = { root },
            captureLoggingEnabled = { false },
            onActiveName = { name = it },
        )
        m.open()
        m.clog("must-not-be-written")
        val dir = File(root, CaptureLogManager.CAPTURE_DIR)
        assertFalse("no log file created when disabled", dir.exists() && (dir.listFiles()?.isNotEmpty() ?: false))
        assertEquals("active name untouched when disabled", "sentinel", name)
    }

    @Test fun openWritesAFileAndCloseGzipsIt() {
        val root = Files.createTempDirectory("caplog").toFile()
        var name: String? = null
        val m = CaptureLogManager(
            externalFilesDir = { root },
            captureLoggingEnabled = { true },
            onActiveName = { name = it },
        )
        val dir = File(root, CaptureLogManager.CAPTURE_DIR)

        m.open()
        assertNotNull("active name set on open", name)
        assertEquals("one active .log while open", 1, dir.listFiles { f -> f.name.endsWith(".log") }!!.size)

        m.clog("a-line")
        m.close()

        assertNull("active name cleared on close", name)
        assertEquals("plain .log gzipped away on close", 0, dir.listFiles { f -> f.name.endsWith(".log") }!!.size)
        assertEquals("one gzipped archive after close", 1, dir.listFiles { f -> f.name.endsWith(".log.gz") }!!.size)
    }

    @Test fun openIsANoOpWhenExternalDirIsNull() {
        // No external storage (e.g. ejected/unavailable): open must bail before
        // touching the filesystem, leaving no active file.
        var name: String? = "sentinel"
        val m = CaptureLogManager(
            externalFilesDir = { null },
            captureLoggingEnabled = { true },
            onActiveName = { name = it },
        )
        m.open()
        m.clog("must-not-write")
        assertEquals("no active name when external dir is null", "sentinel", name)
    }

    @Test fun closeWithoutAnOpenFileIsClean() {
        // close() before any open(): closedName is null, so the gzip step is
        // skipped and nothing is created or thrown.
        val root = Files.createTempDirectory("caplog").toFile()
        val m = CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true })
        m.close()
        val dir = File(root, CaptureLogManager.CAPTURE_DIR)
        assertFalse("no file created by a bare close", dir.exists() && (dir.listFiles()?.isNotEmpty() ?: false))
    }

    @Test fun pruneGzipsAPlainStragglerLeftByAPriorSession() {
        // A crash before close (or a pre-gzip install) leaves a plain .log above
        // the useful-size threshold. prune must backfill-gzip it, not drop it.
        val root = Files.createTempDirectory("caplog").toFile()
        val dir = File(root, CaptureLogManager.CAPTURE_DIR).apply { mkdirs() }
        val body = "x".repeat(CaptureLogManager.MIN_USEFUL_LOG_BYTES.toInt() + 100)
        File(dir, "bike-radar-capture-20260101-000000.log").writeText(body)

        CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true }).prune()

        assertEquals("plain log gzipped by backfill", 0, dir.listFiles { f -> f.name.endsWith(".log") }!!.size)
        assertEquals("one archive remains", 1, dir.listFiles { f -> f.name.endsWith(".log.gz") }!!.size)
    }

    @Test fun pruneDropsAHeaderOnlyLogButKeepsARealOne() {
        // The size gate: a header-only stub (under MIN_USEFUL_LOG_BYTES) is a
        // session where the radar never connected - dropped; a real session is
        // kept (and backfill-gzipped).
        val root = Files.createTempDirectory("caplog").toFile()
        val dir = File(root, CaptureLogManager.CAPTURE_DIR).apply { mkdirs() }
        File(dir, "bike-radar-capture-20260101-000001.log").writeText("# header only")
        val real = "x".repeat(CaptureLogManager.MIN_USEFUL_LOG_BYTES.toInt() + 100)
        File(dir, "bike-radar-capture-20260101-000002.log").writeText(real)

        CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true }).prune()

        assertEquals(
            "header-only dropped, the real session kept",
            1,
            dir.listFiles { f -> CaptureLogFiles.isCaptureLog(f) }!!.size,
        )
    }

    @Test fun clogPacketWritesATaggedHexLine() {
        val root = Files.createTempDirectory("caplog").toFile()
        val dir = File(root, CaptureLogManager.CAPTURE_DIR)
        val m = CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true })
        m.open()
        // UUID segment chars 4..7 are the tag; bytes render as lowercase hex.
        m.clogPacket(UUID.fromString("6a4e3204-0000-1000-8000-00805f9b34fb"), byteArrayOf(0x01, 0xAB.toByte()))
        m.close()
        val gz = dir.listFiles { f -> f.name.endsWith(".log.gz") }!!.single()
        val text = GZIPInputStream(gz.inputStream()).bufferedReader().use { it.readText() }
        assertTrue("packet line carries the char tag", text.contains(" 3204 "))
        assertTrue("packet line carries the lowercase hex payload", text.contains(" 3204 01ab"))
    }

    @Test fun openSurvivesAFileWriterFailure() {
        // externalFilesDir points at a regular FILE, so the captures subdir can't
        // be created and FileWriter throws - open must swallow it and stay inert.
        val notADir = Files.createTempFile("caplog", ".tmp").toFile()
        var name: String? = "sentinel"
        val m = CaptureLogManager(
            externalFilesDir = { notADir },
            captureLoggingEnabled = { true },
            onActiveName = { name = it },
        )
        m.open()
        m.clog("must-not-throw")
        assertEquals("no active file when open failed", "sentinel", name)
    }

    @Test fun pruneIsANoOpWhenExternalDirIsNull() {
        // No external storage at prune time: bail before listing.
        CaptureLogManager(externalFilesDir = { null }, captureLoggingEnabled = { true }).prune()
    }

    @Test fun closeToleratesAMissingActiveFile() {
        // The active .log vanished before close ran (manual delete / external
        // cleanup): close must skip the gzip and not throw.
        val root = Files.createTempDirectory("caplog").toFile()
        val dir = File(root, CaptureLogManager.CAPTURE_DIR)
        val m = CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true })
        m.open()
        dir.listFiles { f -> f.name.endsWith(".log") }!!.forEach { it.delete() }
        m.close()
        assertEquals("no archive produced from a vanished file", 0, dir.listFiles { f -> f.name.endsWith(".log.gz") }!!.size)
    }

    @Test fun pruneCapsTheCountKeepingTheNewestArchives() {
        // Already-gzipped archives over the cap: prune keeps the newest
        // MAX_CAPTURE_LOGS and drops the oldest, without re-gzipping.
        val root = Files.createTempDirectory("caplog").toFile()
        val dir = File(root, CaptureLogManager.CAPTURE_DIR).apply { mkdirs() }
        val over = CaptureLogManager.MAX_CAPTURE_LOGS + 5
        repeat(over) { i ->
            File(dir, "bike-radar-capture-2026010%02d.log.gz".format(i)).apply {
                writeText("gz")
                setLastModified(1_000_000L + i * 1000L) // deterministic age order
            }
        }
        CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true }).prune()
        assertEquals(
            "count capped at MAX_CAPTURE_LOGS",
            CaptureLogManager.MAX_CAPTURE_LOGS,
            dir.listFiles { f -> CaptureLogFiles.isCaptureLog(f) }!!.size,
        )
    }
}
