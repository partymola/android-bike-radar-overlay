// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import es.jjrh.bikeradar.data.Prefs

/**
 * One tick of the dashcam liveness probe: a real battery read of the selected
 * camera, so the walk-away alarm and the camera glyph see whether it still
 * answers. The service runs [tick] every [BikeRadarService.DASHCAM_TICK_MS]
 * from onCreate to onDestroy; it is a class so a test can run the same tick in
 * virtual time. [shouldLaunch] holds the decision.
 */
internal class DashcamProbe(
    private val prefs: Prefs,
    private val linkState: () -> RadarLinkState,
    private val dashcamSlug: () -> String?,
    private val batteryReader: BatteryReader,
    private val lastProbeMs: MutableMap<String, Long>,
    private val probeFailures: Map<String, Int>,
) {
    fun tick(nowMs: Long) {
        val mac = prefs.activeDashcamMac ?: return
        val name = prefs.dashcamDisplayName
        if (name.isNullOrEmpty()) return
        val link = linkState()
        val entry = dashcamSlug()?.let { BatteryStateBus.entries.value[it] }
        val ageMs = entry?.let { nowMs - it.lastSeenElapsedMs } ?: Long.MAX_VALUE
        if (!shouldLaunch(link.radarGattActive, link.radarOffSinceMs, nowMs, ageMs, lastProbeMs[mac], probeFailures[mac] ?: 0)) return
        lastProbeMs[mac] = nowMs
        batteryReader.launch(name, mac)
    }

    companion object {
        /**
         * Whether this tick launches a read. Only while [IdleGate] is open,
         * and once the camera's last read is [BikeRadarService.DASHCAM_REFRESH_MS]
         * old, on the monotonic clock the walk-away alarm reads. While the
         * radar's link is open, a camera whose reads keep failing backs off
         * ([BatteryProbeBackoff]) so the probe cannot connect-storm the radar
         * link; a failing radar holds that link for its handshake, so the
         * backoff applies then too.
         */
        fun shouldLaunch(
            radarGattActive: Boolean,
            radarOffSinceMs: Long?,
            nowMs: Long,
            dashcamAgeMs: Long,
            lastProbeMs: Long?,
            failures: Int,
        ): Boolean = IdleGate.shouldRefreshDashcam(radarGattActive, radarOffSinceMs, nowMs) &&
            dashcamAgeMs >= BikeRadarService.DASHCAM_REFRESH_MS &&
            BatteryProbeBackoff.shouldProbe(
                radarGattActive,
                nowMs,
                lastProbeMs,
                failures,
                BikeRadarService.DASHCAM_REFRESH_MS,
                BikeRadarService.DASHCAM_PROBE_BACKOFF_CAP_MS,
            )
    }
}
