// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * What every Home Assistant status surface is allowed to claim.
 *
 * The single derivation behind every Home Assistant status surface: the home
 * screen's System row, the Settings menu subtitle, and the Home Assistant
 * screen's connection pill and published-entity dots. Those surfaces
 * disagreeing is the defect this exists to prevent, so a new one reads its
 * state from here rather than re-deriving its own.
 *
 * The defect this replaces: the System row counted [HaHealth.Unknown] as
 * healthy and had no not-configured state at all, so a rider who had never
 * touched Home Assistant saw a green "MQTT ready" while Settings said
 * "Not configured".
 *
 * [HaHealthBus] is written by the publish paths only - battery, ride edge,
 * ride summary and close pass. So [HaHealth.Unknown] is the resting state
 * twice over: for an install that will never use Home Assistant, and for a
 * correctly configured one between app start and its first publish. Neither
 * may be rendered as a working connection - hence [CONFIGURED], which claims
 * setup and nothing more.
 *
 * An [UNREACHABLE] verdict is not time-boxed: it stands until a publish
 * succeeds. Ageing it out would silently promote a failure back to green
 * without a single new observation, and publishes are sparse enough (ride
 * edges) that "no news" carries no evidence either way.
 */
enum class HaStatus {
    /** No base URL or no token stored. The rider has not set this up. */
    NOT_CONFIGURED,

    /** Credentials stored, nothing published yet, so nothing observed yet. */
    CONFIGURED,

    /**
     * A publish has succeeded. The only state that may read as working.
     *
     * It cannot outlive the credentials that earned it: `HaCredentials.save`
     * and `clear` reset the bus on a genuine change, so editing the host puts
     * this back to [CONFIGURED] rather than leaving a stale claim. A publish
     * already in flight under the old credentials can still land afterwards
     * and re-assert a verdict; the next publish resolves it.
     */
    READY,

    /** The last publish failed, and nothing has succeeded since. */
    UNREACHABLE,
}

/**
 * Classify a Home Assistant status surface.
 *
 * @param configured credentials are stored (`HaCredentials.isConfigured()`).
 *   Stored credentials are not evidence they work: a typo'd URL is
 *   indistinguishable from a good one until something publishes.
 * @param health the last publish outcome, from [HaHealthBus].
 */
object HaStatusDeriver {
    fun derive(configured: Boolean, health: HaHealth): HaStatus = when {
        !configured -> HaStatus.NOT_CONFIGURED
        health is HaHealth.Error -> HaStatus.UNREACHABLE
        health is HaHealth.Ok -> HaStatus.READY
        else -> HaStatus.CONFIGURED
    }
}

/** Only a not-configured row dims its label, matching the not-paired rows. */
val HaStatus.isMuted: Boolean get() = this == HaStatus.NOT_CONFIGURED

/** Only a not-configured row shows a hollow dot, matching the not-paired rows. */
val HaStatus.isHollow: Boolean get() = this == HaStatus.NOT_CONFIGURED
