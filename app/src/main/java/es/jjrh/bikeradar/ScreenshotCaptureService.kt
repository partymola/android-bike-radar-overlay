// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Periodic screenshot capture, gated on a live radar link.
 *
 * Captures the device screen via MediaProjection on a fixed interval and
 * writes a PNG only when [RadarStateBus] is publishing fresh V2 frames
 * (i.e. the overlay is being drawn on top of whatever app the rider is
 * using). Frames acquired while the radar is disconnected are dropped, so
 * leaving the toggle on between rides does not flood the files dir.
 *
 * MediaProjection consent must be obtained from an Activity before this
 * service starts; the result intent is forwarded via [EXTRA_RESULT_CODE]
 * and [EXTRA_RESULT_DATA].
 */
class ScreenshotCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var densityDpi: Int = 0
    private var foregroundStarted: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Deliberately no startForeground here. Calling it with
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION on Android 14+ requires
        // a valid consent token to already be in flight; we don't have one
        // until onStartCommand validates the result extras. Promotion to
        // foreground happens in [beginProjection].
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = IntentCompat.getParcelableExtra(
                    intent, EXTRA_RESULT_DATA, Intent::class.java,
                )
                if (resultCode == 0 || resultData == null) {
                    Log.w(TAG, "missing projection result; stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (projection != null) {
                    Log.i(TAG, "already running; ignoring re-start")
                    return START_NOT_STICKY
                }
                beginProjection(resultCode, resultData)
            }
            else -> {
                Log.w(TAG, "unknown action ${intent?.action}; stopping")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun beginProjection(resultCode: Int, resultData: Intent) {
        // Promote to foreground BEFORE getMediaProjection. On Android 14+
        // the system rejects getMediaProjection calls from a service that
        // isn't already foregrounded as type=mediaProjection.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        foregroundStarted = true

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            Log.w(TAG, "getMediaProjection failed: $t")
            stopSelf()
            return
        }
        if (mp == null) {
            Log.w(TAG, "getMediaProjection returned null")
            stopSelf()
            return
        }
        projection = mp

        // Required on API 34+: a callback must be registered before the
        // virtual display is created, otherwise the projection is killed
        // immediately with a SecurityException.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system / user")
                stopSelf()
            }
        }, handler)

        if (!setupCaptureRig(mp)) {
            stopSelf()
            return
        }

        isRunning = true
        captureJob = scope.launch { captureLoop() }
    }

    private fun setupCaptureRig(mp: MediaProjection): Boolean {
        val metrics = resources.displayMetrics
        widthPx = metrics.widthPixels
        heightPx = metrics.heightPixels
        densityDpi = metrics.densityDpi
        if (widthPx <= 0 || heightPx <= 0) {
            Log.w(TAG, "invalid display metrics ${widthPx}x$heightPx")
            return false
        }

        val reader = ImageReader.newInstance(
            widthPx, heightPx, PixelFormat.RGBA_8888, MAX_IMAGES,
        )
        val vd = try {
            mp.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                widthPx, heightPx, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "createVirtualDisplay failed: $t")
            try { reader.close() } catch (_: Throwable) {}
            return false
        }
        if (vd == null) {
            Log.w(TAG, "createVirtualDisplay returned null")
            try { reader.close() } catch (_: Throwable) {}
            return false
        }
        imageReader = reader
        virtualDisplay = vd
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes the display dimensions. On Android 14+ a
        // MediaProjection token allows exactly one createVirtualDisplay
        // call - re-creating throws SecurityException and kills the
        // session. Resize the existing VirtualDisplay in place and
        // hot-swap its surface to a fresh ImageReader at the new size
        // instead. The catch in [captureLoop] absorbs the
        // IllegalStateException from any in-flight acquire that races
        // with the surface swap; the next tick reads the new reader
        // via @Volatile.
        val vd = virtualDisplay ?: return
        val metrics = resources.displayMetrics
        val newW = metrics.widthPixels
        val newH = metrics.heightPixels
        if (newW <= 0 || newH <= 0) {
            Log.w(TAG, "invalid display metrics on rotation ${newW}x$newH")
            return
        }
        if (newW == widthPx && newH == heightPx) return

        val newReader = ImageReader.newInstance(
            newW, newH, PixelFormat.RGBA_8888, MAX_IMAGES,
        )
        val oldReader = imageReader
        try {
            vd.resize(newW, newH, densityDpi)
            vd.surface = newReader.surface
        } catch (t: Throwable) {
            Log.w(TAG, "resize after rotation failed: $t")
            try { newReader.close() } catch (_: Throwable) {}
            return
        }
        widthPx = newW
        heightPx = newH
        imageReader = newReader
        try { oldReader?.close() } catch (_: Throwable) {}
    }

    private suspend fun captureLoop() {
        // Initial settling delay: the first frame is sometimes blank
        // because the VirtualDisplay has not finished compositing.
        delay(STARTUP_SETTLE_MS)
        val outDir = getExternalFilesDir(null)?.let { File(it, SCREENSHOT_DIR).apply { mkdirs() } }
        if (outDir == null) {
            Log.w(TAG, "external files dir unavailable; stopping")
            handler.post { stopSelf() }
            return
        }
        Log.i(TAG, "captureLoop entered; interval=${CAPTURE_INTERVAL_MS}ms")
        var ticks = 0
        while (scope.isActive) {
            val state = RadarStateBus.state.value
            val ageMs = System.currentTimeMillis() - state.timestamp
            val overlayLive = state.source != DataSource.NONE &&
                state.timestamp > 0L &&
                ageMs in 0..OVERLAY_FRESH_WINDOW_MS
            if (overlayLive) {
                try {
                    captureOnce(outDir)
                } catch (t: Throwable) {
                    Log.w(TAG, "capture failed: $t")
                }
            } else {
                Log.i(TAG, "tick ${ticks}: overlay not live (source=${state.source} ageMs=$ageMs); skipping frame")
            }
            ticks++
            delay(CAPTURE_INTERVAL_MS)
        }
        Log.i(TAG, "captureLoop exited after $ticks ticks; scope.isActive=${scope.isActive}")
    }

    private suspend fun captureOnce(outDir: File) {
        val reader = imageReader
        if (reader == null) {
            Log.w(TAG, "captureOnce: imageReader null, skipping")
            return
        }
        val image = reader.acquireLatestImage()
        if (image == null) {
            Log.w(TAG, "captureOnce: acquireLatestImage returned null")
            return
        }
        var padded: Bitmap? = null
        val bitmap: Bitmap? = try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            if (pixelStride != 4) {
                Log.w(TAG, "unexpected pixelStride=$pixelStride; skipping frame")
                null
            } else {
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * widthPx
                padded = Bitmap.createBitmap(
                    widthPx + rowPadding / pixelStride,
                    heightPx,
                    Bitmap.Config.ARGB_8888,
                )
                padded.copyPixelsFromBuffer(buffer)
                if (rowPadding == 0) padded
                else Bitmap.createBitmap(padded, 0, 0, widthPx, heightPx).also {
                    padded.recycle()
                    padded = null
                }
            }
        } catch (t: Throwable) {
            padded?.recycle()
            throw t
        } finally {
            image.close()
        }
        if (bitmap == null) return

        withContext(Dispatchers.IO) {
            val name = "bike-radar-overlay-${TIMESTAMP_FMT.get()!!.format(Date())}.png"
            val file = File(outDir, name)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "wrote ${file.name} (${file.length() / 1024} KB)")
        }
        bitmap.recycle()
    }

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "onDestroy: tearing down projection rig")
        super.onDestroy()
        // Stop the projection first so the producer surface drains and any
        // in-flight acquireLatestImage() returns promptly. Then cancel the
        // capture job, then release the reader. This ordering avoids the
        // race where ImageReader is closed mid-acquire.
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        captureJob?.cancel()
        scope.cancel()
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screenshot capture", NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenshotCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Bike Radar")
            .setContentText("Capturing screenshots while overlay is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    companion object {
        private const val TAG = "BikeRadar.Screenshot"
        const val CHANNEL_ID = "bike_radar_screenshot"
        const val NOTIF_ID = 4244
        const val ACTION_START = "es.jjrh.bikeradar.SCREENSHOT_START"
        const val ACTION_STOP = "es.jjrh.bikeradar.SCREENSHOT_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val SCREENSHOT_DIR = "screenshots"
        private const val VIRTUAL_DISPLAY_NAME = "BikeRadarCapture"
        // 2 buffers is intentional: with a 60 s capture interval the
        // ImageReader will silently drop intermediate frames produced by
        // the compositor. acquireLatestImage() always returns the newest.
        private const val MAX_IMAGES = 2
        private const val CAPTURE_INTERVAL_MS = 60_000L
        private const val STARTUP_SETTLE_MS = 2_000L
        // The radar publishes a state on every device-status frame
        // (~every 250 ms) so 5 s is comfortably wider than any expected
        // gap. Beyond that we treat the link as down and drop the frame.
        private const val OVERLAY_FRESH_WINDOW_MS = 5_000L
        private val TIMESTAMP_FMT = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
        }
        @Volatile var isRunning: Boolean = false
    }
}
