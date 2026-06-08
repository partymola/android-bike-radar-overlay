// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Decides whether to fire the "you walked off without locking the bike" alert -
 * a wrist-haptic reminder (a high-importance phone notification the Pixel Watch
 * mirrors) for the one case the walk-away alarm deliberately stays silent for.
 *
 * The walk-away alarm arms on radar disconnect only when the bike is NOT
 * freshly-unlocked; getting off a still-unlocked bike (the radar drops while the
 * eBike reports unlocked) does not arm it - "the rider is still using the bike".
 * So a rider who dismounts and walks away WITHOUT locking gets no alert. This
 * decider fills that gap.
 *
 * Fire condition (once per radar-off episode): the radar has been live this
 * session and is now down past [downThresholdMs], the last eBike reading was
 * `system_locked == false` (unlocked), and that snapshot has since gone STALE
 * (age >= [freshMs]). A stale eBike link means the phone has moved out of
 * Bluetooth range of the bike - i.e. the rider walked away - so "last seen
 * unlocked + now out of range" is a precise "left the bike behind, unlocked"
 * signal, not a mid-ride radar blip (the snapshot is fresh while still riding).
 *
 * eBike-only: a radar-only rider has no lock state, so `system_locked == null`
 * never fires. False positive: a cafe stop a few metres from the unlocked bike
 * fires a dismissable buzz - asymmetric against a stolen eBike, so it errs
 * toward alerting. The app is read-only on the bike and cannot lock it; the
 * alert is a reminder only.
 */
object ForgotToLockDecider {

    fun shouldFire(
        enabled: Boolean,
        radarEverLive: Boolean,
        radarDownForMs: Long?,
        systemLocked: Boolean?,
        snapshotAgeMs: Long,
        freshMs: Long,
        downThresholdMs: Long,
        alreadyFired: Boolean,
    ): Boolean {
        if (!enabled || alreadyFired) return false
        val down = radarDownForMs ?: return false
        return radarEverLive &&
            down >= downThresholdMs &&
            systemLocked == false &&
            snapshotAgeMs >= freshMs
    }
}
