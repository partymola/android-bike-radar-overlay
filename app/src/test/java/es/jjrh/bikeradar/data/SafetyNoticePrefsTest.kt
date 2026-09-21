// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The default is the whole feature for an upgrading rider.
 *
 * An install from a version that never wrote this key has no value stored, so
 * the default is what decides whether that rider ever sees the notice. A
 * default of true would skip every existing install silently, which is the
 * one outcome no screen and no log would report.
 */
@RunWith(RobolectricTestRunner::class)
class SafetyNoticePrefsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPrefs() {
        app.getSharedPreferences("bike_radar_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun anInstallThatNeverWroteTheKeyHasNotAcknowledged() {
        assertFalse(Prefs(app).safetyNoticeAcknowledged)
    }

    @Test
    fun theAcknowledgementSurvivesANewPrefsInstance() {
        Prefs(app).safetyNoticeAcknowledged = true
        assertTrue("a new process must not re-ask", Prefs(app).safetyNoticeAcknowledged)
    }
}
