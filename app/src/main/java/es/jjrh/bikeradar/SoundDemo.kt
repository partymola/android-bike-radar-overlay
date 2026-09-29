// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import es.jjrh.bikeradar.data.Prefs

/**
 * The scripted scene behind the onboarding sound demo and the alert-sounds
 * glossary: one car approaching and passing while the rider rides, then a car
 * closing fast on a stopped rider.
 *
 * The demo never chooses its own sounds. [cues] runs the scene through a fresh
 * [AlertDecider] built as the ride pipeline builds it, at the default settings,
 * and maps each decision through [AlertCue.forEvent], so the demo cannot teach
 * a sound the app would not make for the same picture at those settings.
 * `SoundDemoTest` pins the sequence.
 */
internal object SoundDemo {

    const val FRAME_MS = 100L
    const val DURATION_MS = 20_000L

    /** The default alert distance: the scene is laid out for it, whatever the rider's setting. */
    const val ALERT_MAX_M = Prefs.DEFAULT_ALERT_MAX_DISTANCE_M

    /** The strip draws no further than the alert distance, so a car appears as
     *  it starts to beep and no caption about silence sits beside a drawn car. */
    const val VISUAL_MAX_M = ALERT_MAX_M

    /** One audible moment: [cue] sounds at [atMs] on the scene's clock. */
    data class Moment(val atMs: Long, val cue: AlertCue)

    /** Rider speed in m/s: riding, then braking to a stop before the second car. */
    fun bikeSpeedAt(tMs: Long): Float = when {
        tMs < 11_000L -> 6f
        tMs < 11_500L -> 3f
        else -> 0f
    }

    fun vehiclesAt(tMs: Long): List<Vehicle> {
        val t = tMs / 1000.0
        val out = mutableListOf<Vehicle>()
        if (t >= 1.0 && t < 8.5) {
            out += car(id = 1, distanceM = 30.0 - 4.0 * (t - 1.0), speedMs = -4f)
        }
        if (t >= 14.0) {
            val d = 30.0 - 8.0 * (t - 14.0)
            out += if (d > 4.0) car(id = 2, distanceM = d, speedMs = -8f) else car(id = 2, distanceM = 4.0, speedMs = 0f)
        }
        return out
    }

    /** The cues the ride pipeline's decider makes for the scene, in order. */
    fun cues(): List<Moment> {
        val decider = AlertDecider()
        val out = mutableListOf<Moment>()
        var t = 0L
        while (t <= DURATION_MS) {
            val cue = AlertCue.forEvent(
                decider.decide(vehicles = vehiclesAt(t), alertMaxM = ALERT_MAX_M, nowMs = t, bikeSpeedMs = bikeSpeedAt(t)),
            )
            if (cue != AlertCue.Silence) out += Moment(t, cue)
            t += FRAME_MS
        }
        return out
    }

    /**
     * Sounds the scene's cues as its clock passes them. The screen calls
     * [advanceTo] once per frame; a frame that jumps several cues ahead plays
     * each one, in order, rather than skipping any.
     */
    class Playback(private val moments: List<Moment> = cues(), private val play: (AlertCue) -> Unit) {
        var lastCue: AlertCue? = null
            private set
        private var next = 0

        fun advanceTo(tMs: Long) {
            while (next < moments.size && moments[next].atMs <= tMs) {
                val cue = moments[next++].cue
                lastCue = cue
                play(cue)
            }
        }
    }

    // Slightly off-centre, so the lateral gates see a measured car rather than
    // the 0f every consumer reads as "nothing measured".
    private fun car(id: Int, distanceM: Double, speedMs: Float) = Vehicle(
        id = id,
        distanceM = distanceM.toInt().coerceAtLeast(0),
        speedMs = speedMs,
        lateralPos = LATERAL_POS,
        rangeXm = LATERAL_POS * RadarV2Decoder.LATERAL_FULL_M,
        rangeXmRaw = LATERAL_POS * RadarV2Decoder.LATERAL_FULL_M,
    )

    private const val LATERAL_POS = 0.1f
}
