// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.bluetooth.BluetoothGatt
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke tests for the [EBikeLink.outcome] flow and the [LdiOutcome]
 * sealed-class identity. Exercises the parts of the state machine that
 * are reachable without a real BLE stack: initial state, start() with
 * adapter unavailable, stop() reset.
 *
 * The pure decisions extracted from the GATT callbacks - [classifyMissingLdi],
 * [classifyInboundDisconnect] (SlotConflict / Advertising attribution), and
 * [shouldEnterPaired] (the pairing rising-edge) - are unit-tested here. The
 * callback plumbing that drives them (Connecting / NoServiceFound /
 * Timeouts) still requires live BLE and is verified by on-bike behaviour
 * with the capture log + outcome logging in EBikeLink itself.
 */
@RunWith(RobolectricTestRunner::class)
class EBikeLinkOutcomeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `initial outcome is Idle`() {
        val link = EBikeLink(app)
        assertEquals(LdiOutcome.Idle, link.outcome.value)
    }

    @Test fun `start without BLE adapter emits AdapterUnavailable`() {
        // Robolectric ships a BluetoothManager whose adapter is enabled
        // by default at API level 28+; nothing under test actually
        // advertises, so the failure path we hit is server-open returning
        // null (no real BLE radio). Either way, the outcome must be a
        // terminal failure, never linger in Idle.
        val link = EBikeLink(app)
        val started = link.start()
        assertFalse("start() must return false without a real BLE radio", started)
        val o = link.outcome.value
        val acceptable = o == LdiOutcome.AdapterUnavailable ||
            o == LdiOutcome.PermissionsDenied
        assert(acceptable) { "expected AdapterUnavailable or PermissionsDenied, got $o" }
    }

    @Test fun `stop after failed start resets to Idle`() {
        val link = EBikeLink(app)
        link.start()
        link.stop()
        assertEquals(LdiOutcome.Idle, link.outcome.value)
    }

    @Test fun `LdiOutcome sealed class enumerates all expected states`() {
        // Anchors the API surface: a future contributor renaming or
        // removing an outcome breaks this test before it breaks any
        // collector. Paired is a data class so a sample instance stands
        // in for the type; the rest are singletons.
        val all: List<LdiOutcome> = listOf(
            LdiOutcome.Idle,
            LdiOutcome.Advertising,
            LdiOutcome.Connecting,
            LdiOutcome.Paired("AA:BB:CC:DD:EE:FF"),
            LdiOutcome.NoServiceFound,
            LdiOutcome.SlotConflict,
            LdiOutcome.PermissionsDenied,
            LdiOutcome.AdapterUnavailable,
            LdiOutcome.NoInbound,
            LdiOutcome.PairPromptDeclined,
        )
        assertEquals(10, all.size)
        // Each is a distinct type. For data classes, instances with
        // different content compare unequal; for objects, distinct
        // identities. Either way, no two should equal each other.
        for (i in all.indices) {
            for (j in (i + 1) until all.size) {
                assert(all[i] != all[j]) {
                    "${all[i]::class.simpleName} == ${all[j]::class.simpleName}"
                }
            }
        }
    }

    @Test fun `Paired carries the bonded address`() {
        // UIs render "Paired with bike at <shortAddress>" by reading
        // this field. The empty-string defensive default is only set
        // when the address was unobservable at inbound-connect time,
        // which shouldn't happen on real hardware.
        val paired = LdiOutcome.Paired("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", paired.shortAddress)
    }

    @Test fun `classifyMissingLdi reports old firmware only for a bonded clean discovery`() {
        // The connection-trust gate: a bonded (paired) bike that completes
        // discovery without the LDI service is genuinely old firmware.
        assertEquals(
            MissingLdi.OLD_FIRMWARE,
            classifyMissingLdi(BluetoothGatt.GATT_SUCCESS, bonded = true),
        )
    }

    @Test fun `classifyMissingLdi treats stray or failed connections as not-the-bike`() {
        // An unbonded stray central probing the solicitation advert, or any
        // failed/aborted discovery (even on a bonded device), must NOT be
        // reported as old firmware. Regression guard for the bug where a
        // passing device showed "Firmware too old" with the bike powered off.
        assertEquals(
            MissingLdi.NOT_THE_BIKE,
            classifyMissingLdi(BluetoothGatt.GATT_SUCCESS, bonded = false),
        )
        assertEquals(
            MissingLdi.NOT_THE_BIKE,
            classifyMissingLdi(BluetoothGatt.GATT_FAILURE, bonded = true),
        )
        assertEquals(
            MissingLdi.NOT_THE_BIKE,
            classifyMissingLdi(BluetoothGatt.GATT_FAILURE, bonded = false),
        )
    }

    @Test fun `classifyInboundDisconnect maps auth-failure statuses to SlotConflict`() {
        // Either auth status, while not yet Paired, means another accessory
        // holds the single LDI slot.
        assertEquals(
            LdiOutcome.SlotConflict,
            classifyInboundDisconnect(LdiOutcome.Connecting, STATUS_INSUFFICIENT_AUTH),
        )
        assertEquals(
            LdiOutcome.SlotConflict,
            classifyInboundDisconnect(LdiOutcome.Advertising, STATUS_GATT_ERROR_VENDOR_AUTH),
        )
    }

    @Test fun `classifyInboundDisconnect drops a mid-pair vanish back to Advertising`() {
        // A plain disconnect while Connecting (no auth status) is the bike
        // vanishing mid-pair; fall back so the 90s timer can attribute it.
        assertEquals(
            LdiOutcome.Advertising,
            classifyInboundDisconnect(LdiOutcome.Connecting, BluetoothGatt.GATT_SUCCESS),
        )
    }

    @Test fun `classifyInboundDisconnect leaves a trusted or quiet connection unchanged`() {
        // Once Paired the connection is trusted: even an auth-status disconnect
        // is ignored (bike powering down). And a non-auth disconnect that isn't
        // mid-pair has nothing to attribute.
        assertNull(classifyInboundDisconnect(LdiOutcome.Paired("AA:BB"), STATUS_INSUFFICIENT_AUTH))
        assertNull(classifyInboundDisconnect(LdiOutcome.Advertising, BluetoothGatt.GATT_SUCCESS))
        assertNull(classifyInboundDisconnect(LdiOutcome.Idle, BluetoothGatt.GATT_SUCCESS))
    }

    @Test fun `shouldEnterPaired fires once on the first positive RTC snapshot`() {
        assertTrue(shouldEnterPaired(LdiOutcome.Connecting, timeSec = 1_700_000_000L))
        assertTrue(shouldEnterPaired(LdiOutcome.Advertising, timeSec = 1L))
    }

    @Test fun `shouldEnterPaired ignores absent, zero, or negative RTC`() {
        assertFalse(shouldEnterPaired(LdiOutcome.Connecting, timeSec = null))
        assertFalse(shouldEnterPaired(LdiOutcome.Connecting, timeSec = 0L))
        assertFalse(shouldEnterPaired(LdiOutcome.Connecting, timeSec = -5L))
    }

    @Test fun `shouldEnterPaired does not re-fire once already Paired`() {
        // Subsequent snapshots after pairing are just data refreshes.
        assertFalse(shouldEnterPaired(LdiOutcome.Paired("AA:BB"), timeSec = 1_700_000_000L))
    }

    @Test fun `CONNECT_TIMEOUT_MS is the documented 90 seconds`() {
        // Sized for the observed pair flow (see EBikeLink.armConnectTimeout
        // KDoc). Shortening this without updating the user-facing copy
        // would silently lie to the rider.
        assertEquals(90_000L, CONNECT_TIMEOUT_MS)
    }
}
