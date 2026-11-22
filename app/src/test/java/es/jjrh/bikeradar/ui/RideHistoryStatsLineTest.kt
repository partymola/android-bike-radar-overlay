// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.RideHistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The stats segments of a ride row, in both locales: the vehicles detected
 * behind, the approximate count that passed with the close passes nested in it,
 * the tightest clearance. The row lays each segment out whole; how the line
 * breaks is pinned by the `RideHistorySnapshotTest` goldens.
 */
@RunWith(RobolectricTestRunner::class)
class RideHistoryStatsLineTest {

    private val res get() = ApplicationProvider.getApplicationContext<Application>().resources

    private fun record(
        detected: Int = 37,
        passed: Int? = 8,
        close: Int = 1,
        counted: Boolean? = true,
        tightest: Float? = 0.8f,
    ) = RideHistoryRecord(
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        overtakes = detected,
        closePasses = close,
        grazingPasses = 0,
        hgvClosePasses = 0,
        peakClosingKmh = null,
        closingSpeedP90Kmh = null,
        minLateralClearanceM = null,
        distanceKm = 5f,
        exposureSeconds = 0L,
        alertsPerKm = null,
        tightestPassClearanceM = tightest,
        tightestPassClosingKmh = null,
        closePassesCounted = counted,
        vehiclesPassed = passed,
    )

    private val en = Locale.UK
    private val es = Locale.forLanguageTag("es-ES")

    private fun line(rec: RideHistoryRecord, locale: Locale) = rideHistoryStatsSegments(res, locale, rec)

    private fun segments(vararg parts: String) = parts.toList()

    // ── English ──────────────────────────────────────────────────────────────

    @Test
    fun aCountedRideNestsItsClosePassesInThePassedCount() {
        assertEquals(
            segments("37 vehicles detected behind", "about 8 passed you (1 close)", "tightest 0.8 m"),
            line(record(), en),
        )
    }

    @Test
    fun moreClosePassesThanPassedReadsAsAcceptedWithAZeroPassed() {
        assertEquals(
            segments("37 vehicles detected behind", "about 0 passed you (1 close)", "tightest 0.8 m"),
            line(record(passed = 0), en),
        )
    }

    @Test
    fun aRideWithNoPassedFigureShowsItsClosePassesAlone() {
        assertEquals(
            segments("37 vehicles detected behind", "1 close pass", "tightest 0.8 m"),
            line(record(passed = null), en),
        )
        assertEquals(
            segments("37 vehicles detected behind", "3 close passes", "tightest 0.8 m"),
            line(record(passed = null, close = 3), en),
        )
    }

    @Test
    fun anUncountedRideShowsNoClosePassFigureAndKeepsItsPassedCount() {
        assertEquals(
            segments("37 vehicles detected behind", "about 8 passed you", "tightest 0.8 m"),
            line(record(counted = false), en),
        )
        assertEquals(
            segments("37 vehicles detected behind", "tightest 0.8 m"),
            line(record(counted = false, passed = null), en),
        )
        assertEquals(
            segments("37 vehicles detected behind", "about 1 passed you", "tightest 0.8 m"),
            line(record(counted = false, passed = 1), en),
        )
    }

    @Test
    fun aRideOfUnknownCountingNestsItsClosePassesLikeACountedOne() {
        assertEquals(
            segments("37 vehicles detected behind", "about 8 passed you (1 close)", "tightest 0.8 m"),
            line(record(counted = null), en),
        )
    }

    @Test
    fun singularsAndAMissingTightestFigure() {
        assertEquals(
            segments("1 vehicle detected behind", "about 1 passed you (1 close)"),
            line(record(detected = 1, passed = 1, tightest = null), en),
        )
        assertEquals(
            segments("0 vehicles detected behind", "about 0 passed you (0 close)"),
            line(record(detected = 0, passed = 0, close = 0, tightest = null), en),
        )
    }

    // ── Spanish ──────────────────────────────────────────────────────────────

