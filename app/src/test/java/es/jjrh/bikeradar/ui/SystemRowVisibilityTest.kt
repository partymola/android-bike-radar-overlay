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

    @Test fun onlyNotPairedIsMutedAndHollow() {
        assertTrue("not-paired rows dim", DeviceLinkState.NOT_PAIRED.muted)
        assertTrue("not-paired rows show a hollow dot", DeviceLinkState.NOT_PAIRED.hollow)
        assertFalse(DeviceLinkState.LIVE.muted)
        assertFalse(DeviceLinkState.LIVE.hollow)
        assertFalse(DeviceLinkState.NO_SIGNAL.muted)
        assertFalse(DeviceLinkState.NO_SIGNAL.hollow)
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
