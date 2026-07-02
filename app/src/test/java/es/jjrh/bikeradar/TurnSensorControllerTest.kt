// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/**
 * Pins [TurnSensorController]: yaw is the gyro projected on the gravity
 * unit vector (mount-orientation independent), the published state follows
 * the decider through a synthetic corner (TURNING -> HOLD -> IDLE),
 * missing sensors degrade to a no-op, and stop() resets the published
 * state.
 */
@RunWith(RobolectricTestRunner::class)
class TurnSensorControllerTest {

    private fun sensorManager(withSensors: Boolean = true): SensorManager {
        val sm = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (withSensors) {
            val shadow: ShadowSensorManager = shadowOf(sm)
            shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
            shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GRAVITY))
        }
        return sm
    }

    private fun event(sm: SensorManager, type: Int, values: FloatArray, tNs: Long) = SensorEventBuilder.newBuilder()
        .setSensor(requireNotNull(sm.getDefaultSensor(type)))
        .setTimestamp(tNs)
        .setValues(values)
        .build()

    @Test
    fun yawRateIsGyroProjectedOnGravity() {
        // Phone upright in a handlebar mount: gravity along device -Y.
        // Rotation about world-vertical then lives on the device Y gyro
        // axis; X/Z rates (road vibration, pitch) must not leak in.
        val g = floatArrayOf(0f, -1f, 0f)
        assertEquals(
            -0.5f,
            TurnSensorController.yawRateAboutGravity(floatArrayOf(0.2f, 0.5f, 0.1f), g),
            1e-6f,
        )
        // Phone flat: gravity along +Z, yaw is the Z rate.
        val flat = floatArrayOf(0f, 0f, 1f)
        assertEquals(
            0.5f,
            TurnSensorController.yawRateAboutGravity(floatArrayOf(0.2f, 0.1f, 0.5f), flat),
            1e-6f,
        )
    }

    @Test
    fun normalizeRejectsFreeFallAndKeepsFallback() {
        val prev = floatArrayOf(0f, 0f, 1f)
        assertNull(TurnSensorController.normalize(floatArrayOf(0.1f, 0.1f, 0.1f), null))
        assertEquals(prev, TurnSensorController.normalize(floatArrayOf(0f, 0f, 0f), prev))
        val n = TurnSensorController.normalize(floatArrayOf(0f, 0f, 9.81f), null)!!
        assertEquals(1f, n[2], 1e-4f)
    }

    @Test
    fun syntheticCornerDrivesTheStateThroughTurningHoldIdle() {
        val sm = sensorManager()
        val shadow = shadowOf(sm)
        val c = TurnSensorController(sm)
        c.start()
        assertTrue(shadow.hasListener(c))
        var tNs = 1_000_000_000L
        shadow.sendSensorEventToListeners(
            event(sm, Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9.81f), tNs),
        )
        // ~29 deg/s about world-vertical for 3.2 s = a 92-degree corner.
        repeat(64) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0.5f), tNs),
            )
        }
        assertEquals(TurnStateDecider.State.TURNING, c.state())
        // Rotation stops; the state moves to the post-turn HOLD window.
        repeat(20) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0f), tNs),
            )
        }
        assertEquals(TurnStateDecider.State.HOLD, c.state())
        // ...and returns to IDLE after HOLD_MS.
        tNs += (TurnStateDecider.HOLD_MS + 1_000) * 1_000_000L
        shadow.sendSensorEventToListeners(
            event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0f), tNs),
        )
        assertEquals(TurnStateDecider.State.IDLE, c.state())
        c.stop()
        assertFalse(shadow.hasListener(c))
    }

    @Test
    fun gyroBeforeAnyGravitySampleIsIgnored() {
        val sm = sensorManager()
        val shadow = shadowOf(sm)
        val c = TurnSensorController(sm)
        c.start()
        var tNs = 1_000_000_000L
        repeat(80) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0.5f), tNs),
            )
        }
        assertEquals(TurnStateDecider.State.IDLE, c.state())
    }

    @Test
    fun missingSensorsDegradeToNoOp() {
        val sm = sensorManager(withSensors = false)
        var logged: String? = null
        val c = TurnSensorController(sm, clog = { logged = it })
        c.start()
        assertFalse(shadowOf(sm).hasListener(c))
        assertEquals(TurnStateDecider.State.IDLE, c.state())
        assertTrue(logged!!.startsWith("# turn sensors unavailable"))
    }

    @Test
    fun staleSensorStreamReadsIdleAndRecoversOnNextSample() {
        val sm = sensorManager()
        val shadow = shadowOf(sm)
        var fakeNowMs = 1_000L
        val c = TurnSensorController(sm, clockMono = { fakeNowMs })
        c.start()
        var tNs = 1_000_000_000L
        shadow.sendSensorEventToListeners(
            event(sm, Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9.81f), tNs),
        )
        repeat(64) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0.5f), tNs),
            )
        }
        fakeNowMs = tNs / 1_000_000
        assertEquals(TurnStateDecider.State.TURNING, c.state())
        // Gyro delivery pauses mid-turn (screen-off Doze): once the last
        // sample is stale the frozen TURNING must read as IDLE so the
        // all-clear cannot be deferred indefinitely.
        fakeNowMs += TurnSensorController.SENSOR_STALE_MS + 500
        assertEquals(TurnStateDecider.State.IDLE, c.state())
        // Delivery resumes mid-corner: the state recovers immediately.
        tNs = fakeNowMs * 1_000_000
        shadow.sendSensorEventToListeners(
            event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0.5f), tNs),
        )
        assertEquals(TurnStateDecider.State.TURNING, c.state())
    }

    @Test
    fun stopResetsThePublishedState() {
        val sm = sensorManager()
        val shadow = shadowOf(sm)
        val c = TurnSensorController(sm)
        c.start()
        var tNs = 1_000_000_000L
        shadow.sendSensorEventToListeners(
            event(sm, Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9.81f), tNs),
        )
        repeat(64) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0.5f), tNs),
            )
        }
        assertEquals(TurnStateDecider.State.TURNING, c.state())
        c.stop()
        assertEquals(TurnStateDecider.State.IDLE, c.state())
    }
}
