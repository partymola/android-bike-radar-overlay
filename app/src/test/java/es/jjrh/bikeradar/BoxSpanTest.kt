// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the strip draws a target's box, in metres behind the rider. */
class BoxSpanTest {

    @Test
    fun aLockedCarSpansItsTemplateCentredOnTheRange() {
        assertEquals(8f to 12f, boxSpanM(10f, 4f))
    }

    @Test
    fun anUnlockedTrackStartsAtItsRangeAndRunsBackOneCar() {
        assertEquals(8f to 12f, boxSpanM(8f, 0f))
    }

    @Test
    fun theNearEdgeHoldsStillForALockStepOfHalfTheTemplate() {
        // bike-radar-docs PROTOCOL.md: on the lock frame rangeY steps back by
        // about half the template. Pre-lock 8 m, post-lock 10 m, 4 m template.
        val before = boxSpanM(8f, 0f).first
        val after = boxSpanM(10f, 4f).first
        assertEquals(before, after, 0f)
    }

    @Test
    fun theNearEdgeNeverPassesTheRider() {
        assertEquals(0f to 3f, boxSpanM(1f, 4f))
    }

    @Test
    fun theLongTemplateIsDrawnAtItsOwnLength() {
        assertEquals(12.5f to 27.5f, boxSpanM(20f, 15f))
    }

    @Test
    fun theShortTemplateIsDrawnAtItsOwnLength() {
        assertEquals(4f to 6f, boxSpanM(5f, 2f))
    }

    @Test
    fun aLockedWidthIsUsedAsIs() {
        assertEquals(2.25f, boxWidthM(2.25f), 0f)
    }

    @Test
    fun anUnlockedWidthIsTheCarTemplate() {
        assertEquals(1.75f, boxWidthM(0f), 0f)
    }

    @Test
    fun aWidthWiderThanTheStripIsCappedAtTheStrip() {
        // 0xFF in the width byte decodes to 63.75 m; the strip spans 6 m.
        assertEquals(6f, boxWidthM(63.75f), 0f)
    }
}
