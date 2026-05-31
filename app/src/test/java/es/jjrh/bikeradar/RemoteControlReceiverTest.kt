// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Smoke tests for RemoteControlReceiver. Adds a Robolectric layer to the
 * existing pure-JVM ControlReceiverActions tests: ensures the actual
 * BroadcastReceiver entrypoint is reachable and the dev-mode gate keeps
 * untrusted broadcasts from spinning up dev services on a release install.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteControlReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val receiver = RemoteControlReceiver()

    @Test
    fun ignoresNullAndUnknownActions() {
        receiver.onReceive(app, Intent())
        receiver.onReceive(app, Intent("totally.unknown"))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun replayActionIsNoOpWhenDevModeLocked() {
        Prefs(app).devModeUnlocked = false
        receiver.onReceive(app, Intent(RemoteControlReceiver.ACTION_DEV_REPLAY))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun synthActionIsNoOpWhenDevModeLocked() {
        Prefs(app).devModeUnlocked = false
        receiver.onReceive(app, Intent(RemoteControlReceiver.ACTION_DEV_SYNTH))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun replayActionStartsDebugOverlayAndReplayWhenDevModeUnlocked() {
        Prefs(app).devModeUnlocked = true
        receiver.onReceive(app, Intent(RemoteControlReceiver.ACTION_DEV_REPLAY))
        val started = drainStartedServices()
        // Must include DebugOverlayService + ReplayService at minimum.
        // SyntheticScenarioService.stopService() is also invoked, but only
        // started services show on this queue.
        assertTrue(started.size >= 2)
        assertTrue(started.any { it.component?.className == DebugOverlayService::class.java.name })
        assertTrue(started.any { it.component?.className == ReplayService::class.java.name })
    }

    @Test
    fun synthActionStartsDebugOverlayAndSynthWhenDevModeUnlocked() {
        Prefs(app).devModeUnlocked = true
        receiver.onReceive(app, Intent(RemoteControlReceiver.ACTION_DEV_SYNTH))
        val started = drainStartedServices()
        assertTrue(started.size >= 2)
        assertTrue(started.any { it.component?.className == DebugOverlayService::class.java.name })
        assertTrue(started.any { it.component?.className == SyntheticScenarioService::class.java.name })
    }

    @Test
    fun radarLightWriteIsNoOpWhenDevModeLocked() {
        Prefs(app).devModeUnlocked = false
        Prefs(app).radarSettingsProbeEnabled = true
        receiver.onReceive(app, Intent(RemoteControlReceiver.ACTION_DEV_RADAR_LIGHT_WRITE))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun radarLightWriteIsNoOpWhenProbeDisabled() {
        // Dev mode alone is not enough: the radar-settings probe toggle must
        // also be on, so a stray broadcast can't poke the radar's control char.
        Prefs(app).devModeUnlocked = true
        Prefs(app).radarSettingsProbeEnabled = false
        receiver.onReceive(app, Intent(RemoteControlReceiver.ACTION_DEV_RADAR_LIGHT_WRITE))
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun radarLightWriteForwardsNnToServiceWhenUnlockedAndProbeOn() {
        Prefs(app).devModeUnlocked = true
        Prefs(app).radarSettingsProbeEnabled = true
        receiver.onReceive(
            app,
            Intent(RemoteControlReceiver.ACTION_DEV_RADAR_LIGHT_WRITE)
                .putExtra(RemoteControlReceiver.EXTRA_NN, 3),
        )
        val started = drainStartedServices()
        val fwd = started.firstOrNull {
            it.component?.className == BikeRadarService::class.java.name &&
                it.action == BikeRadarService.ACTION_RADAR_LIGHT_PROBE_WRITE
        }
        assertTrue("expected a forwarded radar-light write intent", fwd != null)
        assertEquals(3, fwd!!.getIntExtra(BikeRadarService.EXTRA_RADAR_LIGHT_NN, -1))
    }

    private fun drainStartedServices(): List<Intent> {
        val out = mutableListOf<Intent>()
        val s = shadowOf(app)
        while (true) {
            val next: Intent = s.getNextStartedService() ?: break
            out.add(next)
        }
        return out
    }
}
