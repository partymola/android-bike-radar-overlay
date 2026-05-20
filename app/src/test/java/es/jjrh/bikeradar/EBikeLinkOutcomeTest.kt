// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke tests for the [EBikeLink.outcome] flow and the [LdiOutcome]
 * sealed-class identity. Exercises the parts of the state machine that
 * are reachable without a real BLE stack: initial state, start() with
 * adapter unavailable, stop() reset.
 *
 * Deeper transitions (Connecting / Paired / NoServiceFound / SlotConflict
 * / Timeouts) require live BLE callbacks and are verified by on-bike
 * behaviour with the capture log + outcome logging in EBikeLink itself.
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

    @Test fun `CONNECT_TIMEOUT_MS is the documented 90 seconds`() {
        // Sized for the observed pair flow (see EBikeLink.armConnectTimeout
        // KDoc). Shortening this without updating the user-facing copy
        // would silently lie to the rider.
        assertEquals(90_000L, CONNECT_TIMEOUT_MS)
    }
}
