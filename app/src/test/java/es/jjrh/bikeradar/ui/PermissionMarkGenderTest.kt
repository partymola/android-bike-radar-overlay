// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
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
 * Each Recommended mark agrees with its permission in Spanish: "Recomendadas"
 * beside las Notificaciones. English uses one word for both keys, so only
 * Spanish can see a card given the other's. Literals, read through the
 * production [PERMISSIONS], as in [PairedChipGenderTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+es")
class PermissionMarkGenderTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun markFor(titleRes: Int): String = app.getString(checkNotNull(PERMISSIONS.single { it.titleRes == titleRes }.markLabelRes))

    @Test
    fun `each recommended mark agrees with its permission`() {
        assertEquals("Recomendadas", markFor(R.string.permission_notifications_title))
        assertEquals("Recomendado", markFor(R.string.permission_overlay_title))
    }
}
