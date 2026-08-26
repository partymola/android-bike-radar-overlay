// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Debug cue-preview name -> cue dispatch ([playPreviewCue]) with a fake
 * player, so the mapping is tested without an AudioTrack-backed [AlertBeeper].
 */
class CuePreviewTest {

    private class FakeCuePlayer : CuePlayer {
        val calls = mutableListOf<String>()

        override fun play(beeps: Int) {
            calls += "play$beeps"
        }

        override fun playClear() {
            calls += "clear"
        }

        override fun playUrgent() {
            calls += "urgent"
        }

        override fun playRadarDropped() {
            calls += "dropped"
        }

        override fun playRadarReconnected() {
            calls += "reconnected"
        }
    }

    @Test fun eachCueNameDispatchesTheMatchingCall() {
        val expected = mapOf(
            BikeRadarService.CUE_BEEP_1 to "play1",
            BikeRadarService.CUE_BEEP_2 to "play2",
            BikeRadarService.CUE_BEEP_3 to "play3",
            BikeRadarService.CUE_CLEAR to "clear",
            BikeRadarService.CUE_URGENT to "urgent",
            BikeRadarService.CUE_DROPPED to "dropped",
            BikeRadarService.CUE_RECONNECTED to "reconnected",
        )
        for ((name, call) in expected) {
            val fake = FakeCuePlayer()
            playPreviewCue(name, fake)
            assertEquals("cue $name", listOf(call), fake.calls)
        }
    }

    @Test fun unknownOrNullNamePlaysNothing() {
        val fake = FakeCuePlayer()
        playPreviewCue("bogus", fake)
        playPreviewCue(null, fake)
        assertEquals(emptyList<String>(), fake.calls)
    }

    @Test fun nullPlayerIsANoOp() {
        // The warm beeper is only allocated between onCreate and onDestroy;
        // a preview arriving with no beeper must do nothing, not crash.
        playPreviewCue(BikeRadarService.CUE_URGENT, null)
    }
}
