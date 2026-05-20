// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import es.jjrh.bikeradar.LdiOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Status-text derivation for Settings -> eBike. Pins the design's §1
 * status taxonomy:
 *
 *   Off -> Bluetooth permission needed -> Pairing rejected ... ->
 *   Firmware too old (need v19.54+) -> Connecting... ->
 *   Paired with bike at <short address> -> Not paired
 *
 * Off precedence is the most-important assertion: an LDI-disabled
 * screen must never render "Paired" / "Connecting" leftovers from the
 * last session's outcome bus value.
 */
class SettingsEBikeStatusTest {

    @Test
    fun offWhenDisabled() {
        // ldiEnabled = false -> always "Off", regardless of bond /
        // outcome state. The status group's chip colour and ACTIONS
        // group both branch off this string.
        assertEquals(
            "Off",
            settingsStatusText(
                ldiEnabled = false,
                bondedAddress = "AA:BB:CC:DD:EE:FF",
                outcome = LdiOutcome.Paired("AA:BB:CC:DD:EE:FF"),
            ),
        )
        assertEquals(
            "Off",
            settingsStatusText(
                ldiEnabled = false,
                bondedAddress = null,
                outcome = LdiOutcome.Idle,
            ),
        )
    }

    @Test
    fun bluetoothPermissionTrumpsBond() {
        // Even with a stored bond, a missing permission means the
        // subsystem can't talk to the bike; the rider needs to know
        // this rather than seeing a misleading "Paired" line.
        assertEquals(
            "Bluetooth permission needed",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = "AA:BB:CC:DD:EE:FF",
                outcome = LdiOutcome.PermissionsDenied,
            ),
        )
    }

    @Test
    fun adapterOffSurfaced() {
        assertEquals(
            "Bluetooth is off",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = null,
                outcome = LdiOutcome.AdapterUnavailable,
            ),
        )
    }

    @Test
    fun slotConflictExplicit() {
        // Bond may still exist locally, but the bike is rejecting it.
        assertEquals(
            "Pairing rejected (another device holds the slot)",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = "AA:BB:CC:DD:EE:FF",
                outcome = LdiOutcome.SlotConflict,
            ),
        )
    }

    @Test
    fun firmwareTooOldExplicit() {
        assertEquals(
            "Firmware too old (need v19.54+)",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = null,
                outcome = LdiOutcome.NoServiceFound,
            ),
        )
    }

    @Test
    fun connectingProgress() {
        assertEquals(
            "Connecting...",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = null,
                outcome = LdiOutcome.Connecting,
            ),
        )
    }

    @Test
    fun pairedShowsShortAddress() {
        assertEquals(
            "Paired with bike at AA:BB:CC...",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = "AA:BB:CC:DD:EE:FF",
                outcome = LdiOutcome.Paired("AA:BB:CC:DD:EE:FF"),
            ),
        )
    }

    @Test
    fun pairedFallsBackToBondedAddressWhenOutcomeAddressEmpty() {
        // EBikeLink.LdiOutcome.Paired carries an empty string when the
        // address wasn't observed in the inbound callback (defensive
        // default). The Settings line must still show the persisted
        // bond rather than "Paired with bike at " with nothing after.
        assertEquals(
            "Paired with bike at AA:BB:CC...",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = "AA:BB:CC:DD:EE:FF",
                outcome = LdiOutcome.Paired(""),
            ),
        )
    }

    @Test
    fun idleWithBondedAddressReadsPaired() {
        // After a cold start, the service hasn't republished anything
        // to the bus yet. With a persisted bond we still want to show
        // "Paired" rather than scaring the rider with "Not paired".
        assertEquals(
            "Paired with bike at AA:BB:CC...",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = "AA:BB:CC:DD:EE:FF",
                outcome = LdiOutcome.Idle,
            ),
        )
    }

    @Test
    fun idleWithNoBondReadsNotPaired() {
        assertEquals(
            "Not paired",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = null,
                outcome = LdiOutcome.Idle,
            ),
        )
    }

    @Test
    fun advertisingWithoutBondReadsNotPaired() {
        // Settings shows the coarser view; the live "Waiting for the
        // bike..." copy is the onboarding step's concern. The Settings
        // status simply reads "Not paired" until a bond exists.
        assertEquals(
            "Not paired",
            settingsStatusText(
                ldiEnabled = true,
                bondedAddress = null,
                outcome = LdiOutcome.Advertising,
            ),
        )
    }

    @Test
    fun shortenAddressTruncates() {
        assertEquals("AA:BB:CC...", shortenAddress("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun shortenAddressShorterThanCutoffLeftAsIs() {
        assertEquals("ABCD", shortenAddress("ABCD"))
    }

    @Test
    fun shortenAddressBlankUntouched() {
        assertEquals("", shortenAddress(""))
    }

    @Test
    fun subOnlyShownWhenPairedOrLikelyPaired() {
        // When the rider has a bond and the bus is Idle (service not
        // running, cold start), the sub still reads. This is a small
        // affordance: confirming the rider is "set up" without forcing
        // them to launch a ride to verify.
        assertEquals(
            "Reading wheel speed and lock state.",
            settingsStatusSub(
                ldiEnabled = true,
                bondedAddress = "AA:BB:CC:DD:EE:FF",
                outcome = LdiOutcome.Idle,
            ),
        )
        // No bond, advertising -> no sub.
        assertNull(
            settingsStatusSub(
                ldiEnabled = true,
                bondedAddress = null,
                outcome = LdiOutcome.Advertising,
            ),
        )
        // Disabled -> no sub.
        assertNull(
            settingsStatusSub(
                ldiEnabled = false,
                bondedAddress = "AA:BB:CC:DD:EE:FF",
                outcome = LdiOutcome.Paired("AA:BB:CC:DD:EE:FF"),
            ),
        )
    }
}
