// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Overlay service for debug paths (Replay, Synthetic scenario) only.
 * The live radar path draws the overlay inline inside BikeRadarService.
 */
class DebugOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var overlayView: RadarOverlayView? = null
    private var collectJob: Job? = null
    private var beeper: AlertBeeper? = null

    private val alerts = AlertDecider()

    private var volumePct = AlertBeeper.DEFAULT_VOLUME_PCT
    private var maxDistanceM = DEFAULT_MAX_DISTANCE_M
    private var visualMaxM = RadarOverlayView.DEFAULT_VISUAL_MAX_M

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        )

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no SYSTEM_ALERT_WINDOW; stopping")
            stopSelf()
            return
        }

        val prefs = Prefs(this)
        volumePct = prefs.alertVolume
        maxDistanceM = prefs.alertMaxDistanceM.coerceIn(MIN_DIST_M, MAX_DIST_M)
        visualMaxM = prefs.visualMaxDistanceM
            .coerceIn(RadarOverlayView.MIN_VISUAL_MAX_M, RadarOverlayView.MAX_VISUAL_MAX_M)

        beeper = try {
            AlertBeeper().also { it.setVolumePct(volumePct) }
        } catch (e: Exception) {
            Log.w(TAG, "AlertBeeper init failed: ${e.message}"); null
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.wm = wm
        val view = RadarOverlayView(this).apply {
            setVisualMaxM(visualMaxM)
            setAlertMaxM(maxDistanceM)
        }
        overlayView = view
        wm.addView(view, buildParams(wm))

        scope.launch {
            prefs.flow.collect { snap ->
                beeper?.setVolumePct(snap.alertVolume)
                maxDistanceM = snap.alertMaxDistanceM.coerceIn(MIN_DIST_M, MAX_DIST_M)
                visualMaxM = snap.visualMaxDistanceM
                    .coerceIn(RadarOverlayView.MIN_VISUAL_MAX_M, RadarOverlayView.MAX_VISUAL_MAX_M)
                view.setVisualMaxM(visualMaxM)
                view.setAlertMaxM(maxDistanceM)
            }
        }

        collectJob = scope.launch {
            RadarStateBus.state.collect { state ->
                view.setState(state)
                handleAlerts(state)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        collectJob?.cancel()
        try { overlayView?.let { wm?.removeView(it) } } catch (_: Throwable) {}
        overlayView = null
        beeper?.release()
        beeper = null
        scope.cancel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = overlayView ?: return
        val w = wm ?: return
        try { w.updateViewLayout(v, buildParams(w)) } catch (_: Throwable) {}
    }

    private fun handleAlerts(state: RadarState) {
        val b = beeper ?: return
        when (val ev = alerts.decide(state.vehicles, maxDistanceM, System.currentTimeMillis())) {
            is AlertDecider.Event.Beep -> b.play(ev.count)
            AlertDecider.Event.Clear   -> b.playClear()
            AlertDecider.Event.None    -> {}
        }
    }

    private fun buildParams(wm: WindowManager): WindowManager.LayoutParams {
        val screenH = wm.currentWindowMetrics.bounds.height()
        return WindowManager.LayoutParams(
            dp(130f).toInt(), screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 0 }
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radar debug overlay", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bike Radar")
            .setContentText("Debug overlay")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    companion object {
        private const val TAG = "BikeRadar"
        const val CHANNEL_ID = "bike_radar_debug_overlay"
        const val NOTIF_ID = 3
        const val MIN_DIST_M = 10
        const val MAX_DIST_M = 40
        const val DEFAULT_MAX_DISTANCE_M = 20
    }
}
