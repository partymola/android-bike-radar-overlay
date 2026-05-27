// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPInputStream

class CaptureLogFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun isCaptureLogAcceptsBothPlainAndGzippedForms() {
        assertTrue(CaptureLogFiles.isCaptureLogName("bike-radar-capture-20260527-013000.log"))
        assertTrue(CaptureLogFiles.isCaptureLogName("bike-radar-capture-20260527-013000.log.gz"))
    }

    @Test
    fun isCaptureLogRejectsUnrelatedFiles() {
        assertFalse(CaptureLogFiles.isCaptureLogName("replay-fixture.txt"))
        assertFalse(CaptureLogFiles.isCaptureLogName("bike-radar-capture-foo.txt"))
        assertFalse(CaptureLogFiles.isCaptureLogName("other-prefix.log"))
        assertFalse(CaptureLogFiles.isCaptureLogName("bike-radar-capture-X.log.bak"))
    }

    @Test
    fun isGzippedDistinguishesArchiveFromActive() {
        assertFalse(CaptureLogFiles.isGzipped(File("bike-radar-capture-1.log")))
        assertTrue(CaptureLogFiles.isGzipped(File("bike-radar-capture-1.log.gz")))
    }

    @Test
    fun gzipAndDeleteCompressesSourceAndRemovesPlainFile() {
        val src = tmp.newFile("bike-radar-capture-20260527-013000.log")
        // Repeated text compresses well - the assertion below relies on a
        // meaningful size reduction so we know the gzip stream actually ran.
        src.writeText("# bike-radar capture\n" + "frame 11 22 33 44 55 66\n".repeat(2000))
        val srcSize = src.length()

        val gz = CaptureLogFiles.gzipAndDelete(src)

        assertNotNull("gzipAndDelete returned null on a healthy source", gz)
        assertFalse("source .log must be deleted on success", src.exists())
        assertTrue("destination .log.gz must exist", gz!!.exists())
        assertTrue(
            "compressed size ${gz.length()} should be << source $srcSize for highly redundant text",
            gz.length() < srcSize / 4,
        )
    }

    @Test
    fun gzipAndDeleteRoundTripsContentByteForByte() {
        val src = tmp.newFile("bike-radar-capture-20260527-013100.log")
        val payload = "# header\n" + (1..500).joinToString("\n") {
            "1700000000${1000 + it} aabb${it.toString(16).padStart(4, '0')}"
        }
        src.writeText(payload)

        val gz = CaptureLogFiles.gzipAndDelete(src)!!
        val recovered = GZIPInputStream(gz.inputStream()).bufferedReader().use { it.readText() }
        assertEquals("gzip round-trip must be lossless", payload, recovered)
    }

    @Test
    fun gzipAndDeleteIsNoOpOnAlreadyGzipped() {
        val gz = tmp.newFile("bike-radar-capture-20260527-013200.log.gz")
        gz.writeBytes(byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x08))
        val out = CaptureLogFiles.gzipAndDelete(gz)
        assertEquals("already-gzipped file must be returned unchanged", gz, out)
        assertTrue("source must not be deleted", gz.exists())
    }

    @Test
    fun gzipAndDeletePreservesSourceOnFailure() {
        // Source file that doesn't exist - gzipAndDelete should fail and
        // not throw. The non-existent path means nothing to preserve, but
        // verify the helper handles it cleanly.
        val ghost = File(tmp.root, "bike-radar-capture-ghost.log")
        val out = CaptureLogFiles.gzipAndDelete(ghost)
        assertNull("missing source must return null", out)
    }
}
