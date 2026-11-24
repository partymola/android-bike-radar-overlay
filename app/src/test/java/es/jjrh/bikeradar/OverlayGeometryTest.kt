// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun theLeftEdgeIsThePhysicalTopLeft() {
        assertEquals(0x33, OverlayGeometry(OverlayEdge.LEFT, 130).gravity)
    }

    @Test
    fun theRightEdgeIsThePhysicalTopRight() {
        assertEquals(0x35, OverlayGeometry(OverlayEdge.RIGHT, 130).gravity)
    }

    @Test
    fun theStripFollowsBothSettings() {
        assertEquals(OverlayGeometry(OverlayEdge.RIGHT, 130), OverlayGeometry.strip(onLeft = false, narrow = false))
        assertEquals(OverlayGeometry(OverlayEdge.RIGHT, 80), OverlayGeometry.strip(onLeft = false, narrow = true))
        assertEquals(OverlayGeometry(OverlayEdge.LEFT, 130), OverlayGeometry.strip(onLeft = true, narrow = false))
        assertEquals(OverlayGeometry(OverlayEdge.LEFT, 80), OverlayGeometry.strip(onLeft = true, narrow = true))
    }

    @Test
    fun theBannerFollowsTheSideAndKeepsItsWidth() {
        assertEquals(OverlayGeometry(OverlayEdge.RIGHT, 130), OverlayGeometry.banner(onLeft = false))
        assertEquals(OverlayGeometry(OverlayEdge.LEFT, 130), OverlayGeometry.banner(onLeft = true))
    }

    @Test
    fun theWidthScalesWithDensity() {
        assertEquals(390, OverlayGeometry(OverlayEdge.RIGHT, 130).widthPx(3.0f))
        assertEquals(210, OverlayGeometry(OverlayEdge.RIGHT, 80).widthPx(2.625f))
    }
}
