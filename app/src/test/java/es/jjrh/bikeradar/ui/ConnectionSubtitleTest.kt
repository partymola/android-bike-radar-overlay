// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Settings Connections row answers two questions in one line: is this
 * device set up, and is it working right now.
 *
 * Asserted as literals rather than through a golden. A golden would go red on
 * this too, but it cannot say WHY, and it cannot tell a swapped argument order
 * from a reworded string. It also renders one locale, and the Spanish half is
 * where the interesting failure lives: the two halves must not collapse into
 * the same adjective.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionSubtitleTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `set up and working reads set-up first`() {
        assertEquals(
            "Paired · No signal",
            connectionSubtitle(
                app,
                setUp = R.string.settings_home_conn_paired,
                status = R.string.device_status_no_signal,
            ),
        )
    }

    @Test
    fun `not set up is the whole answer`() {
        // No second half: there is no link to describe yet, and "Not paired ·
        // No signal" would be answering a question the rider has not reached.
        assertEquals(
            "Not paired",
            connectionSubtitle(app, setUp = null, status = R.string.device_status_not_paired),
        )
    }

    @Test
    fun `the ebike row names the feature, not the bike`() {
        // "On · Live" in English survives a set-up word that duplicates the
        // status word; Spanish does not, which is what the next test pins.
        assertEquals(
            "Data on · Live",
            connectionSubtitle(
                app,
                setUp = R.string.settings_home_ebike_on,
                status = R.string.device_status_ebike_live,
            ),
        )
    }

    @Config(qualifiers = "+es")
    @Test
    fun `the two halves never collapse into one word in Spanish`() {
        // "Activada · Activa" is what a set-up word borrowed from the feature
        // toggle produced, and it reads as a stutter or a bug. The fix is a
        // set-up word naming the DATA, so the halves stay distinguishable.
        assertEquals(
            "Datos activados · Activa",
            connectionSubtitle(
                app,
                setUp = R.string.settings_home_ebike_on,
                status = R.string.device_status_ebike_live,
            ),
        )
    }

    @Config(qualifiers = "+es")
    @Test
    fun `Spanish inflects the set-up word for the device it describes`() {
        // El radar takes the masculine, la cámara the feminine. English
        // collapses both to "Paired", so nothing in the en strings or the en
        // goldens can catch the two being swapped.
        assertEquals(
            "Emparejado · Sin señal",
            connectionSubtitle(
                app,
                setUp = R.string.settings_home_conn_paired,
                status = R.string.device_status_no_signal,
            ),
        )
        assertEquals(
            "Emparejada · Sin señal",
            connectionSubtitle(
                app,
                setUp = R.string.settings_home_conn_paired_cam,
                status = R.string.device_status_no_signal,
            ),
        )
    }
}
