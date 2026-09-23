// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Privacy screen says the rear radar is sent the phone's make and model, in
 * its opening line and on its Bluetooth card, in both locales. The opening line
 * otherwise promises the data goes only to Home Assistant and allowed apps. The
 * sending end is pinned on the wire by
 * `EnablingSequenceHarnessTest.theRadarIsSentThePhonesMakeAndModel` and
 * `theFrontCameraIsNeverSentThePhonesMakeOrModel`.
 */
@RunWith(RobolectricTestRunner::class)
class HandshakeDeviceIdIsDisclosedTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theEnglishBluetoothCardSaysSo() {
        val body = app.getString(R.string.settings_privacy_bluetooth_body)
        for (phrase in listOf("rear radar", "make and model", "Google Pixel 10")) {
            assertTrue("the Bluetooth disclosure does not mention $phrase: $body", body.contains(phrase))
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun theSpanishBluetoothCardSaysSoToo() {
        val body = app.getString(R.string.settings_privacy_bluetooth_body)
        for (phrase in listOf("radar trasero", "marca y el modelo", "Google Pixel 10")) {
            assertTrue("la copia de Bluetooth no menciona $phrase: $body", body.contains(phrase))
        }
    }

    @Test
    fun theEnglishOpeningLineNamesTheRadar() {
        val intro = app.getString(R.string.settings_privacy_intro)
        for (phrase in listOf("rear radar", "make and model")) {
            assertTrue("the Privacy intro does not mention $phrase: $intro", intro.contains(phrase))
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun theSpanishOpeningLineNamesTheRadarToo() {
        val intro = app.getString(R.string.settings_privacy_intro)
        for (phrase in listOf("radar trasero", "marca y el modelo")) {
            assertTrue("la introducción de Privacidad no menciona $phrase: $intro", intro.contains(phrase))
        }
    }
}
