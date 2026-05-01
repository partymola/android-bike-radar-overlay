// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays a scripted sequence of synthetic RadarStates for validating
 * DebugOverlayService's alert rules against known inputs. Publishes at 10 Hz
 * on RadarStateBus so the overlay draws and AlertBeeper fires as if
 * the bike was on the road.
 *
 * Timeline (10 Hz sampling, total 60 s):
 *  - Car 1 [t=2..15.5]  single overtake from d=80 at v=6. Urg 1->2->3 escalation.
 *  - Car 2 [t=10..25]   first queued pair, d=75 at v=5.
 *  - Car 3 [t=10..28]   second queued, d=90 at v=5. Closest-threat tracking.
 *  - Car 4 [t=22..39]   fast approach (v=10), brake/match (v=0, d=5), then overtake.
 *  - Car 5 TRUCK [t=36..42]  v=14 m/s (50.4 km/h), triggers red danger border.
 *  - Dense traffic [t=42..54] BIKE + 2 cars with lateral positions.
 *  - Phantom blip [t=56.0..56.05] single frame; AlertDecider sustain=2 must suppress it.
 *  - Silence [t=56.1..60] all-clear window.
 *
 * Dashcam status sequence (with the default dashcamOffIndicator on):
 *  - t=0..10       Searching  (cold-start grace; no battery advert yet)
 *  - t=10..20      Missing    (amber, never-seen this session)
 *  - t=20          simulated dashcam advert pushed to BatteryStateBus
 *  - t=20..50      Ok         (within 30s freshness window)
 *  - t=50..60      Dropped    (red, seen-then-lost)
 *
 * Foreground type: shortService (well under the 3-minute limit).
 */
class SyntheticScenarioService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        scope.launch { run() }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun run() {
        val prefs = es.jjrh.bikeradar.data.Prefs(this)
        // Publish the synthetic advert under the slug of the user-selected
        // dashcam so the MAC-based matching in the overlay composer fires.
        // Falls back to a well-known slug + temporarily pointing the pref at
        // a synthetic MAC if the user hasn't selected one.
        val dashcamSlug = prefs.dashcamMac?.let { BikeRadarService.macToSlug[it] } ?: "vue_synth"
        val savedMac = prefs.dashcamMac
        val savedName = prefs.dashcamDisplayName
        val savedWarn = prefs.dashcamWarnWhenOff
        val usingSyntheticPref = savedMac == null
        if (usingSyntheticPref) {
            BikeRadarService.macToSlug[SYNTHETIC_MAC] = dashcamSlug
            prefs.dashcamMac = SYNTHETIC_MAC
            prefs.dashcamDisplayName = "Vue Synthetic"
            prefs.dashcamWarnWhenOff = true
        }

