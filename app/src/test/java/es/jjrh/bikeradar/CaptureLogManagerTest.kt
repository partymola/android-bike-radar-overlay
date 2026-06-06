// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

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
}
