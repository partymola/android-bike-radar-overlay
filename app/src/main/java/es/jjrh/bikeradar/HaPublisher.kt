// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.util.Log
import es.jjrh.bikeradar.data.HaCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * publish and, through [offerFrontMode], the camera-light front-mode publish,
 * by way of `() -> HaClient` providers; the service rebuilds that client when
 * the stored credentials change, so every consumer follows a mid-session save.
 *
 * Threading: [publishRideEdgeIfHa], the battery and front-mode sends and the
 * ride-summary loop launch into the injected [scope]; the summary path runs on IO and
 * reads + marks the [rideStats] accumulator there. The accumulator stays single-writer-on-Main
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
    private val newClient: (baseUrl: String, token: String) -> HaClient = ::HaClient,
    /** Stamps the battery heartbeat. */
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {

    // Per-slug HA discovery + throttle state. One HaPublisher is built per
    // service lifetime, so these start empty for each ride.
    private val discoveredSlugs = ConcurrentHashMap.newKeySet<String>()
    private val lastHaPublishMs = ConcurrentHashMap<String, Long>()
    private val lastPublishedPct = ConcurrentHashMap<String, Int>()
    private val rideSummaryDiscoveredSlugs = ConcurrentHashMap.newKeySet<String>()
    private val frontModeDiscoveredSlugs = ConcurrentHashMap.newKeySet<String>()

    private fun client() = newClient(creds.baseUrl, creds.token)

    private class PendingSend(val send: suspend () -> Boolean, val waiters: List<CompletableDeferred<Boolean>>)

    private class SendSlot {
        var draining = false
        var pending: PendingSend? = null
    }

    // Per device and entity; guarded by itself.
    private val sendSlots = HashMap<String, SendSlot>()

    /**
     * Queues a battery reading for Home Assistant and returns at once: the
     * send runs on this publisher's scope, never on the caller's, so a
     * connection loop never waits on Home Assistant
     * (`RadarLinkControllerHarnessTest.aHungBatterySendDoesNotHoldTheRadarFrames`).
     * One send per device runs at a time, and a reading offered while one runs
     * replaces any still waiting (`offersDuringAHungSendCollapseToTheNewest`).
     */
    fun offerBattery(name: String, pct: Int) = enqueueBattery(name, pct, null)

    /**
     * [offerBattery], then waits for the send this reading went out in: its
     * own, or the newer one that replaced it. True when nothing needed
     * sending or the send succeeded, so a caller's throttle arms; false on a
     * failed send.
     */
    suspend fun sendBattery(name: String, pct: Int): Boolean {
        val result = CompletableDeferred<Boolean>()
        enqueueBattery(name, pct, result)
        return result.await()
    }

    private fun enqueueBattery(name: String, pct: Int, waiter: CompletableDeferred<Boolean>?) {
        val s = slug(name)
        enqueue("$s/battery", waiter) { sendBatteryIfDue(s, name, pct) }
    }

    /**
     * Queues the front camera's light [mode] for Home Assistant, or only its
     * discovery when [mode] is null, and returns at once, as [offerBattery]
     * does and on a queue of its own
     * (`CameraLightLinkControllerHarnessTest.aHungFrontModeSendDoesNotHoldTheCameraLoop`,
     * `theNewestFrontModeGoesNextWhenSeveralWaitBehindAHungSend`). Each send
     * publishes the discovery first until one succeeds. [ha] is read when the
     * send runs.
     */
    fun offerFrontMode(ha: () -> HaClient, slug: String, name: String, mode: String?) = enqueue("$slug/front_mode", null) { sendFrontMode(ha(), slug, name, mode) }

    private suspend fun sendFrontMode(ha: HaClient, s: String, name: String, mode: String?): Boolean {
        if (!ha.isConfigured()) return true
        if (frontModeDiscoveredSlugs.add(s) && !ha.publishFrontModeDiscovery(s, name)) frontModeDiscoveredSlugs.remove(s)
        return mode == null || ha.publishFrontModeState(s, mode)
    }

    private fun enqueue(key: String, waiter: CompletableDeferred<Boolean>?, send: suspend () -> Boolean) {
        val startDrain = synchronized(sendSlots) {
            val slot = sendSlots.getOrPut(key) { SendSlot() }
            slot.pending = PendingSend(send, slot.pending?.waiters.orEmpty() + listOfNotNull(waiter))
            val idle = !slot.draining
            slot.draining = true
            idle
        }
        if (startDrain) scope.launch { drain(key) }
    }

    private suspend fun drain(key: String) {
        while (true) {
            val next = synchronized(sendSlots) {
                val slot = sendSlots.getValue(key)
                val p = slot.pending
                slot.pending = null
                if (p == null) slot.draining = false
                p
            } ?: return
            val ok = try {
                next.send()
            } catch (e: CancellationException) {
                val queued = synchronized(sendSlots) {
                    val slot = sendSlots.getValue(key)
                    slot.draining = false
                    slot.pending.also { slot.pending = null }
                }
                (next.waiters + queued?.waiters.orEmpty()).forEach { it.cancel(e) }
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "HA send for $key threw: ${t.javaClass.simpleName}")
                false
            }
            next.waiters.forEach { it.complete(ok) }
        }
    }

    /**
     * Sends [pct] when it differs from the last value sent for this device,
     * or [BATTERY_HA_HEARTBEAT_MS] after it, so HA's last-update stays recent;
     * records it on success. Decided when the send runs, not when the reading
     * was offered, so a repeat of the reading in flight is not sent twice.
     */
    private suspend fun sendBatteryIfDue(s: String, name: String, pct: Int): Boolean {
        val now = wallClock()
        val lastMs = lastHaPublishMs[s] ?: 0L
        val due = pct != lastPublishedPct[s] || (now - lastMs) >= BATTERY_HA_HEARTBEAT_MS
        if (!due) return true
        val ok = publishBatteryToHa(name, pct)
        if (ok) {
            lastHaPublishMs[s] = now
            lastPublishedPct[s] = pct
        }
        return ok
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
                HaHealthBus.reportOk(HaFamily.RIDE_EDGE)
            } else {
                HaHealthBus.reportError(
                    HaFamily.RIDE_EDGE,
                    "ride-edge publish failed",
                    ha.lastFailure ?: HaFailure.UNKNOWN,
                )
                Log.w(TAG, "HA ride-edge publish failed: $edgeName")
            }
        }
    }

    /**
     * Publishes a battery percentage. Returns true on success, and also when
     * HA isn't configured at all, so the caller's throttle arms instead of
     * retrying every advert. A transient HA failure returns false so the next
     * advert retries rather than waiting for the 5-min heartbeat.
     */
    private suspend fun publishBatteryToHa(name: String, pct: Int): Boolean {
        val ha = client()
        if (!ha.isConfigured()) return true

        val s = slug(name)
        var newlyDiscovered = false
        if (discoveredSlugs.add(s)) {
            val ok = ha.publishBatteryDiscovery(s, name)
            if (!ok) {
                discoveredSlugs.remove(s)
                Log.w(TAG, "HA discovery failed for ${HaClient.NS}_${s}_battery")
                // Reported, not just logged: a token broken from the first
                // publish makes discovery fail on every attempt, and a family
                // that never reports reads in the bundle as "not published
                // this session" - which is the wording for a quiet stream,
                // not a failing one.
                HaHealthBus.reportError(
                    HaFamily.BATTERY,
                    "battery discovery failed",
                    ha.lastFailure ?: HaFailure.UNKNOWN,
                )
                return false
            }
            Log.i(TAG, "HA discovery published for ${HaClient.NS}_${s}_battery")
            newlyDiscovered = true
        }
        val ok = ha.publishBatteryState(s, pct)
        if (newlyDiscovered) {
            // Retire this device's entities from the naming generations the
            // app has shipped, so the old ones do not sit beside the new.
            // Launched, never awaited: a couple of dozen sequential round
            // trips would hold this device's next battery send behind them.
            scope.launch {
                val retired = ha.cleanupStaleDiscoveryTopics(listOf(s))
                Log.i(TAG, "HA stale-entity cleanup for $s: ok=$retired")
            }
        }
        if (ok) {
            HaHealthBus.reportOk(HaFamily.BATTERY)
        } else {
            HaHealthBus.reportError(
                HaFamily.BATTERY,
                "battery publish failed",
                ha.lastFailure ?: HaFailure.UNKNOWN,
            )
            Log.w(TAG, "HA state publish failed for ${HaClient.NS}/$s/battery")
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
                HaHealthBus.reportError(
                    HaFamily.RIDE_SUMMARY,
                    "ride-summary discovery failed",
                    ha.lastFailure ?: HaFailure.UNKNOWN,
                )
                return
            }
            Log.i(TAG, "ride-summary discovery published for $slug")
        }

        val ok = ha.publishRideSummaryState(slug, stats.snapshot())
        if (ok) {
            stats.markPublished()
            HaHealthBus.reportOk(HaFamily.RIDE_SUMMARY)
        } else {
            Log.w(TAG, "ride-summary state publish failed")
            HaHealthBus.reportError(
                HaFamily.RIDE_SUMMARY,
                "ride-summary publish failed",
                ha.lastFailure ?: HaFailure.UNKNOWN,
            )
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
