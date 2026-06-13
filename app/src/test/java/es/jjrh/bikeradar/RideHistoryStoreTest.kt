// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Robolectric (for org.json + android.util.Log). Pins the JSON-lines
 * round-trip, null-field handling, the [RideHistoryStore.MAX_RIDES]
 * trim, and corrupt-line tolerance - the contract the Ride history
 * screen reads against.
 */
@RunWith(RobolectricTestRunner::class)
class RideHistoryStoreTest {

    private fun fullRecord(start: Long = 1_000L) = RideHistoryRecord(
        startedAtMs = start,
        endedAtMs = start + 1_800_000L,
        overtakes = 48,
        closePasses = 2,
        grazingPasses = 1,
        hgvClosePasses = 1,
        peakClosingKmh = 52,
        closingSpeedP90Kmh = 38,
        minLateralClearanceM = 0.8f,
        distanceKm = 12.4f,
        exposureSeconds = 950L,
        alertsPerKm = 1.4f,
        tightestPassClearanceM = 0.8f,
        tightestPassClosingKmh = 32,
    )

    private fun emptyRideRecord() = RideHistoryRecord(
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        overtakes = 0,
        closePasses = 0,
        grazingPasses = 0,
        hgvClosePasses = 0,
        peakClosingKmh = null,
        closingSpeedP90Kmh = null,
        minLateralClearanceM = null,
        distanceKm = 0f,
        exposureSeconds = 0L,
        alertsPerKm = null,
        tightestPassClearanceM = null,
        tightestPassClosingKmh = null,
    )

    @Test fun roundTripPreservesAllFields() {
        val rec = fullRecord()
        val parsed = RideHistoryRecord.fromJsonLine(rec.toJsonLine())
        assertEquals(rec, parsed)
    }

    @Test fun roundTripPreservesNulls() {
        val rec = emptyRideRecord()
        val parsed = RideHistoryRecord.fromJsonLine(rec.toJsonLine())
        assertEquals(rec, parsed)
    }

    @Test fun fromSnapshotMapsEveryField() {
        val snap = RideStatsSnapshot(
            overtakesTotal = 48,
            closePassCount = 2,
            grazingCount = 1,
            hgvClosePassCount = 1,
            peakClosingKmh = 52,
            closingSpeedP90Kmh = 38,
            minLateralClearanceM = 0.8f,
            distanceRiddenKm = 12.4f,
            exposureSeconds = 950L,
            closePassConversionRatePct = 4.2f,
            tightestPass = TightestPass(
                tsMs = 5_000L,
                side = ClosePassDetector.Side.LEFT,
                vehicleSize = VehicleSize.TRUCK,
                clearanceM = 0.8f,
                closingKmh = 32,
                rangeYAtMinM = 1.5f,
            ),
            rideStartedAtMs = 1_000L,
            alertsPerKm = 1.4f,
            alertsPerHourOfRide = 6.5f,
        )
        val rec = RideHistoryRecord.fromSnapshot(snap, endedAtMs = 9_000L)
        assertEquals(1_000L, rec.startedAtMs)
        assertEquals(9_000L, rec.endedAtMs)
        assertEquals(48, rec.overtakes)
        assertEquals(2, rec.closePasses)
        assertEquals(1, rec.grazingPasses)
        assertEquals(1, rec.hgvClosePasses)
        assertEquals(52, rec.peakClosingKmh)
        assertEquals(38, rec.closingSpeedP90Kmh)
        assertEquals(0.8f, rec.minLateralClearanceM!!, 0.001f)
        assertEquals(12.4f, rec.distanceKm, 0.001f)
        assertEquals(950L, rec.exposureSeconds)
        assertEquals(1.4f, rec.alertsPerKm!!, 0.001f)
        assertEquals(0.8f, rec.tightestPassClearanceM!!, 0.001f)
        assertEquals(32, rec.tightestPassClosingKmh)
    }

    @Test fun appendThenReadAllReturnsNewestFirst() {
        val root = Files.createTempDirectory("ridehist").toFile()
        val store = RideHistoryStore({ root })
        store.append(fullRecord(start = 1_000L))
        store.append(fullRecord(start = 2_000L))
        store.append(fullRecord(start = 3_000L))
        val all = store.readAll()
        assertEquals(3, all.size)
        assertEquals(3_000L, all[0].startedAtMs)
        assertEquals(1_000L, all[2].startedAtMs)
    }

    @Test fun readAllIsEmptyWhenNoFileExists() {
        val root = Files.createTempDirectory("ridehist").toFile()
        assertTrue(RideHistoryStore({ root }).readAll().isEmpty())
    }

    @Test fun readAllIsEmptyWhenDirUnavailable() {
        assertTrue(RideHistoryStore({ null }).readAll().isEmpty())
    }

    @Test fun appendIsANoOpWhenDirUnavailable() {
        RideHistoryStore({ null }).append(fullRecord()) // must not throw
    }

    @Test fun corruptLinesAreSkippedOnRead() {
        val root = Files.createTempDirectory("ridehist").toFile()
        val store = RideHistoryStore({ root })
        store.append(fullRecord(start = 1_000L))
        val file = File(File(root, RideHistoryStore.HISTORY_DIR), RideHistoryStore.FILE_NAME)
        file.appendText("{truncated-mid-wri\n")
        file.appendText("\n")
        store.append(fullRecord(start = 2_000L))
        val all = store.readAll()
        assertEquals(2, all.size)
        assertEquals(2_000L, all[0].startedAtMs)
        assertEquals(1_000L, all[1].startedAtMs)
    }

    @Test fun fromJsonLineRejectsGarbage() {
        assertNull(RideHistoryRecord.fromJsonLine(""))
        assertNull(RideHistoryRecord.fromJsonLine("not json"))
        assertNull(RideHistoryRecord.fromJsonLine("""{"v":1}"""))
    }

    @Test fun appendTrimsToMaxRides() {
        val root = Files.createTempDirectory("ridehist").toFile()
        val store = RideHistoryStore({ root })
        repeat(RideHistoryStore.MAX_RIDES + 5) { i ->
            store.append(fullRecord(start = i.toLong()))
        }
        val all = store.readAll()
        assertEquals(RideHistoryStore.MAX_RIDES, all.size)
        // Newest survives, oldest five were dropped.
        assertEquals((RideHistoryStore.MAX_RIDES + 4).toLong(), all.first().startedAtMs)
        assertEquals(5L, all.last().startedAtMs)
    }

    @Test fun unknownKeysAreTolerated() {
        val line = fullRecord().toJsonLine().removeSuffix("}") + ""","future_field":"x"}"""
        val parsed = RideHistoryRecord.fromJsonLine(line)
        assertEquals(fullRecord(), parsed)
    }
}
