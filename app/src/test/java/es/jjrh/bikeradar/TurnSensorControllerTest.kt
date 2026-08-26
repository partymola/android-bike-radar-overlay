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
import java.util.Locale

/**
 * Pins [TurnSensorController]: yaw is the gyro projected on the gravity
 * unit vector (mount-orientation independent), the published state follows
 * the decider through a synthetic corner (TURNING -> HOLD -> IDLE),
 * missing sensors degrade to a no-op, and stop() resets the published
 * state.
 *
 * The capture-log tests pin the trace by VALUE, not by shape: the sample
 * clock, the running angle, the fixed-3-decimal format on both the yaw and
 * the transition lines, and the exact line counts either side of TURNING.
 * A pattern that accepts any digits is satisfied by a constant, and a count
 * asserted as "more than none" is satisfied by one - both leave a log a
 * post-ride reviewer reads as measurement free to drift.
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
        // Phone upright in a handlebar mount. Device +Y points at the sky,
        // and the gravity SENSOR reports the vector pointing away from the
        // earth, so it reads +Y - not the direction gravity pulls.
        // Rotation about world-vertical then lives on the device Y gyro
        // axis; X/Z rates (road vibration, pitch) must not leak in.
        val g = floatArrayOf(0f, 1f, 0f)
        assertEquals(
            0.5f,
            TurnSensorController.yawRateAboutGravity(floatArrayOf(0.2f, 0.5f, 0.1f), g),
            1e-6f,
        )
        // Mount inverted: the same device-frame rates project to the
        // opposite sign. That is what MAKES the reading mount-independent
        // rather than contradicting it - a physically inverted phone also
        // reports flipped gyro axes, and the two flips cancel.
        assertEquals(
            -0.5f,
            TurnSensorController.yawRateAboutGravity(floatArrayOf(0.2f, 0.5f, 0.1f), floatArrayOf(0f, -1f, 0f)),
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

    /** Drive a corner of [samples] gyro steps at [rate] rad/s, 50 ms
     *  apart, then quiet the rotation until the episode closes. Returns
     *  every line the controller logged. */
    private fun cornerLog(rate: Float, samples: Int, quietSamples: Int = 20): List<String> {
        val sm = sensorManager()
        val shadow = shadowOf(sm)
        val lines = mutableListOf<String>()
        val c = TurnSensorController(sm, clog = { lines += it })
        c.start()
        var tNs = 1_000_000_000L
        shadow.sendSensorEventToListeners(
            event(sm, Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9.81f), tNs),
        )
        repeat(samples) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, rate), tNs),
            )
        }
        repeat(quietSamples) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0f), tNs),
            )
        }
        // Unregister before returning: the SensorManager is per-test, not
        // per-call, so a controller left listening also receives the events
        // of every later cornerLog in the same test.
        c.stop()
        return lines
    }

    @Test
    fun yawLinesCarryTheMonotonicSampleClock() {
        // cornerLog stamps gravity at 1000 ms and steps the gyro 50 ms per
        // sample, so at the 200 ms throttle the first three lines land at
        // 1050 / 1250 / 1450. Asserting the values rather than the shape:
        // a `ts_mono=\d+` pattern is equally satisfied by a constant 0 and
        // by a wall-clock reading, and the `_mono` suffix is a promise
        // nothing else in the capture can check - the file carries no
        // anchor between this base and the `unix_ms` packet prefixes.
        val stamps = cornerLog(rate = 0.5f, samples = 120)
            .filter { it.startsWith("# turn yaw ") }
            .take(3)
            .map { it.substringAfter("ts_mono=").substringBefore(' ').toLong() }
        assertEquals(listOf(1050L, 1250L, 1450L), stamps)
    }

    @Test
    fun rotationBelowTheEpisodeFloorIsNotTraced() {
        // 0.02 rad/s (~1.1 deg/s) is steering wobble: it never opens an
        // episode, so the trace stays empty for the whole 7 s.
        //
        // The suite already held this property, but by one line - 35
        // against an ungated 34 - and only because cornerLog defaults to
        // 20 quiet samples. At quietSamples 10, 12 or 14 the ungated
        // version passes, so a future edit to that default would retire
        // the coverage with nothing going red.
        val lines = cornerLog(rate = 0.02f, samples = 120)
        assertTrue(lines.toString(), lines.none { it.startsWith("# turn yaw ") })
    }

    @Test
    fun captureNumbersUseADecimalPointInACommaDecimalLocale() {
        // The app ships a Spanish translation, so comma-decimal locales
        // are a supported configuration: there the default formatter
        // renders 0.5 as "0,5", which would split a value across the log's
        // space-separated key=value fields and break every parser. The
        // suite's own en-US default cannot expose it, so force the locale.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            assertEquals("0.500", TurnSensorController.fmt(0.5f))
            assertEquals("-170.455", TurnSensorController.fmt(-170.4551f))
            // Float.toString would render this as "8.0E-4"; the decaying
            // rates at the end of every episode reach this range routinely.
            assertEquals("0.001", TurnSensorController.fmt(8.0e-4f))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun yawSamplesAreThrottledWellBelowTheSensorRate() {
        // 120 samples at 50 ms = 6 s of rotation at 0.5 rad/s, plus the
        // 700 ms quiet-end before the episode closes. The episode is live
        // from the opening sample, so 133 of the 140 samples fall inside
        // it; at the 200 ms throttle that is 34 lines, not 133.
        // Deterministic - shadow sensors on fixed timestamps - so this is
        // an equality, not a range. It is coarser than it looks even so:
        // the throttle quantises to whole 50 ms samples, so any interval
        // in (150, 200] ms yields the same count.
        val yaw = cornerLog(rate = 0.5f, samples = 120).filter { it.startsWith("# turn yaw ") }
        assertEquals(34, yaw.size)
    }

    @Test
    fun theCornerEntryIsTracedBeforeTheTurnQualifies() {
        // The hole this gate exists to close. TURNING is only reached once
        // 60 degrees have accumulated - declared at the 43rd sample,
        // 3150 ms in at this rate - and the radar starts sweeping tracks
        // off well before that, so a trace that began at TURNING would
        // miss the entry entirely.
        //
        // Counted, not merely ordered: `firstYaw < turning` is satisfied by
        // one line arriving a single sample early, which is not what "the
        // entry is traced" means. At the 200 ms throttle, 11 precede it.
        val lines = cornerLog(rate = 0.5f, samples = 120)
        val turning = lines.indexOfFirst { it.startsWith("# turn state=TURNING") }
        val yawBefore = lines.take(turning).count { it.startsWith("# turn yaw ") }
        assertEquals(11, yawBefore)
    }

    @Test
    fun rotationDuringThePostTurnHoldWindowIsTraced() {
        // A second junction phase right after a corner opens a fresh
        // episode that never reaches 60 degrees, so stateAt reports HOLD
        // throughout. Gating on TURNING would drop it; gating on the
        // episode keeps it.
        val sm = sensorManager()
        val shadow = shadowOf(sm)
        val lines = mutableListOf<String>()
        val c = TurnSensorController(sm, clog = { lines += it })
        c.start()
        var tNs = 1_000_000_000L
        shadow.sendSensorEventToListeners(
            event(sm, Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9.81f), tNs),
        )
        fun feed(rate: Float, n: Int) = repeat(n) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, rate), tNs),
            )
        }
        feed(0.5f, 120)
        feed(0f, 20)
        assertEquals(TurnStateDecider.State.HOLD, c.state())
        val before = lines.count { it.startsWith("# turn yaw ") }
        // 30 degrees of fresh rotation: above the rate floor, below the
        // qualifying angle.
        feed(0.3f, 35)
        assertEquals(TurnStateDecider.State.HOLD, c.state())
        // The count is deterministic - shadow sensors on fixed timestamps -
        // so assert it. `> before` is satisfied by one line, which would
        // still pass if the throttle or the gate dropped almost all of the
        // HOLD-window trace.
        assertEquals(9, lines.count { it.startsWith("# turn yaw ") } - before)
    }

    @Test
    fun aQualifyingEpisodeInsideTheHoldWindowReportsTurning() {
        // episodeActive's KDoc says a HOLD-window episode that DOES reach
        // the angle reports TURNING from that sample, like any other -
        // stateAt orders `inEpisode && qualified` ahead of the hold branch.
        // Nothing drove that: the HOLD-window test only ever feeds 29
        // degrees, so the parenthetical was documentation with no test
        // behind it, on the branch that decides whether a second corner
        // taken straight out of a first one defers the all-clear.
        val sm = sensorManager()
        val shadow = shadowOf(sm)
        val c = TurnSensorController(sm)
        c.start()
        var tNs = 1_000_000_000L
        shadow.sendSensorEventToListeners(
            event(sm, Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9.81f), tNs),
        )
        fun feed(rate: Float, n: Int) = repeat(n) {
            tNs += 50_000_000L
            shadow.sendSensorEventToListeners(
                event(sm, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, rate), tNs),
            )
        }
        feed(0.5f, 120)
        feed(0f, 20)
        assertEquals(TurnStateDecider.State.HOLD, c.state())
        // A fresh episode inside the window. Its opening sample only
        // starts it, so 42 integrations of 0.025 rad reach 60.16 degrees:
        // one short of that the state is still the hold.
        feed(0.5f, 42)
        assertEquals(TurnStateDecider.State.HOLD, c.state())
        feed(0.5f, 1)
        assertEquals(TurnStateDecider.State.TURNING, c.state())
        c.stop()
    }

    @Test
    fun yawLinesCarryTheSignedRateAndRunningAngle() {
        // Named by sign, not by side. The sign-to-direction mapping rests
        // on Android's own conventions plus a road check (see
        // yawRateAboutGravity); neither is something a test in this repo
        // can establish, so naming these left and right would dress up a
        // restatement as an assertion.
        // Read the SECOND line, not the first: the sample that opens an
        // episode returns before integrating, so the first line always
        // reports cum_deg=0.000 and could not show a sign either way.
        // The angle is asserted as a VALUE, not as a leading character: a
        // `cum_deg=[0-9]` pattern pins the sign and nothing else, so any
        // scaling of the running angle passes it. Line [1] is stamped
        // 1250 ms, four 50 ms integration steps past the opener, so at
        // 0.5 rad/s it is 4 * 1.432 = 5.73 degrees.
        val positive = cornerLog(rate = 0.5f, samples = 120).filter { it.startsWith("# turn yaw ") }[1]
        assertTrue(positive, Regex("^# turn yaw ts_mono=\\d+ rate=0\\.500 cum_deg=-?\\d+\\.\\d{3}$").matches(positive))
        assertEquals(5.73f, positive.substringAfter("cum_deg=").toFloat(), 0.1f)
        val negative = cornerLog(rate = -0.5f, samples = 120).filter { it.startsWith("# turn yaw ") }[1]
        assertTrue(negative, Regex("^# turn yaw ts_mono=\\d+ rate=-0\\.500 cum_deg=-?\\d+\\.\\d{3}$").matches(negative))
        assertEquals(-5.73f, negative.substringAfter("cum_deg=").toFloat(), 0.1f)
    }

    @Test
    fun turnTransitionsCarryTheAngleAndTheCompletedTotal() {
        val lines = cornerLog(rate = -0.5f, samples = 120)
        // The TURNING line reports the angle that qualified it: the first
        // integration count past the 60-degree threshold, which is 42 at
        // 0.025 rad each = 60.16 degrees. The delta is well under one
        // integration step (1.43 degrees), so this cannot be satisfied by
        // qualifying a sample early or late.
        val turning = lines.first { it.startsWith("# turn state=TURNING") }
        assertEquals(-60.16f, turning.substringAfter("cum_deg=").toFloat(), 0.1f)
        // The HOLD line reports the whole corner: 120 samples less the one
        // that opened the episode, 0.025 rad each = 170.46 degrees.
        val hold = lines.first { it.startsWith("# turn state=HOLD") }
        assertEquals(-170.46f, hold.substringAfter("total_deg=").toFloat(), 0.1f)
        // Both transition lines go through the same fixed-3-decimal
        // formatter as the yaw lines. `toFloat()` above parses a raw
        // `Float.toString` just as happily, so without this the formatter
        // could be dropped here alone and every assertion would still
        // pass - taking the comma-decimal-locale protection with it on
        // the two lines that carry a completed corner's angle.
        assertTrue(turning, Regex("^# turn state=TURNING cum_deg=-\\d+\\.\\d{3}$").matches(turning))
        assertTrue(hold, Regex("^# turn state=HOLD total_deg=-\\d+\\.\\d{3}$").matches(hold))
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
