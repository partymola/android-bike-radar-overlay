// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToneGenerator

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LightFailAudioTest {

    @Test fun aCancelDuringTheToneEndsTheCallerToo() = runTest {
        // A caller that goes on after a cancel would post and sound for a link
        // that has ended.
        ShadowToneGenerator.reset()
        var reachedAfter = false
        val job = launch {
            playLightFailTone("test", "test light")
            reachedAfter = true
        }
        runCurrent()
        assertEquals("the tone is playing", 1, ShadowToneGenerator.getPlayedTones().size)
        advanceTimeBy(100)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse("the caller ran on after the cancel", reachedAfter)
    }

    @Test fun aToneThatIsNotCancelledReturnsToItsCaller() = runTest {
        ShadowToneGenerator.reset()
        var reachedAfter = false
        launch {
            playLightFailTone("test", "test light")
            reachedAfter = true
        }
        advanceTimeBy(700)
        runCurrent()
        assertTrue(reachedAfter)
    }
}
