// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide bus exposing the eBike live-data snapshot (and its freshness)
 * to UI surfaces outside the [BikeRadarService]. The service-owned
 * [EBikeStatusReader] mirrors each decoded frame here via [setSnapshot]; the
 * SYSTEM-card eBike row, Settings -> eBike and the onboarding step read it.
 *
 * Same pattern as [HaHealthBus] and [BatteryStateBus]: a MutableStateFlow per
 * signal, kept current by the producer, read via the read-only StateFlow. When
 * the service isn't running these stay at their last value; [reset] (called on
 * service destroy) clears them.
 */
/**
 * Why the eBike feed is not delivering, when it is not.
 *
 * The row used to render every non-receiving state as "Waiting for Flow",
 * which blames a third-party app for cases where this app is not trying at
 * all: the permission was revoked, or no bonded eBike existed when the
 * service started. Those need the RIDER to act and Flow cannot fix them, so
 * telling them to open Flow sends them to the one place that will not help.
 *
 * [NOT_PERMITTED] and [NO_BONDED_BIKE] are the app's own preconditions.
 * [WAITING] is the honest "subscribed or trying, nothing arriving yet" case,
 * which really is usually Flow not holding the link.
 */
enum class EBikeStage { NOT_STARTED, NOT_PERMITTED, NO_BONDED_BIKE, WAITING, RECEIVING }

object EBikeStateBus {
    private val _snapshot = MutableStateFlow(LiveDataSnapshot())
    val snapshot: StateFlow<LiveDataSnapshot> = _snapshot

    private val _stage = MutableStateFlow(EBikeStage.NOT_STARTED)
    val stage: StateFlow<EBikeStage> = _stage

    fun setStage(value: EBikeStage) {
        _stage.value = value
    }

    // Monotonic (elapsedRealtime) ms of the last snapshot update, so UI can tell
    // live from stale: the snapshot fields stay populated after Flow closes, so
    // freshness is the only honest "is data still flowing" signal. 0 = never.
    private val _lastUpdatedElapsedMs = MutableStateFlow(0L)
    val lastUpdatedElapsedMs: StateFlow<Long> = _lastUpdatedElapsedMs

    fun setSnapshot(value: LiveDataSnapshot) {
        _snapshot.value = value
        _lastUpdatedElapsedMs.value = SystemClock.elapsedRealtime()
        _stage.value = EBikeStage.RECEIVING
    }

    /** Restore default state. Called on service destroy - and when the
     *  Bluetooth adapter dies mid-session - so UI surfaces see a clean empty
     *  state instead of a frozen last snapshot. */
    fun reset() {
        _snapshot.value = LiveDataSnapshot()
        _lastUpdatedElapsedMs.value = 0L
        _stage.value = EBikeStage.NOT_STARTED
    }
}

/** A live-data frame newer than this counts as "receiving"; older is stale
 *  (Flow likely closed or the bike off). */
const val EBIKE_DATA_FRESH_MS = 6_000L

/**
 * Whether live eBike data is currently flowing, from the bus's
 * [EBikeStateBus.lastUpdatedElapsedMs] and now (both `elapsedRealtime`).
 * Pure (now is injectable) so UI freshness is unit-testable. `lastUpdated`
 * of 0 means no frame has ever arrived this session -> not fresh.
 */
fun eBikeDataIsFresh(
    lastUpdatedElapsedMs: Long,
    nowMs: Long = SystemClock.elapsedRealtime(),
    windowMs: Long = EBIKE_DATA_FRESH_MS,
): Boolean = lastUpdatedElapsedMs > 0L && (nowMs - lastUpdatedElapsedMs) in 0 until windowMs

/**
 * Which stage the eBike reader can reach, given the preconditions this app
 * controls.
 *
 * A function so the answer is testable: the service that used to decide it
 * inline is not constructible in this harness, so every branch here was
 * unreachable from a test while being exactly the logic that decided what the
 * rider is told. Returns the stage to publish; only [EBikeStage.WAITING] means
 * the reader should actually start.
 */
fun eBikeStartStage(
    featureEnabled: Boolean,
    blePermitted: Boolean,
    bondedMac: String?,
): EBikeStage = when {
    !featureEnabled -> EBikeStage.NOT_STARTED
    !blePermitted -> EBikeStage.NOT_PERMITTED
    bondedMac == null -> EBikeStage.NO_BONDED_BIKE
    else -> EBikeStage.WAITING
}
