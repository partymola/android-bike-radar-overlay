// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The radar's rider speed through the real [RadarV2Decoder] into the real
 * [AlertDecider], on the radar-only wiring [CueLedgerReplayTest] uses: no
 * eBike data, so the decoder's speed is the only stationary signal.
 */
class SparseStatusFrameAlertTest {

    @Test fun aRiderAtSpeedStillHearsACarAfterASparseStatusFrame() {
        val beeps = beepsAfter(byteArrayOf(0x04, 0x00, 0x05, 0x00, 0x00, 20)) // 5 m/s
        assertEquals(listOf(1, 2, 3), beeps)
    }

    @Test fun aRiderWhoseFullFrameSaysStoppedIsHeldAsStationary() {
        // The same road with the radar reporting the rider stopped: the
        // stationary hold the sparse frame must not trigger.
        val beeps = beepsAfter(byteArrayOf(0x04, 0x00, 0x05, 0x00, 0x00, 0))
        assertEquals(emptyList<Int>(), beeps)
    }

    /** Feeds [fullStatus], a sparse status frame, 2.6 s of empty road (past
     *  the decider's 2 s stationary dwell), then a car closing at 3 m/s from
     *  15 m to 6.3 m, through all three thirds of a 20 m alert distance.
     *  Returns the beep counts heard. */
    private fun beepsAfter(fullStatus: ByteArray): List<Int> {
        var clock = 0L
        val decoder = RadarV2Decoder(nowMs = { clock })
        val alerts = AlertDecider()
        val beeps = ArrayList<Int>()
        fun feed(t: Long, bytes: ByteArray) {
            clock = t
            val state = decoder.feed(bytes) ?: return
            val ev = alerts.decide(
                vehicles = state.vehicles,
                alertMaxM = 20,
                nowMs = clock,
                bikeSpeedMs = state.bikeSpeedMs,
                bikeNotDriving = null,
                urgentLowSpeedEnabled = true,
            )
            if (ev is AlertDecider.Event.Beep) beeps += ev.count
        }
        feed(0L, fullStatus)
        feed(100L, byteArrayOf(0x04, 0x00, 0x01, 0x00, 0x00))
        for (t in 200L..2_700L step 100L) feed(t, byteArrayOf(0x02, 0x00))
        for (i in 0 until 30) feed(2_800L + i * 100L, carFrame(rangeYdm = 150 - i * 3))
        return beeps
    }

    /** One target frame: car 1 dead behind at [rangeYdm] (0.1 m units),
     *  closing at 3 m/s, no lateral velocity, with the observed (16, 7)
     *  class template the decoder tests use. */
    private fun carFrame(rangeYdm: Int): ByteArray {
        val packed = (rangeYdm and 0x1FFF) shl 11
        return byteArrayOf(0x02, 0x00) + byteArrayOf(
            1,
            23, // moderate vehicle class
            (packed and 0xFF).toByte(),
            ((packed shr 8) and 0xFF).toByte(),
            ((packed shr 16) and 0xFF).toByte(),
            16,
            7,
            -6, // closing 3 m/s
            -128, // no lateral velocity
        )
    }
}
