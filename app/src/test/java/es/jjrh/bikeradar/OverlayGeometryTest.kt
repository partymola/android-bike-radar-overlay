// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun theLeftEdgeIsThePhysicalTopLeft() {
        assertEquals(0x33, OverlayGeometry(edge = OverlayEdge.LEFT).gravity)
    }

    @Test
    fun theRightEdgeIsThePhysicalTopRight() {
        assertEquals(0x35, OverlayGeometry(edge = OverlayEdge.RIGHT).gravity)
    }

    @Test
    fun theDefaultIsTheRightEdgeAt130Dp() {
        assertEquals(OverlayEdge.RIGHT, OverlayGeometry().edge)
        assertEquals(130, OverlayGeometry().widthDp)
    }

    @Test
    fun theWidthScalesWithDensity() {
        assertEquals(390, OverlayGeometry(widthDp = 130).widthPx(3.0f))
        assertEquals(210, OverlayGeometry(widthDp = 80).widthPx(2.625f))
    }
}
