// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.util.Log
import es.jjrh.bikeradar.data.HaCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * All outbound Home Assistant MQTT publishing: device-battery state, the
 * ride-edge markers, and the periodic ride-summary snapshot. Optional - every
 * method is a clean no-op when HA is not configured, so a radar-only rider who
 * never set up HA pays nothing.
 *
 * A fresh [HaClient] is built per publish call from [creds] (whose `baseUrl` /
 * `token` getters re-read storage live), so a credential change takes effect on
 * the next publish here without restarting the service. The client this does NOT
 * own is the service-held one, used by the overlay pipeline's close-pass
 * publish and the camera-light front-mode publish through `() -> HaClient`
 * providers; the service rebuilds that client when the stored credentials
 * change, so every consumer follows a mid-session save.
 *
 * Threading: [publishRideEdgeIfHa] and the ride-summary loop launch into the
 * injected [scope]; the summary path runs on IO and reads + marks the
 * [rideStats] accumulator there. The accumulator stays single-writer-on-Main
 * for its tally side (see BikeRadarService.onCue); this IO reader only consumes
 * a consistent snapshot and never mutates the running totals. The accumulator
 * REFERENCE is swapped from the walk-away tick (IO) when a long radar-off gap
 * starts a new ride - @Volatile on the service field makes the fresh instance
 * visible here on the next loop pass.
 */
internal class HaPublisher(
    private val scope: CoroutineScope,
    private val creds: HaCredentials,
    private val rideStats: () -> RideStatsAccumulator,
    private val currentRadarMac: () -> String?,
    private val macToSlug: () -> ConcurrentHashMap<String, String>,
    private val loadKnownDevices: () -> List<Pair<String, String>>,
    private val slug: (String) -> String,
) {

    // Per-slug HA discovery + throttle state. One HaPublisher is built per
    // service lifetime, so these start empty for each ride.
    private val discoveredSlugs = ConcurrentHashMap.newKeySet<String>()
    private val lastHaPublishMs = ConcurrentHashMap<String, Long>()
    private val lastPublishedPct = ConcurrentHashMap<String, Int>()
    private val rideSummaryDiscoveredSlugs = ConcurrentHashMap.newKeySet<String>()

    private fun client() = HaClient(creds.baseUrl, creds.token)

    // Throttles HA publishes driven by the 2a19 notify stream (~5 s cadence).
    // Publishes immediately on pct change; otherwise one heartbeat every
    // BATTERY_HA_HEARTBEAT_MS to keep HA's last-update recent.
    suspend fun maybePublishBatteryToHa(name: String, pct: Int) {
        val s = slug(name)
        val now = System.currentTimeMillis()
        val lastPct = lastPublishedPct[s]
        val lastMs = lastHaPublishMs[s] ?: 0L
        val shouldPublish = pct != lastPct || (now - lastMs) >= BATTERY_HA_HEARTBEAT_MS
        if (!shouldPublish) return
        if (publishBatteryToHa(name, pct)) {
            lastHaPublishMs[s] = now
            lastPublishedPct[s] = pct
        }
    }

    /**
     * Fire-and-forget HA publish for ride-edge events. Called from
     * the BLE callback thread; launches into [scope] so the BLE thread is
     * never blocked. Silently no-ops when HA isn't configured; the
     * decider still keeps state, just nothing reaches the dashboard for
     * radar-only riders who never set up HA.
     */
    fun publishRideEdgeIfHa(edgeName: String, timestampIso: String) {
        scope.launch {
            val ha = client()
            if (!ha.isConfigured()) return@launch
            val ok = ha.publishRideEdge(edgeName, timestampIso)
            if (ok) {
                HaHealthBus.reportOk()
            } else {
                HaHealthBus.reportError("ride-edge publish failed")
                Log.w(TAG, "HA ride-edge publish failed: $edgeName")
            }
        }
    }

    /**
     * Publishes a battery percentage. Returns true on success - and also when
     * HA isn't configured at all, so the caller's throttle arms instead of
     * retrying every advert. A transient HA failure returns false so the next
     * advert retries within the cooldown rather than after the 5-min heartbeat.
     */
    suspend fun publishBatteryToHa(name: String, pct: Int): Boolean {
        val ha = client()
        if (!ha.isConfigured()) return true

        val s = slug(name)
        if (discoveredSlugs.add(s)) {
            val ok = ha.publishBatteryDiscovery(s, name)
            if (!ok) {
                discoveredSlugs.remove(s)
                Log.w(TAG, "HA discovery failed for varia_${s}_battery")
                return false
            }
            Log.i(TAG, "HA discovery published for varia_${s}_battery")
        }
        val ok = ha.publishBatteryState(s, pct)
        if (ok) {
            HaHealthBus.reportOk()
        } else {
            HaHealthBus.reportError("battery publish failed")
            Log.w(TAG, "HA state publish failed for varia/$s/battery")
        }
        return ok
    }

    fun launchRideSummaryPublishLoop() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(RIDE_SUMMARY_PUBLISH_PERIOD_MS)
                publishRideSummaryIfChanged()
            }
        }
    }

    /**
     * Publishes the current ride-summary snapshot if anything changed since
     * the last successful publish. Called periodically by the publish loop
     * and ad-hoc on radar disconnect for a snappier final value.
     *
     * Discovery is published once per slug per service lifetime, gated by
     * [rideSummaryDiscoveredSlugs]. A discovery failure is rolled back so
     * the next call retries.
     */
    suspend fun publishRideSummaryIfChanged() {
        val ha = client()
        if (!ha.isConfigured()) return
        val stats = rideStats()
        if (!stats.changedSinceLast()) return
        val mac = currentRadarMac() ?: return
        val map = macToSlug()
        val slug = map[mac]
            ?: map[mac.uppercase(Locale.ROOT)]
            ?: return
        val deviceName = loadKnownDevices()
            .firstOrNull { it.second.equals(mac, ignoreCase = true) }
            ?.first
            ?: "radar"

        if (rideSummaryDiscoveredSlugs.add(slug)) {
            val ok = ha.publishRideSummaryDiscovery(slug, deviceName)
            if (!ok) {
                rideSummaryDiscoveredSlugs.remove(slug)
                Log.w(TAG, "ride-summary discovery publish failed; will retry")
                return
            }
            Log.i(TAG, "ride-summary discovery published for $slug")
        }

        val ok = ha.publishRideSummaryState(slug, stats.snapshot())
        if (ok) {
            stats.markPublished()
        } else {
            Log.w(TAG, "ride-summary state publish failed")
        }
    }

    companion object {
        private const val TAG = "BikeRadar"

        const val BATTERY_HA_HEARTBEAT_MS = 5 * 60 * 1000L

        // Ride-summary publish cadence. The accumulator only changes on
        // radar events, so most ticks short-circuit via changedSinceLast.
        // 60 s is fine-grained enough that a close-pass shows up in HA
        // within a minute, while still cheap when the rider is parked.
        const val RIDE_SUMMARY_PUBLISH_PERIOD_MS = 60_000L
    }
}
