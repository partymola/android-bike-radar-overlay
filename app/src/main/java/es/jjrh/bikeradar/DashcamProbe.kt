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
    private val coordinator: RadarLinkCoordinator,
    private val dashcamSlug: () -> String?,
    private val batteryReader: BatteryReader,
    private val lastProbeMs: MutableMap<String, Long>,
    private val probeFailures: Map<String, Int>,
) {
    fun tick(nowMs: Long) {
        val mac = prefs.activeDashcamMac ?: return
        val name = prefs.dashcamDisplayName
        if (name.isNullOrEmpty()) return
        val link = coordinator.snapshot()
        val entry = dashcamSlug()?.let { BatteryStateBus.entries.value[it] }
        val shownAgeMs = entry?.let { nowMs - it.lastSeenElapsedMs } ?: Long.MAX_VALUE
        val launch = shouldLaunch(
            radarGattActive = link.radarGattActive,
            radarOffSinceMs = link.radarOffSinceMs,
            nowMs = nowMs,
            shownAgeMs = shownAgeMs,
            heardAgeMs = nowMs - coordinator.dashcamHeardAtMs(nowMs, link),
            armed = coordinator.dashcamAlarmCanSound(link),
            lastProbeMs = lastProbeMs[mac],
            failures = probeFailures[mac] ?: 0,
        )
        // Only a read that started counts toward the backoff; one skipped for a
        // read still in flight does not
        // (`DashcamHeardWindowTest.aLaunchSkippedForAReadInFlightIsNotCountedAsAProbe`).
        if (launch && batteryReader.launch(name, mac)) lastProbeMs[mac] = nowMs
    }

    companion object {
        /**
         * While the dashcam alarm can sound, the next read starts on the first
         * tick at least this long after the camera was last heard, so 2 to 4 s
         * after. With a read ending within [BatteryReader.READ_TIMEOUT_MS], a
         * camera that answers is then never older than
         * [WalkAwayDecider.Config.dashcamFreshMs] while both loops run on time
         * (`DashcamProbeTest.anArmedProbeKeepsACameraThatAnswersInsideTheAlarmsWindow`).
         * The walk-away wakelock keeps them on time until the alarm first
         * sounds. After that, the camera's unheard time counts from the first
         * wake after each hearing, giving its read 20 s to land (a wake is a
         * tick after a sleep both loops slept through,
         * [RadarLinkCoordinator.LATE_TICK_MS]), and its answer runs the
         * walk-away tick at once
         * ([RadarLinkCoordinator.onDashcamAnswered]). Failed reads with a
         * radar link open back off from this base (4, 8, 16 s), which can pass
         * the bound.
         */
        const val ARMED_REFRESH_MS = 2_000L

        /**
         * Whether this tick launches a read. Only while [IdleGate] is open.
         * Otherwise once the camera was last heard ([heardAgeMs], an answer or
         * its light link) or last shown ([shownAgeMs], the battery bus)
         * [BikeRadarService.DASHCAM_REFRESH_MS] ago, whichever is older: a
         * sighting refreshes what is shown but never holds off the read the
         * alarm hears from, and a light link that is up still lets the shown
         * reading be refreshed. While [armed], once it was last heard
         * [ARMED_REFRESH_MS] ago. While the radar's link is open, a camera
         * whose reads keep failing backs off ([BatteryProbeBackoff]) from the
         * same base, so the probe cannot connect-storm the radar link; a
         * failing radar holds that link for its handshake, so the backoff
         * applies then too.
         */
        fun shouldLaunch(
            radarGattActive: Boolean,
            radarOffSinceMs: Long?,
            nowMs: Long,
            shownAgeMs: Long,
            heardAgeMs: Long,
            armed: Boolean,
            lastProbeMs: Long?,
            failures: Int,
        ): Boolean {
            val ageMs = if (armed) heardAgeMs else maxOf(heardAgeMs, shownAgeMs)
            val baseMs = if (armed) ARMED_REFRESH_MS else BikeRadarService.DASHCAM_REFRESH_MS
            return IdleGate.shouldRefreshDashcam(radarGattActive, radarOffSinceMs, nowMs) &&
                ageMs >= baseMs &&
                BatteryProbeBackoff.shouldProbe(
                    radarGattActive,
                    nowMs,
                    lastProbeMs,
                    failures,
                    baseMs,
                    BikeRadarService.DASHCAM_PROBE_BACKOFF_CAP_MS,
                )
        }
    }
}
