// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.EBikeOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.ipc.RadarOverlayGate
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // A test that fails before destroy() would otherwise leave the static
        // pointing at a dead coordinator for later classes.
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
    fun eBikeDataOffNeverStartsTheReaderEvenWithABondedEBike() {
        // Graceful degradation: a rider who owns a Bosch eBike but switched its
        // data off must not be read from, whichever path asks for a start.
        // Without a bonded eBike in the fixture no reader could start anyway.
        EBikeStateBus.reset()
        val root = app.getExternalFilesDir(null) ?: error("Robolectric always provides an external files dir")
        File(root, LinkEventJournal.JOURNAL_DIR).deleteRecursively()
        val adapter = (app.getSystemService(Application.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        val bike = adapter.getRemoteDevice("11:22:33:44:55:66")
        shadowOf(bike).setName("smart system eBike")
        shadowOf(adapter).setBondedDevices(setOf(bike))
        val prefs = Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = false
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        shadowOf(app.mainLooper).idle()
        // Bluetooth coming back, and onboarding's action, both ask for a start.
        for (state in listOf(android.bluetooth.BluetoothAdapter.STATE_OFF, android.bluetooth.BluetoothAdapter.STATE_ON)) {
            app.sendBroadcast(
                Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                    .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, state),
            )
            shadowOf(app.mainLooper).idle()
        }
        service.onStartCommand(Intent().apply { action = BikeRadarService.ACTION_START_EBIKE_READER }, 0, 1)
        assertNull("eBike data is off", service.ebikeStatusReader)
        assertEquals(EBikeStage.NOT_STARTED, EBikeStateBus.stage.value)
        val journal = File(File(root, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME).readText()
        assertFalse("nothing was switched off, so nothing to log", journal.contains("ebike data switched off"))

        // The same fixture with the data on does start one.
        prefs.eBikeDataEnabled = true
        idleMainUntil { service.ebikeStatusReader != null }
        assertTrue("the bonded eBike must be found", service.ebikeStatusReader != null)
        controller.destroy()
    }

    @Test
    fun eBikeDataEnabledIsCleanWithoutABondedEBike() {
        // Flag-on companion. No Bosch eBike is bonded in Robolectric's shadow
        // adapter, so the start attempt must record why it did not start, and
        // no frame arrives - a regression ships as a crash on radar-only riders
        // who toggle the feature on without an eBike. The stage also shows a
        // service started with the data on attempts the start at all.
        EBikeStateBus.reset()
        Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = true
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        idleMainUntil { EBikeStateBus.stage.value != EBikeStage.NOT_STARTED }
        assertEquals(EBikeStage.NO_BONDED_BIKE, EBikeStateBus.stage.value)
        assertEquals(0L, EBikeStateBus.lastUpdatedElapsedMs.value)
        controller.destroy()
    }

    private fun idleMainUntil(timeoutMs: Long = 5_000L, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(app.mainLooper).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun switchingEBikeDataOffStopsARunningReader() {
        // Settings and onboarding's "back" and "I don't have one" write only the
        // pref. A reader left running keeps feeding the no-radar warning for a
        // rider whose switch for it is hidden.
        val root = app.getExternalFilesDir(null) ?: error("Robolectric always provides an external files dir")
        File(root, LinkEventJournal.JOURNAL_DIR).deleteRecursively()
        val prefs = Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = true
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        shadowOf(app.mainLooper).idle()
        // Robolectric has no bonded eBike, so stand in for one that started.
        val readerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        service.ebikeStatusReader = EBikeStatusReader(app, readerScope, "AA:BB:CC:DD:EE:FF", onSnapshot = {})
        EBikeStateBus.setStage(EBikeStage.WAITING)

        // A reading from before: Bluetooth going off keeps it, the switch does
        // not. Left behind, the forgot-to-lock reminder and the unlocked
        // banner would keep acting on it with no age limit.
        service.ebikeSnapshotCoordinator.onSnapshot(LiveDataSnapshot(systemLocked = false))
        app.sendBroadcast(
            Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.STATE_OFF),
        )
        shadowOf(app.mainLooper).idle()
        assertNull("the broadcast must have reached the adapter-off path", service.ebikeStatusReader)
        assertEquals(false, service.ebikeSnapshotCoordinator.lastSnapshotAnyAge()?.systemLocked)
        service.ebikeStatusReader = EBikeStatusReader(app, readerScope, "AA:BB:CC:DD:EE:FF", onSnapshot = {})

        prefs.eBikeDataEnabled = false
        idleMainUntil { service.ebikeStatusReader == null }
        assertNull("switching eBike data off must stop the reader", service.ebikeStatusReader)
        assertEquals(EBikeStage.NOT_STARTED, EBikeStateBus.stage.value)
        idleMainUntil { service.ebikeSnapshotCoordinator.lastSnapshotAnyAge() == null }
        assertNull("and drop the last reading", service.ebikeSnapshotCoordinator.lastSnapshotAnyAge())
        assertFalse(service.ebikeSnapshotCoordinator.hasEverSeenSnapshot())
        val journal = File(File(root, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME).readText()
        assertTrue("the switch-off must reach the journal, got:\n$journal", journal.contains("ebike data switched off"))
        readerScope.cancel()
        controller.destroy()
    }

    @Test
    fun switchingOffAReaderThatNeverSentAFrameIsJournalledToo() {
        // The other half of the journal line's condition: a running reader is
        // worth a line even with no reading to forget.
        val root = app.getExternalFilesDir(null) ?: error("Robolectric always provides an external files dir")
        File(root, LinkEventJournal.JOURNAL_DIR).deleteRecursively()
        val prefs = Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = true
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        shadowOf(app.mainLooper).idle()
        val readerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        // No GATT, so the loop only backs off: running, and nothing to send.
        val reader = EBikeStatusReader(app, readerScope, "AA:BB:CC:DD:EE:FF", onSnapshot = {}, openGatt = { _, _, _, _ -> null })
        service.ebikeStatusReader = reader
        reader.start()

        prefs.eBikeDataEnabled = false
        val journal = File(File(root, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME)
        idleMainUntil { journal.readText().contains("ebike data switched off") }
        assertFalse(service.ebikeSnapshotCoordinator.hasEverSeenSnapshot())
        assertTrue("the switch-off must reach the journal, got:\n${journal.readText()}", journal.readText().contains("ebike data switched off"))
        readerScope.cancel()
        controller.destroy()
    }

    @Test
    fun aFrameStillBeingHandledWhenTheSwitchGoesOffIsForgottenToo() {
        // The reader hands over frames it had received before its cancel. One
        // that finishes after the switch-off would put the reading back.
        lateFrameAcrossTheSwitchOff(duringTheWait = { _, _ -> }) { service ->
            idleMainUntil { service.ebikeSnapshotCoordinator.lastSnapshotAnyAge() == null }
            assertNull("the late frame must not survive the switch", service.ebikeSnapshotCoordinator.lastSnapshotAnyAge())
            assertFalse(service.ebikeSnapshotCoordinator.hasEverSeenSnapshot())
            assertEquals("nor on the status bus", EBikeStage.NOT_STARTED, EBikeStateBus.stage.value)
            assertEquals(0L, EBikeStateBus.lastUpdatedElapsedMs.value)
        }
    }

    @Test
    fun aReaderStartedWhileTheSwitchOffWaitsKeepsItsStatus() {
        // Onboarding's back then "I have one" can start a reader while the
        // switch-off is still waiting for the old one: clearing then would
        // wipe the new reader's status.
        var started: EBikeStatusReader? = null
        lateFrameAcrossTheSwitchOff(duringTheWait = { service, scope ->
            started = EBikeStatusReader(app, scope, "AA:BB:CC:DD:EE:FF", onSnapshot = {})
            service.ebikeStatusReader = started
            EBikeStateBus.setStage(EBikeStage.WAITING)
        }) { service ->
            // Long enough for the switch-off to finish its wait.
            idleMainUntil(timeoutMs = 1_000L) { false }
            assertTrue("the new reader was dropped", service.ebikeStatusReader === started)
            // The old reader's late frame is the last thing on the bus, and the
            // coordinator keeps it; neither is cleared under the new reader.
            assertEquals(EBikeStage.RECEIVING, EBikeStateBus.stage.value)
            assertTrue("the reading was forgotten", service.ebikeSnapshotCoordinator.lastSnapshotAnyAge() != null)
        }
    }

    /** A real reader holding one frame inside its handler while eBike data is
     *  switched off; [duringTheWait] runs before the frame is let go. */
    private fun lateFrameAcrossTheSwitchOff(
        duringTheWait: (BikeRadarService, kotlinx.coroutines.CoroutineScope) -> Unit,
        afterRelease: (BikeRadarService) -> Unit,
    ) {
        Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = true
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        shadowOf(app.mainLooper).idle()

        val inHandler = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val handled = java.util.concurrent.CountDownLatch(1)
        val releasedInTime = java.util.concurrent.atomic.AtomicBoolean(false)
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        // Set on the reader's thread, read on this one.
        val cbRef = java.util.concurrent.atomic.AtomicReference<android.bluetooth.BluetoothGattCallback?>()
        val gattRef = java.util.concurrent.atomic.AtomicReference<android.bluetooth.BluetoothGatt?>()
        val status = android.bluetooth.BluetoothGattCharacteristic(
            Uuids.CHAR_EBIKE_STATUS,
            android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply { addDescriptor(android.bluetooth.BluetoothGattDescriptor(Uuids.CCCD, android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE)) }
        val readerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val reader = EBikeStatusReader(
            context = app,
            scope = readerScope,
            mac = "AA:BB:CC:DD:EE:FF",
            // The service's own handler, held until the switch-off is waiting.
            onSnapshot = { snap ->
                inHandler.countDown()
                releasedInTime.set(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                service.onEBikeFrame(snap)
                handled.countDown()
            },
            log = { logs += it },
            openGatt = { ctx, dev, _, c ->
                @Suppress("DEPRECATION")
                dev.connectGatt(ctx, false, c).also { g ->
                    val svc = android.bluetooth.BluetoothGattService(Uuids.SVC_EBIKE_STATUS, android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY)
                    svc.addCharacteristic(status)
                    shadowOf(g).addDiscoverableService(svc)
                    gattRef.set(g)
                    cbRef.set(c)
                }
            },
        )
        service.ebikeStatusReader = reader
        reader.start()
        idleMainUntil { cbRef.get() != null }
        val cb = requireNotNull(cbRef.get())
        val gatt = requireNotNull(gattRef.get())
        cb.onConnectionStateChange(gatt, 0, android.bluetooth.BluetoothProfile.STATE_CONNECTED)
        idleMainUntil {
            cb.onDescriptorWrite(gatt, status.getDescriptor(Uuids.CCCD), 0)
            logs.any { "subscribed" in it }
        }
        assertTrue("the reader must reach streaming; log=$logs", logs.any { "subscribed" in it })

        // Battery 72 %: the reader is now inside its handler with this frame.
        cb.onCharacteristicChanged(gatt, status, "300480880848".hexToBytes())
        assertTrue(inHandler.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Prefs(app).eBikeDataEnabled = false
        idleMainUntil { service.ebikeStatusReader == null }
        // Otherwise a switch-off that never arrived fails below as a late frame.
        assertNull("the switch-off never reached the service", service.ebikeStatusReader)
        duringTheWait(service, readerScope)
        release.countDown()
        // Until the frame has landed, "no reading" proves nothing.
        assertTrue("the late frame was never handled", handled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("the frame went through before the switch-off", releasedInTime.get())

        afterRelease(service)
        readerScope.cancel()
        controller.destroy()
    }

    @Test
    fun switchingEBikeDataOnStartsTheReaderWithoutARestart() {
        // The Settings switch writes only the pref, and its toast says the data
        // is on. Without a bonded eBike the start attempt can only record why it
        // did not start, which is what shows it ran.
        EBikeStateBus.reset()
        val prefs = Prefs(app).apply {
            eBikeOwnership = EBikeOwnership.YES
            eBikeDataEnabled = false
        }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        shadowOf(app.mainLooper).idle()
        assertEquals(EBikeStage.NOT_STARTED, EBikeStateBus.stage.value)

        prefs.eBikeDataEnabled = true
        idleMainUntil { EBikeStateBus.stage.value != EBikeStage.NOT_STARTED }
        assertEquals(EBikeStage.NO_BONDED_BIKE, EBikeStateBus.stage.value)
        controller.destroy()
    }

    /** A ride confirmed by the service's own eBike path with the radar never
     *  up, then the tick cadence the loop would sleep for. */
    private fun tickDelayOnARideWithNoRadar(radarBonded: Boolean): Long {
        val adapter = (app.getSystemService(Application.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        val radar = adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        shadowOf(radar).setName("RearVue8")
        shadowOf(adapter).setBondedDevices(if (radarBonded) setOf(radar) else emptySet())
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        assertEquals("idle before any ride", 30_000L, service.walkAwayTickDelayMs())
        repeat(13) {
            service.ebikeSnapshotCoordinator.onSnapshot(LiveDataSnapshot(systemLocked = false, speedRaw = 2_000))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        }
        service.radarLinkCoordinator.evaluateRadarDrop(android.os.SystemClock.elapsedRealtime())
        val delay = service.walkAwayTickDelayMs()
        controller.destroy()
        return delay
    }

    @Test
    fun aRideWithNoRadarTicksFastOnlyWhenARadarIsPaired() {
        // The service half of the no-radar warning: the loop's cadence comes
        // from the coordinator, and "a radar is paired" from the bonded list.
        // Either wired to a constant leaves the coordinator tests green.
        assertEquals(2_000L, tickDelayOnARideWithNoRadar(radarBonded = true))
        assertEquals(30_000L, tickDelayOnARideWithNoRadar(radarBonded = false))
    }

    @Test
    fun theEBikeSnapshotIsStampedOnTheClockTheRadarLinkReadsItBy() {
        // The alert path ages the snapshot on the coordinator's own clock, but
        // RadarLinkCoordinator compares snapshotAtMs() against its own
        // elapsedRealtime for walk-away arming and the drop cue. Stamped on any
        // other clock, an unlocked reading would have its age misread there.
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val coord = controller.get().ebikeSnapshotCoordinator
        // Deep sleep moves elapsedRealtime and not uptimeMillis, which is what
        // tells the two apart; a plain advance moves both.
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(10))
        val at = android.os.SystemClock.elapsedRealtime()
        coord.onSnapshot(LiveDataSnapshot(bikeNotDriving = true))
        assertEquals(at, coord.snapshotAtMs())
        controller.destroy()
    }

    @Test
    fun aVolumeChangeMidRideReachesTheLiveBeeper() {
        // No radar here, so this is the disconnected case the drop cue plays
        // in. The collector runs on the service's IO scope, so poll with a
        // deadline.
        val prefs = Prefs(app).apply { alertVolume = 40 }
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val beeper = requireNotNull(controller.get().alertBeeper)
        assertEquals(40, beeper.currentVolumePct)

        prefs.alertVolume = 85
        val deadline = System.currentTimeMillis() + 5_000L
        while (beeper.currentVolumePct != 85 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("the running beeper must take the new volume", 85, beeper.currentVolumePct)
        controller.destroy()
    }

    @Test
    fun walkAwayArmingStillSeesAnEBikeSnapshotTooOldForTheAlertPath() {
        // The radar-link coordinator applies its own 30 s window, so it must
        // get the snapshot however old. Handed the alert path's 3 s one
        // instead, a bike unlocked 10 s ago would read as absent and the
        // walk-away alarm would arm on a rider still at the bike.
        fun armedAfter(ageSec: Long): Boolean {
            val controller = Robolectric.buildService(BikeRadarService::class.java)
            controller.create()
            val service = controller.get()
            service.ebikeSnapshotCoordinator.onSnapshot(LiveDataSnapshot(systemLocked = false))
            ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(ageSec))
            service.radarLinkCoordinator.markDisconnected()
            val armed = service.radarLinkCoordinator.radarLinkState.value.walkAwayArmed
            controller.destroy()
            return armed
        }
        assertFalse("a 10 s old unlocked reading must still hold arming off", armedAfter(10))
        assertTrue("a 40 s old one is past the arming window, so it arms", armedAfter(40))
    }

    @Test
    fun anOvernightBikeLockStillReachesTheStateTheHomeScreenReads() {
        // The home screen's parked question rests on the coordinator getting
        // the snapshot however old: the bike drops its link as it sleeps, so a
        // lock reading is hours old by the time it matters. A supplier capped
        // at any window brings the question back after every ride.
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        service.ebikeSnapshotCoordinator.onSnapshot(LiveDataSnapshot(systemLocked = true))
        ShadowSystemClock.simulateDeepSleep(Duration.ofHours(8))
        service.radarLinkCoordinator.evaluateRadarDrop(android.os.SystemClock.elapsedRealtime())
        assertTrue(service.radarLinkCoordinator.radarLinkState.value.bikeLocked)
        controller.destroy()
    }

    @Test
    fun aNewRideForgetsTheLockInTheServicesOwnSnapshot() {
        // The coordinator-side test asserts a test double, so without this the
        // production lambda could be empty and the last ride's lock would
        // still veto this ride's drop cue.
        val root = app.getExternalFilesDir(null) ?: error("Robolectric always provides an external files dir")
        File(root, LinkEventJournal.JOURNAL_DIR).deleteRecursively()
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        Prefs(app).radarLongOfflineThresholdMinutes = 5
        service.ebikeSnapshotCoordinator.onSnapshot(LiveDataSnapshot(systemLocked = true, batterySoc = 64))
        // Still being sent as the first radar comes up: live, so kept.
        service.radarLinkCoordinator.markConnected()
        assertEquals(true, service.ebikeSnapshotCoordinator.lastSnapshotAnyAge()?.systemLocked)
        // A ride long enough to count, so the reconnect below is a new ride
        // rather than the session's first connect.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        service.radarLinkCoordinator.markDisconnected()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        service.radarLinkCoordinator.markConnected()
        val kept = service.ebikeSnapshotCoordinator.lastSnapshotAnyAge()
        assertNull(kept?.systemLocked)
        assertEquals(64, kept?.batterySoc)
        val journal = File(File(root, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME).readText()
        assertTrue("the dropped lock must reach the always-on journal, got:\n$journal", journal.contains("ebike lock reading dropped"))
        controller.destroy()
    }

    @Test
    fun aNewRideKeepsAStaleUnlockedReading() {
        // Only a lock is forgotten: the forgot-to-lock reminder needs the last
        // unlocked reading, however old.
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        Prefs(app).radarLongOfflineThresholdMinutes = 5
        service.ebikeSnapshotCoordinator.onSnapshot(LiveDataSnapshot(systemLocked = false))
        service.radarLinkCoordinator.markConnected()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        service.radarLinkCoordinator.markDisconnected()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        service.radarLinkCoordinator.markConnected()
        assertEquals(false, service.ebikeSnapshotCoordinator.lastSnapshotAnyAge()?.systemLocked)
        controller.destroy()
    }

    @Test
    fun theRideNotificationFollowsAHoldAndACallUntilTheServiceStops() {
        // The service half of the notification's reposts: that onCreate starts
        // them and onDestroy ends them. What they post is pinned in
        // OverlayHoldIsExplainedInTheNotificationTest.
        RadarOverlayGate.reset()
        val nm = app.getSystemService(NotificationManager::class.java)
        fun posted() = shadowOf(nm).getNotification(ServiceNotifications.NOTIF_ID)
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        // The service's own instance, which is the one its listener sits on.
        val audio = controller.get().getSystemService(AudioManager::class.java)
        var destroyed = false
        try {
            RadarOverlayGate.hide("com.example.trailbuddy")
            shadowOf(app.mainLooper).idle()
            assertEquals(
                "a hold must reach the ride notification",
                "Overlay hidden: com.example.trailbuddy",
                posted()?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            )

            audio.mode = AudioManager.MODE_IN_CALL
            shadowOf(app.mainLooper).idle()
            assertTrue("the ride notification must still be up", posted() != null)
            assertEquals("a call must take the line off", null, posted()!!.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))

            controller.destroy()
            destroyed = true
            nm.cancel(ServiceNotifications.NOTIF_ID)
            audio.mode = AudioManager.MODE_NORMAL
            shadowOf(app.mainLooper).idle()
            assertTrue("a stopped service must post nothing", posted() == null)
        } finally {
            if (!destroyed) controller.destroy()
            RadarOverlayGate.reset()
        }
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
    fun theEndRideActionReachesTheCoordinator() {
        // The service half of the End ride chain: the main screen's control
        // starts the service with exactly this intent (pinned on the other side
        // by MainScreenEndRideCtaTest.tappingTheControlAsksTheServiceToEndTheRide),
        // and this is what the dispatch arm does with it. Deleting the handler
        // body leaves a control that looks live and silences nothing.
        val root = app.getExternalFilesDir(null) ?: error("Robolectric always provides an external files dir")
        File(root, LinkEventJournal.JOURNAL_DIR).deleteRecursively()
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()
        assertTrue(
            "a fresh service carries no rider declaration",
            !service.radarLinkCoordinator.radarLinkState.value.rideEndedByRider,
        )

        // Another action first. Without this the call could be hoisted out of
        // the when arm, and every start - the notification refresh included -
        // would silence the dead-radar cue with nothing here going red.
        service.onStartCommand(Intent().apply { action = BikeRadarService.ACTION_UPDATE_NOTIF }, 0, 1)
        assertTrue(
            "only the END_RIDE action may end the ride",
            !service.radarLinkCoordinator.radarLinkState.value.rideEndedByRider,
        )

        service.onStartCommand(BikeRadarService.endRideIntent(app), 0, 2)
        assertTrue(
            "the END_RIDE action must mark the ride ended on the coordinator",
            service.radarLinkCoordinator.radarLinkState.value.rideEndedByRider,
        )
        // The declaration silences a safety cue, so it has to leave a trace a
        // report can read. The coordinator-side test asserts a test double, so
        // without this the production journal lambda could be empty and the
        // default install would be back to recording nothing.
        val journal = File(File(root, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME).readText()
        assertTrue(
            "the rider's declaration must reach the always-on journal, got:\n$journal",
            journal.contains("ride ended by rider"),
        )
        controller.destroy()
    }

    @Test
    fun theSnoozeActionSaysItWasASnoozeAndTheDismissActionDoesNot() {
        // The coordinator tests call both branches directly, so they cannot see
        // which one each service action picks. Drop the argument at the snooze
        // dispatch and the default would apply, journalling a plain dismissal
        // for a rider who only asked for two minutes of quiet - the confusion
        // the two lines exist to remove, reinstated with every test green.
        val root = app.getExternalFilesDir(null) ?: error("Robolectric always provides an external files dir")
        File(root, LinkEventJournal.JOURNAL_DIR).deleteRecursively()
        val controller = Robolectric.buildService(BikeRadarService::class.java)
        controller.create()
        val service = controller.get()

        service.onStartCommand(Intent().apply { action = BikeRadarService.ACTION_WALKAWAY_SNOOZE }, 0, 1)
        service.onStartCommand(Intent().apply { action = BikeRadarService.ACTION_WALKAWAY_DISMISS }, 0, 2)

        val journal = File(File(root, LinkEventJournal.JOURNAL_DIR), LinkEventJournal.FILE_NAME).readText()
        assertTrue("the snooze must record itself as one, got:\n$journal", journal.contains("snoozed by rider"))
        assertTrue("the dismissal must record itself as one, got:\n$journal", journal.contains("dismissed by rider"))
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
