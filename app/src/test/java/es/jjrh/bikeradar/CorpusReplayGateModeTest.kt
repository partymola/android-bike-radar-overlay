// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The corpus gate's refusals, which CI cannot reach through the gate itself:
 *  without a corpus it skips before deciding anything. */
class CorpusReplayGateModeTest {

    @Test fun `with the wait off any run may go ahead`() {
        assertNull(CorpusReplayGate.refusal(unconfidentWait = false, record = true, baselineExists = true))
        assertNull(CorpusReplayGate.refusal(unconfidentWait = false, record = false, baselineExists = false))
    }

    @Test fun `with the wait on a compare against an existing baseline may go ahead`() {
        assertNull(CorpusReplayGate.refusal(unconfidentWait = true, record = false, baselineExists = true))
    }

    @Test fun `with the wait on nothing may write the baseline`() {
        assertNotNull(CorpusReplayGate.refusal(unconfidentWait = true, record = true, baselineExists = true))
        assertNotNull(CorpusReplayGate.refusal(unconfidentWait = true, record = false, baselineExists = false))
    }
}
