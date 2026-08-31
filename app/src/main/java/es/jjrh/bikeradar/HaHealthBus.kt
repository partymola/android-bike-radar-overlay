// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Why a publish failed, in the terms the rider's next action depends on.
 *
 * Every failure used to arrive as "publish failed", which is true of a
 * mistyped token, a host that does not resolve, a URL the app refuses to send
 * a token over, and a Home Assistant that is simply down. Those are four
 * different things to go and do, and the rider was told none of them.
 *
 * Derived from what the transport actually saw, by [classifyHaFailure]. It is
 * a classification of an observation, never a guess: anything unrecognised
 * stays [UNKNOWN] rather than being attributed to the most likely cause.
 */
enum class HaFailure {
    /** 401/403. The token is wrong, expired, or revoked. */
    AUTH,

    /** 404. The URL reaches something, but not this endpoint. */
    NOT_FOUND,

    /** The app refused to send the token over cleartext to a non-local host. */
    INSECURE_REFUSED,

    /** No connection at all: DNS, route, or timeout. */
    HOST_UNREACHABLE,

    /** 5xx. Home Assistant answered and something is wrong at its end. */
    SERVER_ERROR,

    /** Recognised as a failure, not as one of the above. */
    UNKNOWN,
}

/**
 * Classify a publish failure from what the transport observed.
 *
 * Pure, so the mapping is asserted directly instead of through a network
 * stack. Exactly one of [code] and [error] is expected to be non-null: a
 * response carries a code, a thrown exception carries a class name.
 *
 * @param code the HTTP status, when the request completed.
 * @param error the simple class name of a thrown exception, when it did not.
 * @param insecureRefusal the publish never left the app because the URL was
 *   cleartext to a non-local host.
 */
fun classifyHaFailure(
    code: Int? = null,
    error: String? = null,
    insecureRefusal: Boolean = false,
): HaFailure = when {
    insecureRefusal -> HaFailure.INSECURE_REFUSED
    code == 401 || code == 403 -> HaFailure.AUTH
    code == 404 -> HaFailure.NOT_FOUND
    code != null && code in 500..599 -> HaFailure.SERVER_ERROR
    // Name-matched rather than typed: the client catches Throwable and this
    // stays out of the network layer's types. Substring, because the JDK
    // spells these differently across hosts (SocketTimeoutException,
    // UnknownHostException, ConnectException, NoRouteToHostException).
    error != null &&
        (
            error.contains("UnknownHost", ignoreCase = true) ||
                error.contains("Timeout", ignoreCase = true) ||
                error.contains("Connect", ignoreCase = true) ||
                error.contains("NoRoute", ignoreCase = true) ||
                error.contains("SocketException", ignoreCase = true)
            ) -> HaFailure.HOST_UNREACHABLE
    else -> HaFailure.UNKNOWN
}

/** Which stream of publishes an outcome belongs to. */
enum class HaFamily { BATTERY, RIDE_EDGE, RIDE_SUMMARY, CLOSE_PASS }

sealed class HaHealth {
    object Unknown : HaHealth()
    object Ok : HaHealth()
    data class Error(
        val message: String,
        val atMs: Long = System.currentTimeMillis(),
        val cause: HaFailure = HaFailure.UNKNOWN,
    ) : HaHealth()
}

/**
 * Combine the per-family outcomes into the one verdict a status surface shows.
 *
 * A failure outranks a success, and that is deliberate rather than pessimism:
 * the families publish on different schedules, so a battery heartbeat
 * succeeding minutes after a ride-summary failed is not evidence the summary
 * would now work. Reporting green on the strength of a different stream is
 * how a broken family stays invisible.
 *
 * Among failures the NEWEST wins, because it is the only one with a current
 * observation behind it.
 */
fun aggregateHaHealth(perFamily: Map<HaFamily, HaHealth>): HaHealth {
    val errors = perFamily.values.filterIsInstance<HaHealth.Error>()
    if (errors.isNotEmpty()) return errors.maxBy { it.atMs }
    if (perFamily.values.any { it is HaHealth.Ok }) return HaHealth.Ok
    return HaHealth.Unknown
}

object HaHealthBus {
    private val _state = MutableStateFlow<HaHealth>(HaHealth.Unknown)

    /** The one verdict every status surface reads. */
    val state: StateFlow<HaHealth> = _state

    private val _families = MutableStateFlow<Map<HaFamily, HaHealth>>(emptyMap())

    /**
     * Per-stream outcomes, for the diagnostic bundle rather than the row.
     *
     * The row can only say one thing, and [aggregateHaHealth] decides what.
     * This is what tells a maintainer that battery publishes are fine and
     * close-pass events are not - a distinction the aggregate erases, and the
     * one that names which topic to look at.
     */
    val families: StateFlow<Map<HaFamily, HaHealth>> = _families

    /**
     * Serialises [record] against itself and against [reset].
     *
     * The reporters genuinely run on different threads - close-pass from the
     * overlay's IO dispatcher, ride-edge, battery and ride-summary from the
     * service scope's pool, and [reset] from whichever thread saved the
     * credentials. Without this, three things go wrong and only the first is
     * fixed by an atomic map update: two reports read the same old map and one
     * family's outcome is dropped; the aggregate is recomputed from a re-read
     * that a newer writer may already have replaced, so `state` can contradict
     * `families`; and [reset]'s two assignments can interleave with a report,
     * leaving an empty map beside a non-Unknown state. A dropped Error is the
     * worst of them, because it restores exactly the silence this bus exists
     * to remove.
     */
    private val lock = Any()

    fun reportOk(family: HaFamily) = record(family, HaHealth.Ok)

    fun reportError(family: HaFamily, message: String, cause: HaFailure = HaFailure.UNKNOWN) = record(family, HaHealth.Error(message, cause = cause))

    private fun record(family: HaFamily, health: HaHealth) {
        synchronized(lock) {
            val next = _families.value + (family to health)
            _families.value = next
            // Aggregated from the map just written, not a re-read of the
            // field, so a concurrent writer cannot slip between the two.
            _state.value = aggregateHaHealth(next)
        }
    }

    /**
     * Forget every observation.
     *
     * Called when the stored credentials change, and that is the whole point:
     * an outcome is evidence about the credentials that produced it, so it
     * cannot survive them. Without this a rider who fixes a mistyped host goes
     * on reading UNREACHABLE until something publishes, and one who breaks a
     * working host goes on reading READY. Pinned by [HaHealthBusTest].
     */
    fun reset() {
        synchronized(lock) {
            _families.value = emptyMap()
            _state.value = HaHealth.Unknown
        }
    }
}
