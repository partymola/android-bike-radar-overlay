// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashLoggerTest {

    @Test fun reportContainsKeyFields() {
        val report = CrashLogger.formatReport(
            nowMs = 1_700_000_000_000L,
            threadName = "BikeRadar-IO",
            throwable = IllegalStateException("boom"),
            versionName = "0.9.0-alpha",
            versionCode = 14,
        )
        assertTrue("version", report.contains("0.9.0-alpha (14)"))
        assertTrue("thread", report.contains("BikeRadar-IO"))
        assertTrue("exception type", report.contains("java.lang.IllegalStateException"))
        assertTrue("message", report.contains("boom"))
        assertTrue("timestamp", report.contains("1700000000000"))
        assertTrue("stack trace frame", report.contains("CrashLoggerTest"))
    }

    @Test fun reportToleratesNullMessage() {
        val report = CrashLogger.formatReport(1L, "main", NullPointerException(), "v", 1)
        assertTrue(report.contains("java.lang.NullPointerException"))
    }

    @Test fun writeReportToWritesAReadableFile() {
        val dir = Files.createTempDirectory("crashwrite").toFile()
        val file = CrashLogger.writeReportTo(dir, 1L, "main", RuntimeException("x"), "v", 1)
        assertNotNull(file)
        assertTrue(file!!.exists())
        assertTrue(file.name.startsWith(CrashLogger.FILE_PREFIX))
        assertTrue(file.name.endsWith(CrashLogger.FILE_SUFFIX))
        assertTrue(file.readText().contains("RuntimeException"))
    }

    @Test fun pruneKeepsNewestMaxAndLeavesOtherFiles() {
        val dir = Files.createTempDirectory("crashprune").toFile()
        val files = (1..25).map { i ->
            File(dir, "${CrashLogger.FILE_PREFIX}f$i${CrashLogger.FILE_SUFFIX}").apply {
                writeText("c$i")
                setLastModified(1_000_000L + i * 1000L)
            }
        }
        val unrelated = File(dir, "notes.txt").apply { writeText("keep") }

        CrashLogger.prune(dir, 20)

        val remaining = dir.listFiles { f -> f.name.startsWith(CrashLogger.FILE_PREFIX) }!!.toList()
        assertEquals(20, remaining.size)
        assertFalse("oldest deleted", files[0].exists()) // f1
        assertTrue("newest kept", files[24].exists()) // f25
        assertTrue("non-crash file untouched", unrelated.exists())
    }
}
