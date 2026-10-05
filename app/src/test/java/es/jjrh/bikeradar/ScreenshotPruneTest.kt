// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Debug screenshots are capped on the phone rather than piling up. */
@RunWith(RobolectricTestRunner::class)
class ScreenshotPruneTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun names(dir: File) = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun writingPastTheCapKeepsOnlyTheNewestThirty() {
        val dir = tmp.newFolder("screenshots")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val start = 1_790_000_000_000L
        val written = (0 until 32).map { ScreenshotCaptureService.writeFrame(dir, bitmap, start + it * 60_000L).name }
        assertEquals(30, names(dir).size)
        assertEquals(written.drop(2).sorted(), names(dir))
    }

    @Test
    fun pruningLeavesOtherFilesAlone() {
        val dir = tmp.newFolder("screenshots")
        File(dir, "notes.txt").writeText("x")
        repeat(3) { File(dir, "bike-radar-overlay-00$it.png").writeText("x") }
        ScreenshotCaptureService.prune(dir, 1)
        assertEquals(listOf("bike-radar-overlay-002.png", "notes.txt"), names(dir))
    }

    @Test
    fun theOldestByWriteTimeGoesWhateverItsNameSays() {
        // After the clocks go back, a new frame's local-time name sorts below
        // the frames saved in the hour before.
        val dir = tmp.newFolder("screenshots")
        val earlier = File(dir, "bike-radar-overlay-002.png").apply { writeText("x") }
        val later = File(dir, "bike-radar-overlay-001.png").apply { writeText("x") }
        assertTrue(earlier.setLastModified(1_000_000L))
        assertTrue(later.setLastModified(2_000_000L))
        ScreenshotCaptureService.prune(dir, 1)
        assertEquals(listOf(later.name), names(dir))
    }

    @Test
    fun appStartTrimsABacklogWithoutCapturing() {
        val app = ApplicationProvider.getApplicationContext<Context>() as BikeRadarApp
        val dir = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        repeat(32) { i ->
            File(dir, "bike-radar-overlay-%03d.png".format(i)).apply {
                writeText("x")
                assertTrue(setLastModified(1_000_000L + i * 60_000L))
            }
        }
        app.onCreate()
        assertEquals((2 until 32).map { "bike-radar-overlay-%03d.png".format(it) }, names(dir))
    }
}
