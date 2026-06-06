// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Locks the safety-critical wiring contract of [CrashLogger.install]: the
 * installed handler must ALWAYS delegate to the previous default handler so the
 * platform still terminates the process - crash logging must never mask or
 * swallow the original crash - and it must write a report en route. The pure
 * formatting/prune logic is covered by [CrashLoggerTest]; this covers the
 * handler wiring, which is the part that actually keeps the process behaving on
 * a real crash.
 */
@RunWith(RobolectricTestRunner::class)
class CrashLoggerChainingTest {

    @Test fun installedHandlerChainsToPreviousAndWritesReport() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var chained: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, t -> chained = t }
            CrashLogger.install(ctx) { 123_000L }

            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            val boom = RuntimeException("kaboom")
            handler.uncaughtException(Thread.currentThread(), boom)

            assertSame("previous handler must always be invoked", boom, chained)

            val dir = File(ctx.getExternalFilesDir(null), CrashLogger.CRASH_DIR)
            val reports = dir.listFiles { f -> f.name.startsWith(CrashLogger.FILE_PREFIX) }
            assertTrue("a crash report was written", (reports?.size ?: 0) >= 1)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test fun installedHandlerChainsEvenWhenTheWriteFails() {
        // The safety contract: even if writing the report throws (storage gone),
        // the previous handler must still be invoked so the process dies normally.
        // A regression that moved the chaining call inside the try would fail here.
        val real = ApplicationProvider.getApplicationContext<Context>()
        val failing = object : ContextWrapper(real) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File? = throw java.io.IOException("storage unavailable")
        }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var chained: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, t -> chained = t }
            CrashLogger.install(failing) { 1L }
            val boom = RuntimeException("kaboom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertSame("previous handler must run even when the write fails", boom, chained)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
