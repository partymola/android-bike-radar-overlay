// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/** [CloseOnce]: a battery read's GATT client is closed exactly once, however
 *  the close and `connectGatt` returning it are ordered. */
class CloseOnceTest {

    private val closed = mutableListOf<String>()
    private val once = CloseOnce<String> { closed += it }

    @Test
    fun aCloseBeforeTheClientIsKnownClosesItWhenItIs() {
        once.close()
        assertEquals(emptyList<String>(), closed)
        once.attach("gatt")
        assertEquals(listOf("gatt"), closed)
    }

    @Test
    fun aCloseAfterTheClientIsKnownClosesItOnce() {
        once.attach("gatt")
        assertEquals(emptyList<String>(), closed)
        once.close()
        once.close()
        assertEquals(listOf("gatt"), closed)
    }

    @Test
    fun aClientNeverClosedStaysOpen() {
        once.attach("gatt")
        assertEquals(emptyList<String>(), closed)
    }
}
