// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

/**
 * The scripted scene behind the sound demo, which onboarding plays and Settings
 * replays: one car approaching and passing while the rider rides, then a car
 * closing fast on a stopped rider.
 *
 * The demo never chooses its own sounds. [cues] runs the scene through a fresh
 * [AlertDecider] built as the ride pipeline builds it, at the given alert
 * distance and the default settings otherwise, and maps each decision through
 * [AlertCue.forEvent], so the demo cannot teach a sound the app would not make
 * for the same picture at those settings. The scene is the same at every alert
 * distance, so a longer one sounds sooner. `SoundDemoTest` pins the sequence at
 * 10, 20 and 40 m and the shape at every distance in between.
 */
internal object SoundDemo {

    const val FRAME_MS = 100L
    const val DURATION_MS = 20_000L

    private const val CAR_ONE_START_S = 1.0
    private const val CAR_ONE_START_M = 41.0
    private const val CAR_ONE_SPEED_MS = 4.15

    /** One audible moment: [cue] sounds at [atMs] on the scene's clock. */
    data class Moment(val atMs: Long, val cue: AlertCue)

    /** Rider speed in m/s: riding, then braking to a stop before the second car. */
    fun bikeSpeedAt(tMs: Long): Float = when {
        tMs < 11_000L -> 6f
        tMs < 11_500L -> 3f
        else -> 0f
    }

    /**
     * The scene at [tMs]. Both cars are born beyond the slider's longest
     * distance. Car 1 reaches the rider while they are still riding and is gone
     * by the 10.9 s frame
     * (`SoundDemoTest.carOneReachesTheRiderBeforeTheBrakingStartsAndIsGoneAfter`).
     * Car 2 closes at 14 m/s, so its time to collision is under the urgent
     * gate's 3 s anywhere inside 40 m and it sounds the frame after it is drawn
     * at every distance
     * (`SoundDemoTest.eachCarIsDrawnOneFrameBeforeItsFirstSoundAtEveryAlertDistance`).
     */
    fun vehiclesAt(tMs: Long): List<Vehicle> {
        val t = tMs / 1000.0
        val out = mutableListOf<Vehicle>()
        val carOneM = CAR_ONE_START_M - CAR_ONE_SPEED_MS * (t - CAR_ONE_START_S)
        if (t >= CAR_ONE_START_S && carOneM > 0.0) {
            out += car(id = 1, distanceM = carOneM, speedMs = -CAR_ONE_SPEED_MS.toFloat(), heardAtMs = tMs)
        }
        if (t >= 14.0) {
            val d = 45.0 - 14.0 * (t - 14.0)
            out += if (d > 4.0) {
                car(id = 2, distanceM = d, speedMs = -14f, heardAtMs = tMs)
            } else {
                car(id = 2, distanceM = 4.0, speedMs = 0f, heardAtMs = tMs)
            }
        }
        return out
    }

    /** The cues the ride pipeline's decider makes for the scene, in order. */
    fun cues(alertMaxM: Int): List<Moment> {
        val decider = AlertDecider()
        val out = mutableListOf<Moment>()
        var t = 0L
        while (t <= DURATION_MS) {
            val cue = AlertCue.forEvent(
                decider.decide(vehicles = vehiclesAt(t), alertMaxM = alertMaxM, nowMs = t, bikeSpeedMs = bikeSpeedAt(t)),
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
    class Playback(alertMaxM: Int, private val play: (AlertCue) -> Unit) {
        private val moments = cues(alertMaxM)
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
    private fun car(id: Int, distanceM: Double, speedMs: Float, heardAtMs: Long) = Vehicle(
        id = id,
        distanceM = distanceM.toInt().coerceAtLeast(0),
        speedMs = speedMs,
        lateralPos = LATERAL_POS,
        rangeXm = LATERAL_POS * RadarV2Decoder.LATERAL_FULL_M,
        rangeXmRaw = LATERAL_POS * RadarV2Decoder.LATERAL_FULL_M,
        heardAtMs = heardAtMs,
    )

    private const val LATERAL_POS = 0.1f
}
