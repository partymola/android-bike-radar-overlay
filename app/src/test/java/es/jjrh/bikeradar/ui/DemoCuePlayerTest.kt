// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.jjrh.bikeradar.AlertBeeper
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/** The demo's player builds one beeper at the first sound, set up as the ride's own. */
@RunWith(AndroidJUnit4::class)
class DemoCuePlayerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(app)
    private val audio = app.getSystemService(AudioManager::class.java)

    @After
    fun clearPrefs() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun aScreenNobodyPlaysBuildsNoBeeper() {
        var built = 0
        val player = DemoCuePlayer {
            built += 1
            null
        }
        player.release()
        assertEquals(0, built)
    }

    @Test
    fun aReleasedPlayerBuildsNothingLater() {
        var built = 0
        val player = DemoCuePlayer {
            built += 1
            null
        }
        player.release()
        player.play(1)
        assertEquals(0, built)
    }

    @Test
    fun everySoundSharesTheOneBeeperBuiltAtTheFirst() {
        var built = 0
        val player = DemoCuePlayer {
            built += 1
            null
        }
        player.play(1)
        player.playClear()
        player.playUrgent()
        player.playRadarDropped()
        player.playRadarReconnected()
        assertEquals(1, built)
    }

    @Test
    fun eachSoundReachesItsOwnCue() {
        audio.mode = AudioManager.MODE_NORMAL
        val heard = mutableListOf<String>()
        val player = DemoCuePlayer {
            AlertBeeper(audioManager = audio, executor = { it.run() }, playTrackOverride = { true }, onCue = { heard += it })
        }
        player.play(1)
        player.play(2)
        player.play(3)
        player.playClear()
        player.playUrgent()
        player.playRadarDropped()
        player.playRadarReconnected()
        assertEquals(
            listOf("beep count=1", "beep count=2", "beep count=3", "clear", "urgent", "radar_drop", "radar_reconnect"),
            heard,
        )
        player.release()
    }

    @Test
    fun releasingThePlayerReleasesItsBeeper() {
        val executor = Executors.newSingleThreadExecutor()
        val player = DemoCuePlayer { newDemoBeeper(app, prefs, executor) }
        player.play(1)
        player.release()
        assertTrue(executor.isShutdown)
    }

    @Test
    fun aVolumeSetBeforeAnySoundBuildsNothing() {
        var built = 0
        val player = DemoCuePlayer {
            built += 1
            null
        }
        player.setVolumePct(80)
        assertEquals(0, built)
    }

    @Test
    fun aVolumeSetAfterASoundReachesTheBeeperAlreadyBuilt() {
        prefs.alertVolume = 50
        val beeper = newDemoBeeper(app, prefs, executor = { it.run() })!!
        val player = DemoCuePlayer { beeper }
        player.play(1)
        player.setVolumePct(80)
        assertEquals(80, beeper.currentVolumePct)
        player.release()
    }

    @Test
    fun theDemoPlaysAtTheRidersAlertVolume() {
        prefs.alertVolume = 37
        val beeper = newDemoBeeper(app, prefs, executor = { it.run() })!!
        assertEquals(37, beeper.currentVolumePct)
        beeper.release()
    }

    @Test
    fun theDemoLeavesTheSharedSlotToTheRideBeeperThatMayHoldALift() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        prefs.alertBeeperSavedAlarmVolume = 2
        val beeper = newDemoBeeper(app, prefs, executor = { it.run() })!!
        assertEquals(max, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(2, prefs.alertBeeperSavedAlarmVolume)
        beeper.release()
    }

    @Test
    fun theDemoKeepsTheRidersLevelInTheSharedSlotWhileItLifts() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val beeper = newDemoBeeper(app, prefs, executor = { it.run() })!!
        beeper.play(1)
        assertEquals(1, prefs.alertBeeperSavedAlarmVolume)
        beeper.release()
        assertNull(prefs.alertBeeperSavedAlarmVolume)
    }

    @Test
    fun theDemoTakesTheRidersLevelFromALiftTheRideAlreadyHolds() {
        // The ride's beeper lifted 1 to 6 for mid media and saved 1; media then rose.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 6, 0)
        prefs.alertBeeperSavedAlarmVolume = 1
        val beeper = newDemoBeeper(app, prefs, executor = { it.run() })!!
        beeper.play(1)
        assertEquals(1, prefs.alertBeeperSavedAlarmVolume)
        beeper.release()
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }
}
