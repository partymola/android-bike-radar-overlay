// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Smoke tests for [BikeRadarService] lifecycle entrypoints under
 * Robolectric. Exercises the synchronous portion of onCreate
 * (notification channel, startForeground, Prefs / HaCredentials init,
 * capture-log prune, BroadcastReceiver registration, coroutine launches)
 * and the action dispatch in onStartCommand.
 *
 * What this does NOT cover: the BLE-touching paths under registerEventScan
 * and the GATT plumbing both bail out early in Robolectric because the
 * shadow BluetoothLeScanner is null. Live boot is the only place those
 * code paths execute.
 */
@RunWith(RobolectricTestRunner::class)
class BikeRadarServiceSmokeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After
    fun restoreCryptorFactory() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        // Most tests here create() without destroy(), which would otherwise
        // leave the static pointing at a dead coordinator for later classes.
        BikeRadarService.radarLinkStateForUi = null
    }

    @Test
    fun onCreatePublishesTheLinkStateAndDestroyRetractsIt() {
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        // The Settings radar screen derives "Connecting" vs "Not in range"
        // from this static; unset, every not-yet-streaming radar reads as
        // out of range, which is the lie this exists to remove.
        val flow = BikeRadarService.radarLinkStateForUi
        assertTrue("service must publish its link state for the UI", flow != null)
        assertTrue("initial state is disconnected", flow!!.value.radarGattActive.not())
        // Identity, not shape: a detached flow would satisfy both checks above
        // while the card never leaves "Not in range" on a real ride.
        assertTrue(
            "the published flow must be the coordinator's own",
            flow === controller.get().radarLinkCoordinator.radarLinkState,
        )
        controller.destroy()
        assertTrue(
            "a stopped service must retract the flow, or the screen reads a dead one",
            BikeRadarService.radarLinkStateForUi == null,
        )
    }

    @Test
    fun onCreateRegistersNotificationChannel() {
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        // The FGS notification needs a channel on Android 8+ or it is
        // silently dropped. A regression that breaks channel creation
        // ships as "service runs but invisible".
        val nm = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(
            "expected at least one notification channel registered, got ${nm.notificationChannels}",
            nm.notificationChannels.isNotEmpty(),
        )
        controller.destroy()
    }

    @Test
    fun onStartCommandHandlesNullIntent() {
        // After process restart with START_STICKY the framework redelivers
        // a null intent. The service must tolerate that path without
        // throwing.
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        controller.startCommand(0, 1)
        controller.destroy()
    }

    @Test
    fun eBikeDataDisabledIsCleanNoOp() {
        // Graceful degradation: with the eBike feature flag off, the service
        // must not start the status reader, even for a rider who owns a Bosch
        // eBike. Pinned via EBikeStateBus publishing no frame through onCreate
        // (lastUpdated stays 0) - a regression ships as "the app talks to
        // eBikes for riders who never opted in".
        EBikeStateBus.reset()
        Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = false
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        assertEquals(0L, EBikeStateBus.lastUpdatedElapsedMs.value)
        controller.destroy()
    }

    @Test
    fun eBikeDataEnabledIsCleanWithoutABondedEBike() {
        // Flag-on companion of the no-op test. Flag is enabled, ownership is
        // YES, but no Bosch eBike is bonded in Robolectric's shadow adapter
        // (the only bonded device fixture is the radar mock, if any). The
        // reader must not start, and EBikeStateBus stays at the never-received
        // sentinel - regression ships as a crash on radar-only riders who
        // toggle the feature on without an eBike.
        EBikeStateBus.reset()
        Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = true
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        assertEquals(0L, EBikeStateBus.lastUpdatedElapsedMs.value)
        controller.destroy()
    }

    @Test
    fun onStartCommandHandlesUpdateNotifAction() {
        val intent = Intent().apply { action = BikeRadarService.ACTION_UPDATE_NOTIF }
        val controller = Robolectric.buildService(BikeRadarService::class.java, intent)
        controller.create()
        controller.startCommand(0, 1)
        controller.destroy()
    }

    @Test
    fun onCreateFlushesALeftoverRideCheckpointIntoHistory() {
        // A checkpoint slot found at service start means the previous process
        // died before the post-ride summary could append the ride - onCreate
        // must flush it into history and clear the slot. Deleting the
        // recovery block ships as "every crash-recovered ride silently lost";
        // this is the wiring pin for that block.
        val root = app.getExternalFilesDir(null)!!
        File(root, RideHistoryStore.HISTORY_DIR).deleteRecursively()
        val leftover = RideHistoryRecord(
            startedAtMs = 1_000L,
            endedAtMs = 2_000L,
            overtakes = 4,
            closePasses = 1,
            grazingPasses = 0,
            hgvClosePasses = 0,
            peakClosingKmh = 38,
            closingSpeedP90Kmh = null,
            minLateralClearanceM = 0.9f,
            distanceKm = 5.5f,
            exposureSeconds = 900L,
            alertsPerKm = 0.4f,
            tightestPassClearanceM = 0.9f,
            tightestPassClosingKmh = 38,
            partial = true,
        )
        RideCheckpointStore({ root }).write(leftover)

        Robolectric.buildService(BikeRadarService::class.java).create().destroy()

        assertEquals(
            "the leftover checkpoint must land in ride history on start",
            listOf(leftover),
            RideHistoryStore({ root }).readAll(),
        )
        assertEquals(
            "the flushed slot must be cleared so it cannot double-append",
            null,
            RideCheckpointStore({ root }).take(),
        )
    }

    @Test
    fun bluetoothAdapterOffAndOn_runsBothRecoveryPaths_andJournalsThem() {
        // A mid-ride Bluetooth stack restart must tear the links down (OFF)
        // and re-arm discovery (ON) - the wiring lives in service lambdas,
        // so this drives it end-to-end through the real broadcast. The
        // always-on link journal is the observable: both edges must leave a
        // line, and the service must survive the full cycle (the BLE-touching
        // re-registration paths bail gracefully under Robolectric's null
        // scanner, which is exactly the no-crash contract for a dead adapter).
        val root = app.getExternalFilesDir(null)!!
        File(root, LinkEventJournal.JOURNAL_DIR).deleteRecursively()
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()

        fun broadcast(state: Int) {
            app.sendBroadcast(
                Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                    .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, state),
            )
            shadowOf(app.mainLooper).idle()
        }
        broadcast(android.bluetooth.BluetoothAdapter.STATE_OFF)
        broadcast(android.bluetooth.BluetoothAdapter.STATE_ON)
        controller.destroy()

        val journal = File(File(root, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME).readText()
        assertTrue(
            "the adapter-off teardown must be journaled",
            journal.contains("bluetooth adapter off"),
        )
        assertTrue(
            "the adapter-on recovery must be journaled",
            journal.contains("bluetooth adapter on"),
        )
    }

    @Test
    fun radarStateWithRidingSpeedStampsTheActivityInstant() {
        // Wiring pin for the RadarStateBus collector: a decoded frame with the
        // rider above walking pace must stamp lastRidingActivityMs, the signal
        // the coordinator samples at a disconnect to confirm a radar-only rider
        // was mid-ride (drop cue) and to hold the ride wakelock. Dropping the
        // stamp ships as "radar-only riders never get the dead-radar cue".
        // The collector runs on the service's IO scope, so poll with a deadline.
        RadarStateBus.clear()
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        assertEquals(null, service.lastRidingActivityMs)

        RadarStateBus.publish(RadarState(bikeSpeedMs = 5f))
        val deadline = System.currentTimeMillis() + 5_000L
        while (service.lastRidingActivityMs == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(
            "a frame above walking pace must stamp the riding-activity instant",
            service.lastRidingActivityMs != null,
        )
        controller.destroy()
    }

    @Test
    fun onlyASpeedlessRadarsTracksStampTheFallbackInstant() {
        // Wiring pin for the second half of that collector. A range-only frame
        // showing traffic must stamp lastTrackActivityMs - it is the only riding
        // signal that cohort has - and a V2 frame must NOT, or the fallback
        // leaks into the cohort whose speed gate was actually measured.
        RadarStateBus.clear()
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        assertEquals(null, service.lastTrackActivityMs)

        val traffic = listOf(Vehicle(id = 1, distanceM = 20, speedMs = 0f))

        RadarStateBus.publish(RadarState(vehicles = traffic, source = DataSource.V1))
        val deadline = System.currentTimeMillis() + 5_000L
        while (service.lastTrackActivityMs == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(
            "a range-only frame showing traffic must stamp the fallback instant",
            service.lastTrackActivityMs != null,
        )

        // Both negatives are proved against a SENTINEL rather than by watching
        // the stamp fail to advance: the monotonic clock does not necessarily
        // move under Robolectric, so a re-stamp could write back the same value
        // and read as untouched. A stamp writes elapsedRealtime, which is not
        // going to be this number.
        val sentinel = 424_242L

        // A clear road is not riding activity. This is the case the whole
        // window measurement rests on: stamping on every V1 frame instead would
        // leave the latch fresh at every ride end.
        service.lastTrackActivityMs = sentinel
        RadarStateBus.publish(RadarState(vehicles = emptyList(), source = DataSource.V1))
        Thread.sleep(300)
        assertEquals(
            "an empty road must not stamp the fallback instant",
            sentinel,
            service.lastTrackActivityMs,
        )

        // A radar that reports rider speed keeps the measured speed gate.
        service.lastTrackActivityMs = sentinel
        RadarStateBus.publish(RadarState(vehicles = traffic, source = DataSource.V2))
        Thread.sleep(300)
        assertEquals(
            "a radar that reports rider speed must not stamp the fallback instant",
            sentinel,
            service.lastTrackActivityMs,
        )

        // The service's OWN clearTrackActivity lambda, driven through the real
        // coordinator across a new-ride gap. The coordinator-side test asserts
        // a test double, so without this the production wiring could be an
        // empty lambda and one ride's traffic would survive into the next with
        // nothing red.
        service.lastTrackActivityMs = sentinel
        Prefs(ApplicationProvider.getApplicationContext<Application>())
            .radarLongOfflineThresholdMinutes = 5
        service.radarLinkCoordinator.markConnected()
        service.radarLinkCoordinator.markDisconnected()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        service.radarLinkCoordinator.markConnected()
        assertEquals(
            "a reconnect that starts a new ride must clear the sighting",
            null,
            service.lastTrackActivityMs,
        )

        controller.destroy()
    }

    @Test
    fun postSummaryPathReleasesTheRideWakeLock() {
        // Wiring pin for the live-ride off-episode wakelock: a disconnect with
        // fresh riding activity acquires it (through the coordinator lambda),
        // and the PostSummary branch of maybePostRideSummary - ride declared
        // over - releases it. A dropped release ships as "the CPU is held for
        // the full timeout cap after every ride".
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()

        service.lastRidingActivityMs = android.os.SystemClock.elapsedRealtime()
        service.radarLinkCoordinator.markConnected()
        service.radarLinkCoordinator.markDisconnected()
        assertTrue(
            "a drop with fresh riding activity must hold the ride wakelock",
            service.rideWakeLock.isHeld(),
        )

        // Reconnect resolves the episode through the coordinator's release
        // lambda; a fresh drop then re-acquires for the summary round below.
        service.radarLinkCoordinator.markConnected()
        assertTrue(
            "a reconnect must release the ride wakelock",
            !service.rideWakeLock.isHeld(),
        )
        service.radarLinkCoordinator.markDisconnected()
        assertTrue(service.rideWakeLock.isHeld())

        // A close pass makes the ride "meaningful", so the summary decider posts
        // once the dwell has elapsed past the radar-off instant.
        service.rideStats.observeClosePass(
            ClosePassDetector.Event(
                timestampMs = 1_000L,
                minRangeXM = 0.8f,
                side = ClosePassDetector.Side.RIGHT,
                rangeYAtMinM = 2f,
                closingSpeedKmh = 30,
                riderSpeedKmh = 20,
                vehicleSize = VehicleSize.CAR,
                thresholdArmedM = 1.0f,
                severity = ClosePassDetector.Severity.VERY_CLOSE,
            ),
        )
        service.maybePostRideSummary(
            android.os.SystemClock.elapsedRealtime() + RideSummaryNotificationDecider.POST_DWELL_MS + 1_000L,
        )
        assertTrue(
            "the PostSummary branch must release the ride wakelock",
            !service.rideWakeLock.isHeld(),
        )
        controller.destroy()
    }

    // ── "restarted mid-ride" attention flag ──────────────────────────────────

    private fun midRideCheckpoint(): RideHistoryRecord = RideHistoryRecord(
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        overtakes = 5,
        closePasses = 1,
        grazingPasses = 0,
        hgvClosePasses = 0,
        peakClosingKmh = 30,
        closingSpeedP90Kmh = 25,
        minLateralClearanceM = 1.2f,
        distanceKm = 3f,
        exposureSeconds = 600L,
        alertsPerKm = 0.5f,
        tightestPassClearanceM = 1.2f,
        tightestPassClosingKmh = 30,
        partial = true,
    )

    @Test
    fun dirtyMarkerAloneDoesNotRaiseTheRestartFlag() {
        // A reinstall / force-stop between rides skips onDestroy (marker still
        // set) but leaves no ride checkpoint. Flagging that as "restarted
        // mid-ride" was a false alarm: the rider got the attention item on
        // the first ride after every reinstall.
        Prefs(app).serviceRunningMarker = true
        File(app.getExternalFilesDir(null), RideHistoryStore.HISTORY_DIR).deleteRecursively()

        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        assertTrue(
            "an unclean death with no ride in flight must not raise the restart flag",
            !controller.get().startedFromDirtyRestart,
        )
        controller.destroy()
    }

    @Test
    fun dirtyMarkerPlusCheckpointRaisesTheFlag_thenFirstSummaryClearsIt() {
        Prefs(app).serviceRunningMarker = true
        RideCheckpointStore({ app.getExternalFilesDir(null) }).write(midRideCheckpoint())

        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        assertTrue(
            "unclean death + recovered checkpoint = restarted mid-ride",
            service.startedFromDirtyRestart,
        )

        // Drive one ride to its posted summary (same recipe as the wakelock
        // test): a close pass makes it meaningful, the dwell declares it over.
        service.radarLinkCoordinator.markConnected()
        service.radarLinkCoordinator.markDisconnected()
        service.rideStats.observeClosePass(
            ClosePassDetector.Event(
                timestampMs = 1_000L,
                minRangeXM = 0.8f,
                side = ClosePassDetector.Side.RIGHT,
                rangeYAtMinM = 2f,
                closingSpeedKmh = 30,
                riderSpeedKmh = 20,
                vehicleSize = VehicleSize.CAR,
                thresholdArmedM = 1.0f,
                severity = ClosePassDetector.Severity.VERY_CLOSE,
            ),
        )
        service.maybePostRideSummary(
            android.os.SystemClock.elapsedRealtime() + RideSummaryNotificationDecider.POST_DWELL_MS + 1_000L,
        )
        assertTrue(
            "the first posted summary must clear the flag (report once)",
            !service.startedFromDirtyRestart,
        )
        controller.destroy()
    }

    @Test
    fun cleanShutdownWithLeftoverCheckpointDoesNotRaiseTheFlag() {
        // A clean stop mid-dwell can leave a checkpoint behind with the
        // marker properly cleared: the ride is recovered into history, but
        // nothing "restarted" - no attention item.
        Prefs(app).serviceRunningMarker = false
        RideCheckpointStore({ app.getExternalFilesDir(null) }).write(midRideCheckpoint())

        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        assertTrue(
            "a clean shutdown must not raise the restart flag even with a checkpoint",
            !controller.get().startedFromDirtyRestart,
        )
        controller.destroy()
    }

    @Test
    fun retentionCapConstantIsFifty() {
        // Pins the M9 retention reduction (was 500). A revert trips this.
        assertEquals(50, CaptureLogManager.MAX_CAPTURE_LOGS)
    }

    @Test
    fun onCreatePrunesCaptureLogsToTheCapInTheCapturesSubdir() {
        // M9: capture logs live under files/<CAPTURE_DIR>/ and onCreate prunes
        // them to MAX_CAPTURE_LOGS. Seed more than the cap (each above
        // MIN_USEFUL_LOG_BYTES so none is dropped as header-only), plus a
        // sentinel in the external-files ROOT that prune must NOT touch -
        // proving the prune is scoped to the subdir, not the whole files dir.
        // Assert survivor COUNT only: prune gzips the seeds (resetting mtime),
        // so which files get dropped is not deterministic.
        val root = app.getExternalFilesDir(null)!!
        val captures = File(root, CaptureLogManager.CAPTURE_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val body = "x".repeat(CaptureLogManager.MIN_USEFUL_LOG_BYTES.toInt() + 100)
        repeat(CaptureLogManager.MAX_CAPTURE_LOGS + 10) { i ->
            File(captures, "bike-radar-capture-20260101-0000%02d.log".format(i)).writeText(body)
        }
        val rootSentinel = File(root, "bike-radar-capture-19990101-000000.log").apply {
            writeText(body)
        }

        Robolectric.buildService(BikeRadarService::class.java).create().destroy()

        val kept = captures.listFiles { f -> CaptureLogFiles.isCaptureLog(f) }.orEmpty()
        assertEquals(
            "capture logs should be pruned to the cap",
            CaptureLogManager.MAX_CAPTURE_LOGS,
            kept.size,
        )
        assertTrue(
            "a capture-log file in the external-files root must be left untouched",
            rootSentinel.exists(),
        )
    }
}
