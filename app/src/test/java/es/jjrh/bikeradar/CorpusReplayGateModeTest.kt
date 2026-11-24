// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The corpus gate's mode names, refusals and mirror, which CI cannot reach
 *  through the gate itself: without a corpus it skips before deciding anything. */
class CorpusReplayGateModeTest {

    @Test fun `with neither switch the run has no compare-only mode`() {
        assertNull(CorpusReplayGate.compareOnlyMode(unconfidentWait = false, mirror = false))
    }

    @Test fun `the unconfident wait alone is named as it`() {
        assertEquals("unconfident wait ON", CorpusReplayGate.compareOnlyMode(unconfidentWait = true, mirror = false))
    }

    @Test fun `the mirror alone is named as it`() {
        assertEquals("mirrored", CorpusReplayGate.compareOnlyMode(unconfidentWait = false, mirror = true))
    }

    @Test fun `both switches are named together with the wait first`() {
        assertEquals("unconfident wait ON, mirrored", CorpusReplayGate.compareOnlyMode(unconfidentWait = true, mirror = true))
    }

    @Test fun `with no compare-only mode any run may go ahead`() {
        assertNull(CorpusReplayGate.refusal(compareOnlyMode = null, record = true, baselineExists = true))
        assertNull(CorpusReplayGate.refusal(compareOnlyMode = null, record = false, baselineExists = false))
    }

    @Test fun `with a compare-only mode a compare against an existing baseline may go ahead`() {
        assertNull(CorpusReplayGate.refusal(compareOnlyMode = "mirrored", record = false, baselineExists = true))
    }

    @Test fun `with a compare-only mode nothing may write the baseline`() {
        assertNotNull(CorpusReplayGate.refusal(compareOnlyMode = "mirrored", record = true, baselineExists = true))
        assertNotNull(CorpusReplayGate.refusal(compareOnlyMode = "mirrored", record = false, baselineExists = false))
    }

    @Test fun `the mirror flips every lateral field and nothing else`() {
        val v = Vehicle(
            id = 7,
            distanceM = 18,
            speedMs = -6.5f,
            lateralPos = 0.4f,
            rangeXm = 1.25f,
            rangeXmRaw = 0.75f,
            speedXMs = 2,
        )
        val m = CorpusReplayGate.mirrored(v)
        assertEquals(v.copy(lateralPos = -0.4f, rangeXm = -1.25f, rangeXmRaw = -0.75f, speedXMs = -2), m)
    }

    @Test fun `the mirror keeps a missing lateral reading as it is`() {
        val v = Vehicle(id = 1, distanceM = 30, speedMs = -4f)
        assertEquals(v, CorpusReplayGate.mirrored(v))
    }
}
