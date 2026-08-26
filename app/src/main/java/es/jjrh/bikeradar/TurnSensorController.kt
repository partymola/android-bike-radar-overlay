// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.util.Locale
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
 *
 * Capture-log lines: `# turn yaw ts_mono=<ms> rate=<rad/s> cum_deg=<deg>`,
 * emitted while a rotation episode runs. A sampled series needs its own
 * time base - a `#` line carries no timestamp prefix of its own, so
 * without one its only time information is its position between packet
 * lines written from other threads.
 *
 * The field is `ts_mono`, not `ts`, because `# alert ts=` already means
 * wall clock: these are elapsedRealtime, the sensor's own base, and the
 * two must not be read as one series. The header's
 * `# clock unix_ms=.. mono_ms=..` line samples both at one instant, which
 * converts between the bases without correlation for as long as the wall
 * clock is not stepped - see
 * [CaptureLogManager.anchorLine]. Captures written before that line
 * existed carry no anchor and have to be aligned against the surrounding
 * packet lines, which is an estimate with a spread.
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

    private var lastYawLogMs = 0L

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
        lastYawLogMs = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravityUnit = normalize(event.values, gravityUnit)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val g = gravityUnit ?: return
                val nowMs = event.timestamp / 1_000_000
                val yaw = yawRateAboutGravity(event.values, g)
                decider.onYawSample(yaw, nowMs)
                lastSampleAtMs = nowMs
                staleLogged = false
                val state = decider.stateAt(nowMs)
                stateNow = state
                if (state != lastState) {
                    clog(
                        when (state) {
                            TurnStateDecider.State.TURNING -> "# turn state=$state cum_deg=${fmt(decider.cumulativeDeg)}"
                            TurnStateDecider.State.HOLD -> "# turn state=$state total_deg=${fmt(decider.lastEpisodeDeg)}"
                            TurnStateDecider.State.IDLE -> "# turn state=$state"
                        },
                    )
                    lastState = state
                }
                if (decider.episodeActive && nowMs - lastYawLogMs >= YAW_LOG_INTERVAL_MS) {
                    lastYawLogMs = nowMs
                    clog(
                        "# turn yaw ts_mono=$nowMs rate=${fmt(yaw)}" +
                            " cum_deg=${fmt(decider.cumulativeDeg)}",
                    )
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

        /** Minimum gap (ms) between logged yaw samples; the gyro itself
         *  delivers at roughly 60 ms.
         *
         *  Sampling is gated on [TurnStateDecider.episodeActive] rather
         *  than on TURNING. TURNING starts only once the qualifying angle
         *  has accumulated, which would leave a corner's entry untraced -
         *  and that is where the radar starts sweeping tracks off. It
         *  would also miss rotation during the post-turn HOLD window,
         *  where an unqualified episode reads as HOLD. */
        private const val YAW_LOG_INTERVAL_MS = 200L

        /** Capture-log number format: fixed 3 decimals, never scientific
         *  notation. `Float.toString` switches to E-notation below 1e-3,
         *  which the decaying rates at the end of every episode reach
         *  routinely.
         *
         *  `Locale.ROOT` is load-bearing, not decoration: the default
         *  locale formats a decimal point as a comma in most of Europe,
         *  which would split every value across the log's space-separated
         *  `key=value` fields and break any parser. Do not drop it. */
        internal fun fmt(v: Float): String = String.format(Locale.ROOT, "%.3f", v)

        /** Rotation rate (rad/s) about the world-vertical axis: the
         *  gyroscope vector projected onto the gravity unit vector.
         *
         *  That the result is a projection, and that its sign survives
         *  integration, are pinned by `yawRateIsGyroProjectedOnGravity`
         *  and `lastEpisodeDegIsNegativeForTheOtherDirection`.
         *
         *  Which real-world direction each sign means is NOT pinned by
         *  anything here - it follows from two Android conventions, so a
         *  test in this repo can only restate it. Android's gravity sensor
         *  reports the vector pointing AWAY from the earth (+9.81 on z
         *  with the device flat, screen up), and its gyroscope is
         *  counter-clockwise-positive about the device axes. Counter-
         *  clockwise seen from above is a left turn, so POSITIVE is a left
         *  turn.
         *
         *  Checked on the road rather than only derived: the outbound and
         *  return legs of one commute through the same junction, taken in
         *  opposite directions, produced opposite-signed episodes with the
         *  left turn positive. That is field evidence from a single day,
         *  and it settles the SIGN only - the magnitude is integrated
         *  steering, not heading change, so a roughly 90-degree junction
         *  reported about 175 and about -196 degrees on the two legs. Do
         *  not read a completed episode's total as an angle turned. */
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
