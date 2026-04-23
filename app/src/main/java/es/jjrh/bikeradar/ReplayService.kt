// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Feeds the bundled capture into RadarV2Decoder at original pacing, publishing
 * RadarStates on RadarStateBus so DebugOverlayService renders them as live data.
 *
 * One-shot: reads assets/replay-highlight.log, plays once, then stops itself.
 * Foreground type shortService (well under the 3-minute limit).
 */
class ReplayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification("Replay starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIF_ID, buildNotification("Replay starting…"))
        }
        scope.launch { runReplay() }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun runReplay() {
        val frames = try {
            assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
                lines.mapNotNull(::parseLine).toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to read $ASSET_NAME", e)
            stopSelf(); return
        }
        if (frames.isEmpty()) {
            Log.w(TAG, "no frames in $ASSET_NAME"); stopSelf(); return
        }

        val decoder = RadarV2Decoder()
        val startTs = frames[0].tsMs
        val wallStart = SystemClock.elapsedRealtime()
        var lastNotifUpdateMs = 0L

        for ((i, f) in frames.withIndex()) {
            val targetOffsetMs = f.tsMs - startTs
            val nowOffsetMs = SystemClock.elapsedRealtime() - wallStart
            val sleepMs = targetOffsetMs - nowOffsetMs
            if (sleepMs > 0) delay(sleepMs)

            val state = decoder.feed(f.payload)
            if (state != null) {
                RadarStateBus.publish(state.copy(scenarioTimeMs = targetOffsetMs))
            }

            val elapsed = SystemClock.elapsedRealtime() - wallStart
            if (elapsed - lastNotifUpdateMs > 1000) {
                updateNotif("Replaying frame ${i + 1}/${frames.size}")
                lastNotifUpdateMs = elapsed
            }
        }

        delay(1500)
        RadarStateBus.clear()
        stopService(android.content.Intent(this, DebugOverlayService::class.java))
        stopSelf()
    }

    private data class Frame(val tsMs: Long, val payload: ByteArray)

    private fun parseLine(raw: String): Frame? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3 || parts[1] != "3204") return null
        val ts = parts[0].toLongOrNull() ?: return null
        val hex = parts[2]
        if (hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return Frame(ts, bytes)
    }

    private fun updateNotif(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Bike Radar replay")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Replay", NotificationManager.IMPORTANCE_MIN)
        )
    }

    companion object {
        private const val TAG = "BikeRadar.Replay"
        const val CHANNEL_ID = "bike_radar_replay_min"
        const val NOTIF_ID = 4242
        private const val ASSET_NAME = "replay-highlight.log"
        @Volatile var isRunning = false
    }
}
