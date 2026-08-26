// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

/**
 * Minimal cue-playing surface. [AlertBeeper] implements it; extracting it lets
 * the Debug cue-preview's name-to-cue dispatch ([playPreviewCue]) be unit-tested
 * with a fake, without an AudioTrack-backed beeper.
 */
internal interface CuePlayer {
    fun play(beeps: Int)
    fun playClear()
    fun playUrgent()
    fun playRadarDropped()
    fun playRadarReconnected()
}

/**
 * Pure dispatch for the dev cue-preview (Debug screen): play the cue named
 * [name] on [player], or nothing if the name is unknown or [player] is null
 * (the warm beeper is only allocated between onCreate and onDestroy). Names are
 * the `BikeRadarService.CUE_*` constants the Debug buttons send. Bypasses the
 * deciders' firing gates - purely the audio, to audition one cue in isolation.
 */
internal fun playPreviewCue(name: String?, player: CuePlayer?) {
    if (player == null) return
    when (name) {
        BikeRadarService.CUE_BEEP_1 -> player.play(1)
        BikeRadarService.CUE_BEEP_2 -> player.play(2)
        BikeRadarService.CUE_BEEP_3 -> player.play(3)
        BikeRadarService.CUE_CLEAR -> player.playClear()
        BikeRadarService.CUE_URGENT -> player.playUrgent()
        BikeRadarService.CUE_DROPPED -> player.playRadarDropped()
        BikeRadarService.CUE_RECONNECTED -> player.playRadarReconnected()
    }
}
