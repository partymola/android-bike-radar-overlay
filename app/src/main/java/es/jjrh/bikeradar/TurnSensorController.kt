// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Feeds [TurnStateDecider] with the rider's yaw rate about the gravity
 * axis, derived from the gyroscope projected onto the gravity vector:
 * `yawRate = gyro . g_unit`. The projection makes the reading independent
 * of how the phone sits in its handlebar mount - no coordinate remap, no
 * magnetometer, no gimbal edge cases.
 *
 * Lifecycle: [start] on overlay attach (a ride is live and the
 * turn-aware-alerting flag is on), [stop] on detach. Listeners are only
 * registered in between, so the gyroscope costs nothing when the feature
 * is off or the app is idle. Both sensors deliver on the main thread; the
 * decider is single-thread by contract and [state] publishes the result
 * across threads via a volatile.
 *
 * Timestamps: the decider integrates rate over time, so samples carry
 * [SensorEvent.timestamp] (elapsedRealtimeNanos at the moment the sample
 * was TAKEN) rather than the delivery time - sensor batching can deliver
 * several samples in one burst and integrating with delivery times would
 * squash their spacing.
 */
class TurnSensorController(
    private val sensorManager: SensorManager?,
    private val clog: (String) -> Unit = {},
    private val decider: TurnStateDecider = TurnStateDecider(),
    /** Monotonic clock on the same base as [SensorEvent.timestamp]
     *  (elapsedRealtime), consumed by [state]'s staleness watchdog. */
    private val clockMono: () -> Long = { SystemClock.elapsedRealtime() },
) : SensorEventListener {

    private var started = false
    private var gravityUnit: FloatArray? = null
    private var lastState = TurnStateDecider.State.IDLE

    @Volatile private var stateNow = TurnStateDecider.State.IDLE

    @Volatile private var lastSampleAtMs = Long.MIN_VALUE

    /** One-shot latch for the staleness override, so a sensor stall is
     *  recorded in the capture log exactly once per stall instead of once
     *  per query (state() is polled per radar frame) - a post-ride
     *  reviewer must be able to tell "rider straightened" from "sensor
     *  went stale" when a deferral ends. Reset on the next real sample. */
    @Volatile private var staleLogged = false

    /** The rider's current turn state. Safe to call from any thread.
     *  Always [TurnStateDecider.State.IDLE] while stopped.
     *
     *  Staleness watchdog: the state only advances inside sensor
     *  callbacks, so if gyro delivery pauses mid-turn (non-wakeup sensor
     *  during a screen-off window) a TURNING reading would otherwise
     *  freeze and defer the all-clear until samples resume. A last
     *  sample older than [SENSOR_STALE_MS] therefore reads as IDLE;
     *  normal clear semantics resume, and the state recovers on the
     *  next real sample. */
    fun state(): TurnStateDecider.State {
        val s = stateNow
        if (s == TurnStateDecider.State.IDLE) return s
        return if (clockMono() - lastSampleAtMs > SENSOR_STALE_MS) {
            if (!staleLogged) {
                staleLogged = true
                clog("# turn sensor stale, was=$s -> IDLE")
            }
            TurnStateDecider.State.IDLE
        } else {
            s
        }
    }

    fun start() {
        if (started) return
        val sm = sensorManager ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gyro == null || gravity == null) {
            clog("# turn sensors unavailable gyro=${gyro != null} gravity=${gravity != null}")
            return
        }
        sm.registerListener(this, gravity, SensorManager.SENSOR_DELAY_UI)
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_UI)
        started = true
    }

    fun stop() {
        if (!started) return
        sensorManager?.unregisterListener(this)
        started = false
        gravityUnit = null
        decider.reset()
        stateNow = TurnStateDecider.State.IDLE
        lastState = TurnStateDecider.State.IDLE
        lastSampleAtMs = Long.MIN_VALUE
        staleLogged = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravityUnit = normalize(event.values, gravityUnit)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val g = gravityUnit ?: return
                val nowMs = event.timestamp / 1_000_000
                decider.onYawSample(yawRateAboutGravity(event.values, g), nowMs)
                lastSampleAtMs = nowMs
                staleLogged = false
                val state = decider.stateAt(nowMs)
                stateNow = state
                if (state != lastState) {
                    clog("# turn state=$state")
                    lastState = state
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Age (ms) past which the last gyro sample is considered stale
         *  and [state] reads IDLE regardless of the frozen decider state.
         *  Well above the SENSOR_DELAY_UI cadence (~60 ms) and any
         *  batching burst; well below the shortest deferral it guards
         *  (TURN_TAIL_MIN_MS = 3 s). */
        const val SENSOR_STALE_MS = 1_000L

        /** Rotation rate (rad/s) about the world-vertical axis: the
         *  gyroscope vector projected onto the gravity unit vector. */
        internal fun yawRateAboutGravity(gyro: FloatArray, gravityUnit: FloatArray): Float = gyro[0] * gravityUnit[0] + gyro[1] * gravityUnit[1] + gyro[2] * gravityUnit[2]

        /** Normalised copy of [v]; returns [fallback] when the magnitude is
         *  degenerate (free-fall or a zeroed sample). */
        internal fun normalize(v: FloatArray, fallback: FloatArray?): FloatArray? {
            val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (mag < 1f) return fallback
            return floatArrayOf(v[0] / mag, v[1] / mag, v[2] / mag)
        }
    }
}
