// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The eBike feed must not blame Bosch Flow for this app's own preconditions.
 *
 * The row rendered every non-receiving state as "Waiting for Flow". Two of
 * those states are ours: the Bluetooth permission was revoked, or no eBike was
 * bonded when the service started. Neither is fixable by opening Flow, so the
 * old copy sent the rider to the one place that could not help them, and the
 * reader's own stages reached logcat only - so nothing recorded which case it
 * was, on a phone or in a report.
 *
 * "Waiting for Flow" stays the answer for the state where it is true: the app
 * is subscribed, or trying, and nothing is arriving.
 */
class EBikeStageTest {

    @After
    fun tearDown() {
        EBikeStateBus.reset()
    }

    @Test
    fun aFreshBusHasNotStartedTheReader() {
        EBikeStateBus.reset()
        assertEquals(EBikeStage.NOT_STARTED, EBikeStateBus.stage.value)
    }

    @Test
    fun theTwoPreconditionFailuresAreDistinctFromWaiting() {
        // Distinct values, because they need different rider actions: grant a
        // permission, pair a bike, or open Flow. Collapsing them is what made
        // the row wrong.
        EBikeStateBus.setStage(EBikeStage.NOT_PERMITTED)
        assertEquals(EBikeStage.NOT_PERMITTED, EBikeStateBus.stage.value)

        EBikeStateBus.setStage(EBikeStage.NO_BONDED_BIKE)
        assertEquals(EBikeStage.NO_BONDED_BIKE, EBikeStateBus.stage.value)

        EBikeStateBus.setStage(EBikeStage.WAITING)
        assertEquals(EBikeStage.WAITING, EBikeStateBus.stage.value)
    }

    @Test
    fun aSnapshotArrivingMeansReceiving() {
        // The stage must follow the data rather than be set alongside it by
        // every caller: a snapshot IS the evidence the feed is live, and a
        // caller that forgot to update the stage would leave the row claiming
        // a failure while values streamed in.
        EBikeStateBus.setStage(EBikeStage.NOT_PERMITTED)
        EBikeStateBus.setSnapshot(LiveDataSnapshot())

        assertEquals(EBikeStage.RECEIVING, EBikeStateBus.stage.value)
    }

    @Test
    fun resetClearsTheStageWithTheData() {
        // reset() runs on service destroy and when the adapter dies. A stage
        // surviving it would describe a session that no longer exists.
        EBikeStateBus.setSnapshot(LiveDataSnapshot())
        EBikeStateBus.reset()

        assertEquals(EBikeStage.NOT_STARTED, EBikeStateBus.stage.value)
        assertEquals(0L, EBikeStateBus.lastUpdatedElapsedMs.value)
    }

    @Test
    fun theFeatureBeingOffIsNotAFailure() {
        // Off is a rider choice, not a fault. The row is hidden entirely in
        // this case, so a failure stage here would surface nowhere and mislead
        // anyone reading the journal.
        assertEquals(
            EBikeStage.NOT_STARTED,
            eBikeStartStage(featureEnabled = false, blePermitted = false, bondedMac = null),
        )
    }

    @Test
    fun aMissingPermissionOutranksAMissingBike() {
        // Without the permission the app cannot enumerate bonded devices at
        // all, so "no bonded bike" would be a conclusion drawn from a query it
        // was never allowed to make. Report what is actually known.
        assertEquals(
            EBikeStage.NOT_PERMITTED,
            eBikeStartStage(featureEnabled = true, blePermitted = false, bondedMac = null),
        )
    }

    @Test
    fun apermittedButUnpairedBikeSaysSo() {
        assertEquals(
            EBikeStage.NO_BONDED_BIKE,
            eBikeStartStage(featureEnabled = true, blePermitted = true, bondedMac = null),
        )
    }

    @Test
    fun everythingInPlaceMeansTheReaderShouldStart() {
        // The only stage the caller treats as "go".
        assertEquals(
            EBikeStage.WAITING,
            eBikeStartStage(featureEnabled = true, blePermitted = true, bondedMac = "AA:BB:CC:DD:EE:FF"),
        )
    }
}
