// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract that keeps every Home Assistant surface telling the same story.
 *
 * The defect these pin: an unconfigured install rendered as a working MQTT
 * connection, because the initial [HaHealth.Unknown] was counted as healthy.
 * Two properties matter and are asserted separately - that a stored credential
 * pair is never treated as evidence the connection works, and that nothing but
 * an actual successful publish reaches [HaStatus.READY].
 */
class HaStatusDeriverTest {

    @Test fun unconfiguredIsNeverHealthyWhateverTheHealthSays() {
        // The shipped bug: Unknown is the resting state of a never-configured
        // install, and it rendered green. Configuration is the gate, and it
        // wins over every health value including a stale success.
        assertEquals(HaStatus.NOT_CONFIGURED, HaStatusDeriver.derive(configured = false, health = HaHealth.Unknown))
        assertEquals(HaStatus.NOT_CONFIGURED, HaStatusDeriver.derive(configured = false, health = HaHealth.Ok))
        assertEquals(
            HaStatus.NOT_CONFIGURED,
            HaStatusDeriver.derive(configured = false, health = HaHealth.Error("battery publish failed")),
        )
    }

    @Test fun configuredButNothingPublishedIsNotReady() {
        // Credentials are setup, not observation. This is the state a correctly
        // configured install sits in from every app start until its first
        // publish, so it must not read as a working connection - and equally
        // must not read as a failure, because nothing has failed.
        assertEquals(HaStatus.CONFIGURED, HaStatusDeriver.derive(configured = true, health = HaHealth.Unknown))
    }

    @Test fun onlyASuccessfulPublishIsReady() {
        assertEquals(HaStatus.READY, HaStatusDeriver.derive(configured = true, health = HaHealth.Ok))
    }

    @Test fun aFailureStandsUntilSomethingSucceeds() {
        // Deliberately not time-boxed: an error is not aged out into green,
        // because no new observation has happened. Pinned at both ends of the
        // age range so neither a recency window NOR its inverse can pass: an
        // ancient error, and a just-now one carrying the default atMs that
        // every production failure is built with.
        assertEquals(
            HaStatus.UNREACHABLE,
            HaStatusDeriver.derive(
                configured = true,
                health = HaHealth.Error("battery publish failed", atMs = 0L),
            ),
        )
        assertEquals(
            HaStatus.UNREACHABLE,
            HaStatusDeriver.derive(
                configured = true,
                health = HaHealth.Error("ride-edge publish failed"),
            ),
        )
    }

    @Test fun theVerdictDoesNotDependOnTheErrorText() {
        // The only two messages production ever emits are the publisher's
        // (HaPublisher), so a branch keyed on message content would pass a
        // suite that used only one of them. Both appear above; this pins that
        // an unfamiliar message is treated the same.
        assertEquals(
            HaStatus.UNREACHABLE,
            HaStatusDeriver.derive(configured = true, health = HaHealth.Error("")),
        )
    }

    @Test fun onlyNotConfiguredIsMutedAndHollow() {
        assertTrue("an unconfigured row dims", HaStatus.NOT_CONFIGURED.isMuted)
        assertTrue("an unconfigured row shows a hollow dot", HaStatus.NOT_CONFIGURED.isHollow)
        assertFalse(HaStatus.CONFIGURED.isMuted)
        assertFalse(HaStatus.CONFIGURED.isHollow)
        assertFalse(HaStatus.READY.isMuted)
        assertFalse(HaStatus.READY.isHollow)
        assertFalse(HaStatus.UNREACHABLE.isMuted)
        assertFalse(HaStatus.UNREACHABLE.isHollow)
    }
}
