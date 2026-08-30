// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit assertions for the System-card visibility predicates extracted
 * from [SystemCard]. These contracts were previously verified only as Roborazzi
 * golden PNGs (e.g. "eBike SoC hidden when not receiving, even if a value is
 * present"); here they are asserted directly so a logic regression fails as a
 * named test, not an opaque image diff.
 */
class SystemRowVisibilityTest {

    @Test fun notLinkedIsNotPairedRegardlessOfFreshness() {
        // The link precondition wins over freshness: a stale-but-not-linked row
        // is "not paired", never "no signal".
        assertEquals(DeviceLinkState.NOT_PAIRED, deviceLinkState(linked = false, fresh = false))
        assertEquals(DeviceLinkState.NOT_PAIRED, deviceLinkState(linked = false, fresh = true))
    }

    @Test fun linkedAndFreshIsLive() {
        assertEquals(DeviceLinkState.LIVE, deviceLinkState(linked = true, fresh = true))
    }

    @Test fun linkedButStaleIsNoSignal() {
        assertEquals(DeviceLinkState.NO_SIGNAL, deviceLinkState(linked = true, fresh = false))
    }

    @Test fun aConnectingLinkIsNotReportedAsNoSignal() {
        // The row used to have no CONNECTING state at all, so a radar the app
        // connects to and fails the setup sequence against read "No signal" -
        // and the report that came back was "never in range" about a radar
        // being talked to every second or so. The Settings card already
        // distinguishes these two; this is the home screen catching up.
        assertEquals(
            DeviceLinkState.CONNECTING,
            deviceLinkState(linked = true, fresh = false, connecting = true),
        )
        assertEquals(
            DeviceLinkState.NO_SIGNAL,
            deviceLinkState(linked = true, fresh = false, connecting = false),
        )
    }

    @Test fun freshDataOutranksConnecting() {
        // Data arriving is the stronger fact. A link that is both mid-attempt
        // and delivering frames is delivering.
        assertEquals(
            DeviceLinkState.LIVE,
            deviceLinkState(linked = true, fresh = true, connecting = true),
        )
    }

    @Test fun aLimitedSourceIsNotReportedAsLive() {
        // Green is this app's "you are covered" signal. A range-only radar
        // cannot raise the urgent warning or log a close pass, so it must not
        // render identically to a radar that can.
        assertEquals(
            DeviceLinkState.LIMITED,
            deviceLinkState(linked = true, fresh = true, limited = true),
        )
        assertEquals(
            DeviceLinkState.LIVE,
            deviceLinkState(linked = true, fresh = true, limited = false),
        )
    }

    @Test fun limitedOnlyAppliesWhileDataIsArriving() {
        // "Limited" is a statement about the source that is feeding the row.
        // With nothing arriving there is no source to qualify, and the honest
        // answer is the connection state.
        assertEquals(
            DeviceLinkState.NO_SIGNAL,
            deviceLinkState(linked = true, fresh = false, limited = true),
        )
        assertEquals(
            DeviceLinkState.CONNECTING,
            deviceLinkState(linked = true, fresh = false, limited = true, connecting = true),
        )
    }

    @Test fun theUnpairedPreconditionStillWinsOverEverything() {
        // A row that is not paired says so, whatever the other flags claim -
        // otherwise a stale flag from a previous device could dress an unpaired
        // row as connecting.
        assertEquals(
            DeviceLinkState.NOT_PAIRED,
            deviceLinkState(linked = false, fresh = true, limited = true, connecting = true),
        )
    }

    @Test fun onlyNotPairedIsMutedAndHollow() {
        assertTrue("not-paired rows dim", DeviceLinkState.NOT_PAIRED.muted)
        assertTrue("not-paired rows show a hollow dot", DeviceLinkState.NOT_PAIRED.hollow)
        assertFalse(DeviceLinkState.LIVE.muted)
        assertFalse(DeviceLinkState.LIVE.hollow)
        assertFalse(DeviceLinkState.NO_SIGNAL.muted)
        assertFalse(DeviceLinkState.NO_SIGNAL.hollow)
        // The two new states are live rows, not absent ones: a dimmed or
        // hollow row reads as "nothing here", which is the opposite of what
        // both of them mean.
        assertFalse(DeviceLinkState.LIMITED.muted)
        assertFalse(DeviceLinkState.LIMITED.hollow)
        assertFalse(DeviceLinkState.CONNECTING.muted)
        assertFalse(DeviceLinkState.CONNECTING.hollow)
    }

    @Test fun ebikeChipShowsSocOnlyWhileReceiving() {
        assertEquals(82, ebikeBatteryChipSoc(receiving = true, soc = 82))
        // The contract the golden pinned: a carried-over SoC is hidden when the
        // stream is not being received, so the chip never shows a stale number
        // as if it were current.
        assertNull(ebikeBatteryChipSoc(receiving = false, soc = 82))
        assertNull(ebikeBatteryChipSoc(receiving = true, soc = null))
    }
}
