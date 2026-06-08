// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ForgotToLockDecider]: fire once when the rider has walked off (radar down
 * past the threshold + eBike snapshot stale = out of range) while the bike's last
 * reading was unlocked. eBike-only; gated by an enable flag and a once-per-episode
 * latch.
 */
class ForgotToLockDeciderTest {

    private val fresh = 30_000L
    private val downThreshold = 30_000L

    private fun fire(
        enabled: Boolean = true,
        everLive: Boolean = true,
        downForMs: Long? = 35_000L,
        systemLocked: Boolean? = false,
        ageMs: Long = 35_000L,
        alreadyFired: Boolean = false,
    ) = ForgotToLockDecider.shouldFire(
        enabled = enabled,
        radarEverLive = everLive,
        radarDownForMs = downForMs,
        systemLocked = systemLocked,
        snapshotAgeMs = ageMs,
        freshMs = fresh,
        downThresholdMs = downThreshold,
        alreadyFired = alreadyFired,
    )

    @Test fun firesWhenLeftUnlocked() = assertTrue(fire())

    @Test fun disabledNeverFires() = assertFalse(fire(enabled = false))

    @Test fun latchSuppressesRepeatFire() = assertFalse(fire(alreadyFired = true))

    @Test fun coldStartDoesNotFire() = assertFalse(fire(everLive = false))

    @Test fun radarUpDoesNotFire() = assertFalse(fire(downForMs = null))

    @Test fun belowDownThresholdDoesNotFire() = assertFalse(fire(downForMs = downThreshold - 1))

    @Test
    fun freshSnapshotDoesNotFire() {
        // Still in range (snapshot fresh) = still riding/at the bike, not "walked
        // off" - the down threshold may be met but the staleness gate is not.
        assertFalse(fire(ageMs = fresh - 1))
    }

    @Test fun lockedBikeDoesNotFire() = assertFalse(fire(systemLocked = true))

    @Test
    fun noEBikeDoesNotFire() {
        // Radar-only rider: no lock state -> never fires.
        assertFalse(fire(systemLocked = null))
    }

    @Test fun atDownThresholdFires() = assertTrue(fire(downForMs = downThreshold))

    @Test fun atStalenessBoundaryFires() = assertTrue(fire(ageMs = fresh))
}