        val start = SystemClock.elapsedRealtime()
        var dashcamPushed = false
        try {
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - start
                if (elapsed >= TOTAL_MS) break
                val vehicles = scriptAt(elapsed)
                val bikeMs = bikeSpeedAt(elapsed)
                RadarStateBus.publish(
                    RadarState(
                        vehicles = vehicles,
                        timestamp = System.currentTimeMillis(),
                        source = DataSource.V2,
                        scenarioTimeMs = elapsed,
                        bikeSpeedMs = bikeMs,
                    )
                )
                if (!dashcamPushed && elapsed >= DASHCAM_PUSH_MS) {
                    BatteryStateBus.update(
                        BatteryEntry(
                            slug = dashcamSlug,
                            name = prefs.dashcamDisplayName ?: "Vue Synthetic",
                            pct = 80,
                        )
                    )
                    dashcamPushed = true
                }
                delay(100)
            }
            delay(1000)
        } finally {
            if (usingSyntheticPref) {
                prefs.dashcamMac = savedMac
                prefs.dashcamDisplayName = savedName
                prefs.dashcamWarnWhenOff = savedWarn
                BikeRadarService.macToSlug.remove(SYNTHETIC_MAC)
            }
            RadarStateBus.clear()
            stopService(android.content.Intent(this, DebugOverlayService::class.java))
            stopSelf()
        }
    }

    private fun scriptAt(tMs: Long): List<Vehicle> {
        val t = tMs / 1000.0
        val out = mutableListOf<Vehicle>()

        if (t in 2.0..15.5) {
            val d = 80.0 - 6.0 * (t - 2.0)
            if (d >= 0) out.add(Vehicle(id = 1, distanceM = d.toInt().coerceAtLeast(0), speedMs = 6, lateralPos = 0f))
        }
        if (t in 10.0..25.0) {
            val d = 75.0 - 5.0 * (t - 10.0)
            if (d >= 0) out.add(Vehicle(id = 2, distanceM = d.toInt().coerceAtLeast(0), speedMs = 5, lateralPos = -0.3f))
        }
        if (t in 10.0..28.0) {
            val d = 90.0 - 5.0 * (t - 10.0)
            if (d >= 0) out.add(Vehicle(id = 3, distanceM = d.toInt().coerceAtLeast(0), speedMs = 5, lateralPos = 0.3f))
        }
        if (t in 22.0..30.5) {
            val d = 90.0 - 10.0 * (t - 22.0)
            out.add(Vehicle(id = 4, distanceM = d.toInt().coerceAtLeast(5), speedMs = 10, lateralPos = 0f))
        } else if (t > 30.5 && t <= 37.5) {
            out.add(Vehicle(id = 4, distanceM = 5, speedMs = 0, lateralPos = 0f))
        } else if (t > 37.5 && t <= 39.0) {
            val d = 5.0 - 4.0 * (t - 37.5)
            out.add(Vehicle(id = 4, distanceM = d.toInt().coerceAtLeast(0), speedMs = 4, lateralPos = 0.4f))
        }
        if (t in 36.0..42.1) {
            val d = 85.0 - 14.0 * (t - 36.0)
            if (d >= 0) out.add(Vehicle(id = 5, distanceM = d.toInt().coerceAtLeast(0), speedMs = 14, size = VehicleSize.TRUCK, lateralPos = 0.4f))
        }
        if (t in 42.0..54.0) {
            val d = 70.0 - 6.0 * (t - 42.0)
            if (d >= 0) out.add(Vehicle(id = 6, distanceM = d.toInt().coerceAtLeast(0), speedMs = 6, size = VehicleSize.BIKE, lateralPos = -0.7f))
        }
        if (t in 42.0..53.1) {
            val d = 55.0 - 5.0 * (t - 42.0)
            if (d >= 0) out.add(Vehicle(id = 7, distanceM = d.toInt().coerceAtLeast(0), speedMs = 5, lateralPos = 0f))
        }
        if (t in 42.0..53.5) {
            val d = 80.0 - 7.0 * (t - 42.0)
            if (d >= 0) out.add(Vehicle(id = 8, distanceM = d.toInt().coerceAtLeast(0), speedMs = 7, lateralPos = 0.6f))
        }
        if (t in 56.0..56.05) {
            out.add(Vehicle(id = 9, distanceM = 15, speedMs = 10, lateralPos = 0f))
        }

        // Extra "rush hour" overlap density between t=10 and t=30 to
        // exercise the box-shrink and the renderer-side stationary
        // suppression. id=10 is a parked-in-next-lane case that should
        // edge-dock once the rider speed gate flips on at t=10.
        if (t in 12.0..28.0) {
            out.add(
                Vehicle(
                    id = 10, distanceM = 4, speedMs = 0, size = VehicleSize.CAR,
                    lateralPos = -0.65f, speedXMs = 0,
                )
            )
        }
        if (t in 8.0..22.0) {
            val d = 60.0 - 4.0 * (t - 8.0)
            if (d >= 0) out.add(
                Vehicle(
                    id = 11, distanceM = d.toInt().coerceAtLeast(0), speedMs = 4,
                    lateralPos = 0.55f, speedXMs = 0,
                )
            )
        }
        if (t in 14.0..22.0) {
            val d = 50.0 - 6.0 * (t - 14.0)
            if (d >= 0) out.add(
                Vehicle(
                    id = 12, distanceM = d.toInt().coerceAtLeast(0), speedMs = 8,
                    size = VehicleSize.BIKE, lateralPos = -0.4f, speedXMs = 0,
                )
            )
        }
        if (t in 18.0..28.0) {
            val d = 70.0 - 9.0 * (t - 18.0)
            if (d >= 0) out.add(
                Vehicle(
                    id = 13, distanceM = d.toInt().coerceAtLeast(0), speedMs = 12,
                    size = VehicleSize.TRUCK, lateralPos = 0.65f, speedXMs = 0,
                )
            )
        }
        // Second peak around t=46..54 — close-pass-grade encounter.
        if (t in 46.0..50.0) {
            val d = 30.0 - 7.0 * (t - 46.0)
            if (d >= 0) out.add(
                Vehicle(
                    id = 14, distanceM = d.toInt().coerceAtLeast(0), speedMs = 13,
                    size = VehicleSize.CAR, lateralPos = 0.15f, speedXMs = 0,
                )
            )
        }
        return out
    }

    /** Bike speed schedule for the demo (m/s): rider is crawling through
     *  the rush-hour window (t 10..30 s) so the renderer-side parked-car
     *  gate can fire on id=10, then accelerates back up to a normal
     *  cruise speed for the rest of the run. Null in the warm-up
     *  window mirrors a real device-status delay before the first
     *  speed frame arrives. The crawl segment uses 2 m/s (~7 km/h):
     *  comfortably <= ALONGSIDE_RIDER_SLOW_MS = 2.75 with margin. */
    private fun bikeSpeedAt(tMs: Long): Float? {
        val t = tMs / 1000.0
        return when {
            t < 5.0 -> null
            t < 10.0 -> 5f      // 18 km/h
            t < 30.0 -> 2f      // 7 km/h - within alongside-slow gate
            t < 40.0 -> 3f      // 11 km/h
            else -> 6f          // 22 km/h
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Bike Radar synthetic scenario")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Synthetic scenario", NotificationManager.IMPORTANCE_MIN)
        )
    }

    companion object {
        const val CHANNEL_ID = "bike_radar_synthetic_min"
        const val NOTIF_ID = 4243
        private const val TOTAL_MS = 60_000L
        private const val DASHCAM_PUSH_MS = 20_000L
        private const val SYNTHETIC_MAC = "AA:BB:CC:DD:EE:FF"
        @Volatile var isRunning = false
    }
}
