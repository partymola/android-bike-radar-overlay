// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.RadarLightMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The seam between a granted app's request and a live radar link.
 *
 * The failure it exists to prevent is the quiet one: a handler left installed
 * after the ride service dies would either write to a dead GATT or hold it
 * from being collected, and a consumer would be told the write succeeded.
 */
class RadarControlBridgeTest {

    @Before fun clean() = RadarControlBridge.reset()

    @After fun cleanUp() = RadarControlBridge.reset()

    @Test
    fun withNoRadarLinkedTheAnswerIsNoRatherThanSilence() {
        assertFalse(RadarControlBridge.available)
        assertFalse(
            "a consumer must be able to tell nothing happened",
            RadarControlBridge.set(RadarLightMode.SOLID),
        )
    }

    @Test
    fun anInstalledHandlerReceivesTheModeAsked() {
        var got: RadarLightMode? = null
        RadarControlBridge.install {
            got = it
            true
        }

        assertTrue(RadarControlBridge.set(RadarLightMode.PELOTON))
        assertEquals(RadarLightMode.PELOTON, got)
    }

    @Test
    fun aHandlerThatFailsIsReportedAsFailure() {
        // A GATT write can be refused by the link. Reporting success would
        // leave the consumer showing a light mode the radar never took.
        RadarControlBridge.install { false }

        assertFalse(RadarControlBridge.set(RadarLightMode.OFF))
    }

    @Test
    fun resetStopsTheHandlerBeingCalledAtAll() {
        var calls = 0
        RadarControlBridge.install {
            calls++
            true
        }
        RadarControlBridge.set(RadarLightMode.SOLID)

        RadarControlBridge.reset()
        val after = RadarControlBridge.set(RadarLightMode.SOLID)

        assertFalse(after)
        assertEquals("the stale handler must not run", 1, calls)
        assertFalse(RadarControlBridge.available)
    }

    @Test
    fun reconnectingReplacesTheHandlerRatherThanStacking() {
        // Each connect installs; without replacement the first connection's
        // captured GATT would keep answering after it had gone.
        var first: RadarLightMode? = null
        var second: RadarLightMode? = null
        RadarControlBridge.install {
            first = it
            true
        }
        RadarControlBridge.install {
            second = it
            true
        }

        RadarControlBridge.set(RadarLightMode.NIGHT_FLASH)

        assertNull("the superseded handler must not run", first)
        assertEquals(RadarLightMode.NIGHT_FLASH, second)
    }
}
