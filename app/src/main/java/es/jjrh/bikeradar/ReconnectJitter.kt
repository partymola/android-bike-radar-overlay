// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import kotlin.random.Random

/**
 * Apply +/-20% uniform jitter to a reconnect-backoff value. Returns a value in
 * `[baseMs * 0.8, baseMs * 1.2]`. Pass a seeded [Random] for deterministic
 * tests; production callers use [Random.Default].
 *
 * The rear radar, the front camera/light and the eBike status reader all run
 * independent reconnect loops with the same backoff schedule (1 s -> 2 s -> 4 s
 * -> 8 s, capped). Without jitter, a group ride that briefly takes everyone's
 * radios out of range (e.g. a station's RF cage) leaves every rider's three
 * BLE stacks retrying on the same millisecond grid for the rest of the ride.
 * Jitter dephases the retries so two riders riding the same backoff schedule
 * don't keep colliding.
 *
 * Non-positive inputs pass through unchanged - jittering zero or a negative
 * delay would put us in the past.
 */
internal fun jittered(baseMs: Long, random: Random = Random.Default): Long {
    if (baseMs <= 0L) return baseMs
    val jitterRangeMs = (baseMs * 0.2).toLong()
    if (jitterRangeMs <= 0L) return baseMs
    return baseMs + random.nextLong(-jitterRangeMs, jitterRangeMs + 1)
}
