// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide last-resort crash recorder.
 *
 * The app is a persistent rear-awareness overlay used while riding, and the
 * audio cues are the primary safety channel. A crash silently removes that
 * awareness - no beep, no overlay, just gone - and the opt-in capture log does
 * not record it. This installs an uncaught-exception handler that writes a
 * minimal crash summary to app-private external storage (no remote telemetry)
 * before the process dies, then delegates to the platform's previous handler so
 * the normal crash dialog / process termination still happen.
 *
 * Installed once from [BikeRadarApp.onCreate] so it covers every thread in the
 * process - Compose UI, Binder-thread GATT callbacks, and the service's
 * coroutines - not just the foreground service.
 *
 * Reports land in `crashes/bike-radar-crash-YYYYMMDD-HHMMSS.log` next to the
 * capture logs and are pullable with `adb pull`. The newest [MAX_CRASH_LOGS]
 * are kept. The handler must never throw or block: it is the last code to run
 * before the process dies, so all I/O is wrapped and failures are swallowed -
 * crash logging must not mask the original crash.
 */
object CrashLogger {
    const val CRASH_DIR = "crashes"
    const val FILE_PREFIX = "bike-radar-crash-"
    const val FILE_SUFFIX = ".log"
    const val MAX_CRASH_LOGS = 20

    private const val TAG = "BikeRadar.Crash"

    /** Install once per process. The platform default handler is process-global,
     *  so chaining a fresh wrapper on every call - e.g. when a test harness
     *  re-instantiates the Application per test - would leak a growing chain of
     *  handlers, each capturing a Context. */
    @Volatile private var installed = false

    /**
     * Install the handler, at most once per process. It chains (does not
     * replace) the existing default handler so the platform still terminates
     * the process and shows its crash UI. [nowMs] is injectable for tests.
     */
    fun install(context: Context, nowMs: () -> Long = { System.currentTimeMillis() }) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val root = appContext.getExternalFilesDir(null)
                if (root != null) {
                    val dir = File(root, CRASH_DIR).apply { mkdirs() }
                    writeReportTo(dir, nowMs(), thread.name, throwable, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                    prune(dir, MAX_CRASH_LOGS)
                }
            } catch (t: Throwable) {
                // Never let crash logging mask or replace the original crash.
                Log.w(TAG, "failed to write crash report", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Test seam: clear the install-once guard so a per-test harness can re-install. */
    @VisibleForTesting
    internal fun resetForTest() {
        installed = false
    }

    /** Write a crash report into [dir]; returns the file, or null on failure. */
    fun writeReportTo(
        dir: File,
        nowMs: Long,
        threadName: String,
        throwable: Throwable,
        versionName: String,
        versionCode: Int,
    ): File? = try {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(nowMs))
        val file = File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        file.writeText(formatReport(nowMs, threadName, throwable, versionName, versionCode))
        file
    } catch (t: Throwable) {
        Log.w(TAG, "writeReportTo failed", t)
        null
    }

    /** Keep the newest [max] crash files in [dir]; delete the rest. */
    fun prune(dir: File, max: Int) {
        val crashes = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return
        crashes.sortedByDescending { it.lastModified() }.drop(max).forEach { it.delete() }
    }

    /**
     * Pure, deterministic crash-report body. Kept separate from the I/O so it
     * is unit-testable without a device.
     */
    fun formatReport(
        nowMs: Long,
        threadName: String,
        throwable: Throwable,
        versionName: String,
        versionCode: Int,
    ): String {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("bike-radar crash report")
            appendLine("time_ms: $nowMs")
            appendLine("version: $versionName ($versionCode)")
            appendLine("thread: $threadName")
            appendLine("exception: ${throwable.javaClass.name}: ${throwable.message ?: ""}")
            appendLine()
            append(trace)
        }
    }
}