    @Test
    @Config(qualifiers = "+es")
    fun aCountedRideNestsItsClosePassesInThePassedCountInSpanish() {
        assertEquals(
            segments("37 vehículos detectados detrás", "unos 8 adelantamientos (1 ajustado)", "mínimo 0,8 m"),
            line(record(), es),
        )
        assertEquals(
            segments("37 vehículos detectados detrás", "unos 8 adelantamientos (3 ajustados)", "mínimo 0,8 m"),
            line(record(close = 3), es),
        )
    }

    @Test
    @Config(qualifiers = "+es")
    fun moreClosePassesThanPassedReadsAsAcceptedWithAZeroPassedInSpanish() {
        assertEquals(
            segments("37 vehículos detectados detrás", "unos 0 adelantamientos (1 ajustado)", "mínimo 0,8 m"),
            line(record(passed = 0), es),
        )
    }

    @Test
    @Config(qualifiers = "+es")
    fun aRideWithNoPassedFigureShowsItsClosePassesAloneInSpanish() {
        assertEquals(
            segments("37 vehículos detectados detrás", "1 adelantamiento ajustado", "mínimo 0,8 m"),
            line(record(passed = null), es),
        )
        assertEquals(
            segments("37 vehículos detectados detrás", "3 adelantamientos ajustados", "mínimo 0,8 m"),
            line(record(passed = null, close = 3), es),
        )
    }

    @Test
    @Config(qualifiers = "+es")
    fun anUncountedRideShowsNoClosePassFigureInSpanish() {
        assertEquals(
            segments("37 vehículos detectados detrás", "unos 8 adelantamientos", "mínimo 0,8 m"),
            line(record(counted = false), es),
        )
        assertEquals(
            segments("37 vehículos detectados detrás", "mínimo 0,8 m"),
            line(record(counted = false, passed = null), es),
        )
        assertEquals(
            segments("37 vehículos detectados detrás", "aprox. 1 adelantamiento", "mínimo 0,8 m"),
            line(record(counted = false, passed = 1), es),
        )
    }

    @Test
    @Config(qualifiers = "+es")
    fun aSingleVehicleReadsInTheSingularInSpanish() {
        assertEquals(
            segments("1 vehículo detectado detrás", "aprox. 1 adelantamiento (1 ajustado)"),
            line(record(detected = 1, passed = 1, tightest = null), es),
        )
    }

    // ── wrapping ─────────────────────────────────────────────────────────────

    @Test
    fun theRowEndsEverySegmentButTheLastWithABulletHeldToIt() {
        assertEquals(
            listOf("37 vehicles detected behind\u00A0•", "about 8 passed you (1 close)\u00A0•", "tightest 0.8 m"),
            rideHistoryStatsTexts(line(record(), en)),
        )
        assertEquals(
            listOf("37 vehicles detected behind"),
            rideHistoryStatsTexts(line(record(counted = false, passed = null, tightest = null), en)),
        )
    }

    @Test
    @Config(qualifiers = "+es")
    fun theRowEndsEverySegmentButTheLastWithABulletHeldToItInSpanish() {
        assertEquals(
            listOf("37 vehículos detectados detrás\u00A0•", "unos 8 adelantamientos (1 ajustado)\u00A0•", "mínimo 0,8 m"),
            rideHistoryStatsTexts(line(record(), es)),
        )
    }

    private val noBreakSpace = Char(0xA0)

    private val shapes = listOf(
        record(),
        record(passed = null),
        record(counted = false),
        record(counted = false, passed = 1),
        record(detected = 1, passed = 1),
    )

    @Test
    fun noSegmentHoldsANoBreakSpace() {
        for (rec in shapes) {
            assertFalse(line(rec, en).toString(), line(rec, en).any { it.contains(noBreakSpace) })
        }
    }

    @Test
    @Config(qualifiers = "+es")
    fun noSpanishSegmentHoldsANoBreakSpace() {
        for (rec in shapes) {
            assertFalse(line(rec, es).toString(), line(rec, es).any { it.contains(noBreakSpace) })
        }
    }
}
