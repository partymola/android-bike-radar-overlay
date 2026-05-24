// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.Prefs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the [DevModeState] load/unlock/lock cycle against a real
 * SharedPreferences-backed [Prefs]. DevModeState is a process-global
 * singleton StateFlow that gates the hidden debug surface, so both the
 * in-memory flow value and the persisted Prefs flag must stay in lockstep.
 */
@RunWith(RobolectricTestRunner::class)
class DevModeStateTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        // DevModeState is a singleton; reset its flow to a known state so
        // test ordering can't leak an unlock into the next test.
        DevModeState.lock(Prefs(app))
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        DevModeState.lock(Prefs(app))
    }

    @Test
    fun loadFromReflectsPersistedFalse() {
        val prefs = Prefs(app)
        DevModeState.unlocked // touch
        DevModeState.loadFrom(prefs)
        assertFalse(DevModeState.unlocked.value)
    }

    @Test
    fun loadFromReflectsPersistedTrue() {
        val prefs = Prefs(app)
        prefs.devModeUnlocked = true
        DevModeState.loadFrom(prefs)
        assertTrue("loadFrom must surface the persisted unlocked flag", DevModeState.unlocked.value)
    }

    @Test
    fun unlockSetsFlowAndPersists() {
        val prefs = Prefs(app)
        DevModeState.unlock(prefs)
        assertTrue("unlock flips the flow", DevModeState.unlocked.value)
        assertTrue("unlock persists to Prefs", prefs.devModeUnlocked)
        // A fresh Prefs over the same store sees the persisted value.
        assertTrue("persisted across a new Prefs handle", Prefs(app).devModeUnlocked)
    }

    @Test
    fun lockClearsFlowAndPersists() {
        val prefs = Prefs(app)
        DevModeState.unlock(prefs)
        DevModeState.lock(prefs)
        assertFalse("lock flips the flow back", DevModeState.unlocked.value)
        assertFalse("lock persists the cleared flag", prefs.devModeUnlocked)
        assertFalse("cleared value survives a new Prefs handle", Prefs(app).devModeUnlocked)
    }
}
