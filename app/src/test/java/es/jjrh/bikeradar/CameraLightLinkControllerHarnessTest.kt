// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 JJ del Rio
package es.jjrh.bikeradar

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.media.ToneGenerator
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.DashcamOwnership
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToneGenerator
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Robolectric harness for [CameraLightLinkController.runCameraLightConnection]
 * and its inner connect-and-run loop - the front camera/light reconnect ->
 * AMV (FRONT_CAMERA) handshake -> mode-state-notify path. The simpler twin of
 * the radar harness (no V2 decode, no data-flow watchdog), built first to
 * establish the fake-GATT driving pattern.
 *
 * Driving model (mirrors [EnablingSequenceHarnessTest]):
 *  - The controller's own [BluetoothGattCallback] is captured through the
 *    injected [openGatt] seam, so the test drives connect/discover/notify by
 *    hand and a background driver completes the read/CCCD/MTU ops the shadow
 *    does not auto-fire (writes ARE auto-fired by ShadowBluetoothGatt).
 *  - Handshake reply frames are pushed onto the controller's notify channel
 *    through `onCharacteristicChanged`, in `awaitNotify` order.
 *  - The loop is torn down with [CameraLightLinkController.stop]; the
 *    background driver rides `backgroundScope` and is cancelled at test end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CameraLightLinkControllerHarnessTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val journal = Collections.synchronizedList(mutableListOf<String>())

    /** What the controller told the walk-away alarm, in order. */
    private val reports = Collections.synchronizedList(mutableListOf<String>())

    /** Run as the handshake is reported, inside the controller's coroutine. */
    private var onHeard: () -> Unit = {}

    /** Every report including each connection's end, which [reports] leaves out. */
    private val edges = Collections.synchronizedList(mutableListOf<String>())
    private val gateway = object : DashcamLinkGateway {
        override fun markDashcamHeard(mac: String) {
            reports += "heard"
            edges += "heard $mac"
            onHeard()
        }

        override fun markDashcamSwitchedOff(mac: String) {
            reports += "off $mac"
            edges += "off"
        }

        override fun markDashcamLinkDown() {
            edges += "down"
        }

        override fun markDashcamLinkGone() {
            reports += "gone"
            edges += "gone"
        }
    }

    private val adapter get() = requireNotNull(app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter)

    @Before fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        BatteryStateBus.clearForTest()
        ShadowToneGenerator.reset()
        shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
    }

    @After fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        BatteryStateBus.clearForTest()
    }

    // ── construction ───────────────────────────────────────────────────────────

    /** Paused by default, which skips the notify loop's Home Assistant battery
     *  send, so a test sees only the BatteryStateBus update. */
    private fun prefs(paused: Boolean = true) = Prefs(app).apply {
        autoLightModeEnabled = true
        // The camera under test has to BE the rider's. The loop reads
        // activeDashcamMac on every attempt, so a fixture that leaves the
        // ownership switch unanswered describes a camera the app must not link
        // to at all, and every test below would be asserting against a loop
        // that exited immediately.
        dashcamOwnership = DashcamOwnership.YES
        dashcamMac = this@CameraLightLinkControllerHarnessTest.mac
        pausedUntilEpochMs = if (paused) Long.MAX_VALUE else 0L
    }

    private fun haPublisher(scope: kotlinx.coroutines.CoroutineScope, ha: RecordingHaClient) = HaPublisher(
        scope = scope,
        creds = HaCredentials(app),
        rideStats = { RideStatsAccumulator() },
        currentRadarMac = { null },
        macToSlug = { ConcurrentHashMap() },
        loadKnownDevices = { emptyList() },
        slug = { it.lowercase() },
        newClient = { _, _ -> ha },
    )

    /** Captured handle on the connection the controller opened. */
    private class Link {
        @Volatile var cb: BluetoothGattCallback? = null

        @Volatile var gatt: BluetoothGatt? = null
        var openCount = 0
    }

    private fun TestScope.controller(
        link: Link,
        returnNull: Boolean = false,
        setUp: (BluetoothGatt) -> Unit = ::setUpFrontCameraServices,
        prefs: Prefs = prefs(),
        scope: kotlinx.coroutines.CoroutineScope = backgroundScope,
        ha: RecordingHaClient = RecordingHaClient(),
        // Unconfigured unless a test is about the front-light mode send.
        frontModeHa: HaClient = HaClient("", ""),
        clock: () -> Long = { 0L },
        wallClock: () -> Long = { System.currentTimeMillis() },
    ): CameraLightLinkController = CameraLightLinkController(
        context = app,
        scope = scope,
        prefs = prefs,
        ha = { frontModeHa },
        haPublisher = haPublisher(backgroundScope, ha),
        notifications = ServiceNotifications(app) { Prefs(app) },
        macToSlug = ConcurrentHashMap(),
        slug = { it.lowercase() },
        radarOffSinceMs = { null },
        journal = { journal += it },
        dashcamLink = gateway,
        clock = clock,
        wallClock = wallClock,
        openGatt = { ctx, dev, _, cb ->
            link.openCount++
            link.cb = cb
            if (returnNull) {
                null
            } else {
                @Suppress("DEPRECATION") // 3-arg connectGatt: Robolectric harness setup
                val g = dev.connectGatt(ctx, false, cb)
                setUp(g)
                link.gatt = g
                g
            }
        },
    )

    // ── service maps ─────────────────────────────────────────────────────────

    private val propWrite = BluetoothGattCharacteristic.PROPERTY_WRITE
    private val propWriteNoResp = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    private val propNotify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private val propIndicate = BluetoothGattCharacteristic.PROPERTY_INDICATE
    private val propRead = BluetoothGattCharacteristic.PROPERTY_READ

    private fun char(uuid: UUID, props: Int, cccd: Boolean = false): BluetoothGattCharacteristic {
        val ch = BluetoothGattCharacteristic(
            uuid,
            props,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        if (cccd) {
            ch.addDescriptor(
                BluetoothGattDescriptor(
                    Uuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        return ch
    }

    private fun addService(gatt: BluetoothGatt, svc: UUID, vararg chars: BluetoothGattCharacteristic) {
        val s = BluetoothGattService(svc, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        chars.forEach { s.addCharacteristic(it) }
        shadowOf(gatt).addDiscoverableService(s)
    }

    /** Full front-camera service map: config (TX=2820, RX=2810), control with the
     *  mode-set ACK + the 2f14 state-notify, and battery. */
    private fun setUpFrontCameraServices(gatt: BluetoothGatt) {
        addService(
            gatt,
            Uuids.SVC_CONFIG,
            char(Uuids.CHAR_2820, propWriteNoResp or propWrite),
            char(Uuids.CHAR_2810, propNotify, cccd = true),
        )
        addService(
            gatt,
            Uuids.SVC_CONTROL,
            char(Uuids.SETTINGS_ACK, propIndicate or propWrite, cccd = true),
            char(Uuids.SETTINGS_14, propNotify, cccd = true),
        )
        addService(gatt, Uuids.SVC_BATTERY, char(Uuids.CHAR_BATTERY, propRead or propNotify, cccd = true))
        gatt.discoverServices()
    }

    /** Config service WITHOUT the 2820 handshake-TX char: the AMV handshake aborts
     *  at its TX-present check, exercising the quick-reconnect branch. */
    private fun setUpServicesMissingTx(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_CONFIG, char(Uuids.CHAR_2810, propNotify, cccd = true))
        addService(gatt, Uuids.SVC_CONTROL, char(Uuids.SETTINGS_ACK, propIndicate or propWrite, cccd = true))
        gatt.discoverServices()
    }

    /** No `discoverServices()` call, so Robolectric never auto-fires the SUCCESS
     *  `onServicesDiscovered`; the test then injects a FAILURE status by hand. */
    private fun setUpNoDiscover(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_CONFIG, char(Uuids.CHAR_2810, propNotify, cccd = true))
    }

    // ── driving helpers ────────────────────────────────────────────────────────

    /** Bootstrap the connection: drive STATE_CONNECTED, which re-runs
     *  `discoverServices()`. The service-map setup already auto-fired the SUCCESS
     *  `onServicesDiscovered`, so this only covers the connection-state branch. */
    private fun bootstrap(link: Link) {
        val cb = requireNotNull(link.cb) { "openGatt was not called - controller never connected" }
        val gatt = requireNotNull(link.gatt)
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    }

    /** Background loop completing the read/CCCD/MTU ops ShadowBluetoothGatt does
     *  not auto-fire, routed through the controller's own callback. Completers
     *  no-op unless their op is genuinely pending, so blanket-calling is safe. */
    private fun TestScope.startDriver(link: Link) {
        backgroundScope.launch {
            while (isActive) {
                val cb = link.cb
                val gatt = link.gatt
                if (cb != null && gatt != null) {
                    cb.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
                    complete(cb, gatt, Uuids.SVC_CONFIG, Uuids.CHAR_2810)
                    complete(cb, gatt, Uuids.SVC_CONTROL, Uuids.SETTINGS_ACK)
                    complete(cb, gatt, Uuids.SVC_CONTROL, Uuids.SETTINGS_14)
                    gatt.getService(Uuids.SVC_BATTERY)?.getCharacteristic(Uuids.CHAR_BATTERY)?.let {
                        cb.onCharacteristicRead(gatt, it, byteArrayOf(0x64), BluetoothGatt.GATT_SUCCESS)
                        cb.onDescriptorWrite(gatt, it.getDescriptor(Uuids.CCCD), BluetoothGatt.GATT_SUCCESS)
                    }
                }
                delay(10)
            }
        }
    }

    private fun complete(cb: BluetoothGattCallback, gatt: BluetoothGatt, svc: UUID, ch: UUID) {
        gatt.getService(svc)?.getCharacteristic(ch)?.getDescriptor(Uuids.CCCD)
            ?.let { cb.onDescriptorWrite(gatt, it, BluetoothGatt.GATT_SUCCESS) }
    }

    /** Push a notify frame onto the controller's channel via its callback. */
    private fun notify(link: Link, svc: UUID, ch: UUID, hex: String) {
        val cb = requireNotNull(link.cb)
        val gatt = requireNotNull(link.gatt)
        val char = gatt.getService(svc).getCharacteristic(ch)
        cb.onCharacteristicChanged(gatt, char, hex.hexToBytes())
    }

    /** The five FRONT_CAMERA handshake replies on RX (2810), in await order. */
    private fun feedHandshakeReplies(link: Link) {
        notify(link, Uuids.SVC_CONFIG, Uuids.CHAR_2810, "000600") // AMV open
        notify(link, Uuids.SVC_CONFIG, Uuids.CHAR_2810, "0001000000000000000004000040") // cmd-04
        notify(link, Uuids.SVC_CONFIG, Uuids.CHAR_2810, "0000000000000000414d561800") // 0x18 toggle 1
        notify(link, Uuids.SVC_CONFIG, Uuids.CHAR_2810, "0000000000000000414d561882") // 0x18 toggle 2
        notify(link, Uuids.SVC_CONFIG, Uuids.CHAR_2810, "0000000000000000414d561800") // 0x18 toggle 3
    }

    private suspend fun TestScope.pumpUntil(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!cond()) {
            runCurrent()
            delay(10)
        }
        true
    } ?: false

    private fun journalHas(needle: String) = journal.toList().any { it.contains(needle) }

    // ── happy path: connect -> handshake -> mode-state notify loop ──────────────

    @Test fun connectsHandshakesAndProcessesNotifyLoop() = runTest {
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue("openGatt must be called and the callback captured", pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)

        assertTrue("handshake must complete; journal=$journal", pumpUntil { journalHas("camera handshake complete") })
        assertTrue("link should report a live GATT once handshook", controller.isGattActive())

        // Mode-state frames first, then battery: a battery entry landing proves the
        // loop consumed the earlier 2f14 frames (the parse-null skip and the
        // override-detection branch) and kept running.
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "0102") // 2-byte: parseModeStateNotify -> null -> continue
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010500") // mode OFF: exercises the override branch
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "50") // 0x50 = 80%

        assertTrue(
            "battery notify must reach BatteryStateBus after the 2f14 frames",
            pumpUntil { BatteryStateBus.entries.value["cam"] != null },
        )
        assertEquals(80, BatteryStateBus.entries.value["cam"]?.pct)

        controller.stop()
    }

    /** A camera link through its handshake, with [ha] behind the publisher. */
    private suspend fun TestScope.handshookLink(
        ha: RecordingHaClient,
        prefs: Prefs,
        frontModeHa: HaClient = HaClient("", ""),
    ): Pair<Link, CameraLightLinkController> {
        val link = Link()
        val controller = controller(link, prefs = prefs, ha = ha, frontModeHa = frontModeHa)
        startDriver(link)
        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue("journal=$journal", pumpUntil { journalHas("camera handshake complete") })
        return link to controller
    }

    private suspend fun TestScope.assertBatteryStatesSent(ha: RecordingHaClient, expected: List<String>) {
        val sent = pumpUntil { ha.batteryStates("cam") == expected }
        assertTrue("sends=${ha.sends}", sent)
    }

    @Test fun aBatteryNotifyIsSentToHomeAssistant() = runTest {
        val ha = RecordingHaClient()
        val (link, controller) = handshookLink(ha, prefs(paused = false))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "3c")
        assertBatteryStatesSent(ha, listOf("60"))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "3b")
        assertBatteryStatesSent(ha, listOf("60", "59"))
        controller.stop()
    }

    @Test fun aPausedRiderSendsNoBatteryToHomeAssistant() = runTest {
        val ha = RecordingHaClient()
        val (link, controller) = handshookLink(ha, prefs(paused = true))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "3c")
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "3b")
        assertTrue(pumpUntil { BatteryStateBus.entries.value["cam"]?.pct == 59 })
        pumpUntil(timeoutMs = 300) { false }
        assertEquals(emptyList<Pair<String, String>>(), ha.sends.toList())
        controller.stop()
    }

    @Test fun aBatteryByteThatIsNotAPercentageGoesNowhere() = runTest {
        val ha = RecordingHaClient()
        val (link, controller) = handshookLink(ha, prefs(paused = false))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "3c")
        assertBatteryStatesSent(ha, listOf("60"))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "ff")
        pumpUntil(timeoutMs = 300) { false }
        assertEquals(60, BatteryStateBus.entries.value["cam"]?.pct)
        assertEquals(listOf("60"), ha.batteryStates("cam"))
        controller.stop()
    }

    @Test fun aRefusedBatteryByteStillMarksTheCameraSeen() = runTest {
        val (link, controller) = handshookLink(RecordingHaClient(), prefs(paused = false))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "3c")
        assertTrue(pumpUntil { BatteryStateBus.entries.value["cam"]?.pct == 60 })
        BatteryStateBus.update(BatteryEntry("cam", "Cam", 60, readAtMs = 1L, lastSeenElapsedMs = 1L))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "ff")
        assertTrue(
            "entry=${BatteryStateBus.entries.value["cam"]}",
            pumpUntil(timeoutMs = 1_000) { (BatteryStateBus.entries.value["cam"]?.readAtMs ?: 0L) > 1L },
        )
        val entry = requireNotNull(BatteryStateBus.entries.value["cam"])
        assertTrue("the probe's monotonic stamp moves too; entry=$entry", entry.lastSeenElapsedMs > 1L)
        assertEquals(60, entry.pct)
        controller.stop()
    }

    @Test fun theEndsOfTheRangeArePercentagesAndOnePastIsNot() = runTest {
        val ha = RecordingHaClient()
        val (link, controller) = handshookLink(ha, prefs(paused = false))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "00")
        assertBatteryStatesSent(ha, listOf("0"))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "64")
        assertBatteryStatesSent(ha, listOf("0", "100"))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "65")
        pumpUntil(timeoutMs = 300) { false }
        assertEquals(100, BatteryStateBus.entries.value["cam"]?.pct)
        assertEquals(listOf("0", "100"), ha.batteryStates("cam"))
        controller.stop()
    }

    @Test fun aHungBatterySendDoesNotHoldTheNextBatteryReading() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        ha.answer = { topic, _ ->
            if (topic == "bikeradar/cam/battery") release.await()
            true
        }
        val (link, controller) = handshookLink(ha, prefs(paused = false))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "50")
        assertBatteryStatesSent(ha, listOf("80"))
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "3c")
        assertTrue(
            "the next reading must reach the bus while the send hangs",
            pumpUntil(timeoutMs = 100) { BatteryStateBus.entries.value["cam"]?.pct == 60 },
        )
        release.complete(Unit)
        controller.stop()
    }

    // ── the front-light mode send ──────────────────────────────────────────────

    /** Both auto-mode phases set the same mode, so the first send's value does
     *  not depend on the time of day. */
    private fun frontModePrefs() = prefs(paused = false).apply {
        cameraLightDayMode = CameraLightMode.LOW
        cameraLightNightMode = CameraLightMode.LOW
    }

    /** Holds every send to [topic] until [release] completes, deaf to
     *  cancellation when [ignoringCancel]. */
    private fun hang(ha: RecordingHaClient, topic: String, release: CompletableDeferred<Unit>, ignoringCancel: Boolean = false) {
        ha.answer = { t, _ ->
            if (t == topic) {
                if (ignoringCancel) withContext(NonCancellable) { release.await() } else release.await()
            }
            true
        }
    }

    private fun inFlight(ha: RecordingHaClient, topic: String) = ha.sends.toList().any { it.first == topic }

    @Test fun aHungFrontModeSendDoesNotHoldTheCameraLoop() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        hang(ha, "bikeradar/cam/front_mode", release)
        val (link, controller) = handshookLink(ha, frontModePrefs(), frontModeHa = ha)
        assertTrue("sends=${ha.sends}", pumpUntil { inFlight(ha, "bikeradar/cam/front_mode") })
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010500")
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010000")
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "50")
        assertTrue(
            "the loop must read on past the light-state frames while the send hangs",
            pumpUntil(timeoutMs = 100) { BatteryStateBus.entries.value["cam"]?.pct == 80 },
        )
        assertBatteryStatesSent(ha, listOf("80"))
        release.complete(Unit)
        controller.stop()
    }

    @Test fun aHungFrontModeDiscoveryDoesNotHoldTheCameraLoop() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        hang(ha, ha.frontModeDiscoveryTopic("cam"), release)
        val (link, controller) = handshookLink(ha, frontModePrefs(), frontModeHa = ha)
        assertTrue("sends=${ha.sends}", pumpUntil { inFlight(ha, ha.frontModeDiscoveryTopic("cam")) })
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "50")
        assertTrue(
            "the loop must run while the discovery hangs",
            pumpUntil(timeoutMs = 100) { BatteryStateBus.entries.value["cam"]?.pct == 80 },
        )
        // Discovery goes first, so the mode waits behind it rather than racing it.
        assertEquals(emptyList<String>(), ha.frontModeStates("cam"))
        release.complete(Unit)
        assertTrue("sends=${ha.sends}", pumpUntil { ha.frontModeStates("cam") == listOf("LOW") })
        controller.stop()
    }

    @Test fun theNewestFrontModeGoesNextWhenSeveralWaitBehindAHungSend() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        hang(ha, "bikeradar/cam/front_mode", release)
        val (link, controller) = handshookLink(ha, frontModePrefs(), frontModeHa = ha)
        assertTrue("sends=${ha.sends}", pumpUntil { inFlight(ha, "bikeradar/cam/front_mode") })
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010000")
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010100")
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010500")
        pumpUntil(timeoutMs = 300) { false }
        release.complete(Unit)
        pumpUntil(timeoutMs = 300) { false }
        assertEquals(listOf("LOW", "OFF"), ha.frontModeStates("cam"))
        controller.stop()
    }

    @Test fun aLinkEndsWhileAFrontModeSendHangsDeafToCancellation() = runTest {
        val ha = RecordingHaClient()
        val release = CompletableDeferred<Unit>()
        hang(ha, "bikeradar/cam/front_mode", release, ignoringCancel = true)
        val (_, controller) = handshookLink(ha, frontModePrefs(), frontModeHa = ha)
        assertTrue("sends=${ha.sends}", pumpUntil { inFlight(ha, "bikeradar/cam/front_mode") })
        controller.stop()
        assertTrue("the loop must end with the send still hung; reports=$reports", pumpUntil(timeoutMs = 1_000) { reports.lastOrNull() == "gone" })
        release.complete(Unit)
    }

    @Test fun theFrontModeReachesHomeAssistant() = runTest {
        val ha = RecordingHaClient()
        val (link, controller) = handshookLink(ha, frontModePrefs(), frontModeHa = ha)
        assertTrue("sends=${ha.sends}", pumpUntil { ha.frontModeStates("cam") == listOf("LOW") })
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010500")
        assertTrue("sends=${ha.sends}", pumpUntil { ha.frontModeStates("cam") == listOf("LOW", "OFF") })
        // Discovery once per device, not per connection. The OFF read as a
        // press, so the next connection sets no mode of its own.
        disconnect(link, 147)
        handshake(link, 2)
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010200")
        assertTrue("sends=${ha.sends}", pumpUntil { ha.frontModeStates("cam") == listOf("LOW", "OFF", "LOW") })
        assertEquals(1, ha.frontModeDiscoveries("cam"))
        controller.stop()
    }

    /** The full front-camera table, with a mode-set characteristic that refuses writes. */
    private fun setUpCameraRefusingLightWrites(gatt: BluetoothGatt) {
        addService(
            gatt,
            Uuids.SVC_CONFIG,
            char(Uuids.CHAR_2820, propWriteNoResp or propWrite),
            char(Uuids.CHAR_2810, propNotify, cccd = true),
        )
        addService(
            gatt,
            Uuids.SVC_CONTROL,
            char(Uuids.SETTINGS_ACK, propIndicate, cccd = true),
            char(Uuids.SETTINGS_14, propNotify, cccd = true),
        )
        addService(gatt, Uuids.SVC_BATTERY, char(Uuids.CHAR_BATTERY, propRead or propNotify, cccd = true))
        gatt.discoverServices()
    }

    @Test fun aCameraWhoseLightIsNeverSetStillGetsItsEntity() = runTest {
        val ha = RecordingHaClient()
        val link = Link()
        val controller = controller(link, prefs = frontModePrefs(), ha = ha, frontModeHa = ha, setUp = ::setUpCameraRefusingLightWrites)
        startDriver(link)
        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue("journal=$journal", pumpUntil { journalHas("camera handshake complete") })
        assertTrue("sends=${ha.sends}", pumpUntil { ha.frontModeDiscoveries("cam") == 1 })
        pumpUntil(timeoutMs = 5_000) { false }
        assertEquals(emptyList<String>(), ha.frontModeStates("cam"))
        controller.stop()
    }

    // ── reconnect loop continues after a normal disconnect ──────────────────────

    @Test fun reconnectsAfterDisconnect() = runTest {
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue(pumpUntil { journalHas("camera handshake complete") })

        // Disconnect ends the notify loop; the outer loop grows backoff, waits, and
        // opens a second connection (openGatt called again).
        val cb = requireNotNull(link.cb)
        val gatt = requireNotNull(link.gatt)
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        assertTrue("the reconnect loop must open a second connection", pumpUntil { link.openCount >= 2 })
        controller.stop()
    }

    // ── services-discovery failure short-circuits the attempt ───────────────────

    @Test fun serviceDiscoveryFailureEndsAttempt() = runTest {
        val link = Link()
        val controller = controller(link, setUp = ::setUpNoDiscover)

        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        // No connection-state drive (which would re-run discoverServices and
        // auto-succeed); inject a FAILURE discovery directly.
        requireNotNull(link.cb).onServicesDiscovered(requireNotNull(link.gatt), BluetoothGatt.GATT_FAILURE)

        assertTrue(
            "a failed discovery must be journalled and end the attempt",
            pumpUntil { journalHas("camera services discovery failed") },
        )
        controller.stop()
    }

    // ── handshake abort takes the quick-reconnect branch ────────────────────────

    @Test fun handshakeAbortTakesQuickReconnect() = runTest {
        val link = Link()
        val p = prefs()
        p.cameraLinkProbe = null
        val controller = controller(link, setUp = ::setUpServicesMissingTx, prefs = p)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)

        assertTrue(
            "a missing handshake TX char must abort into the quick-reconnect branch, naming the step",
            pumpUntil { journalHas("camera handshake failed at tx-char-missing (quick reconnect)") },
        )
        // The slot the diagnostic bundle prints. Without this the probe call
        // can be deleted and nothing here fails, which is what shipped: the
        // journal line above proves the branch ran, not that it was recorded.
        assertTrue(pumpUntil { p.cameraLinkProbe != null })
        val probe = requireNotNull(p.cameraLinkProbe)
        assertTrue("expected the stopping point, got: $probe", probe.endsWith(" out=tx-char-missing"))
        controller.stop()
    }

    // ── connectGatt returning null is journalled and ends the attempt ───────────

    @Test fun nullGattIsHandled() = runTest {
        val link = Link()
        val p = prefs()
        // A previous attempt's answer, so this pins the actual defect: the
        // slot must not go on reporting a stopping point that a later attempt
        // never reached.
        p.cameraLinkProbe = LinkProbe.render(1_000L, LinkProbe.format(emptyList(), "handshake-ok"))
        val controller = controller(link, returnNull = true, prefs = p)

        controller.start("Cam", mac)
        assertTrue(
            "a null GATT must be journalled",
            pumpUntil { journalHas("camera connectGatt returned null") },
        )
        assertTrue(pumpUntil { p.cameraLinkProbe?.endsWith(" out=no-gatt") == true })
        val probe = requireNotNull(p.cameraLinkProbe)
        assertFalse("the previous answer must not survive, got: $probe", probe.contains("handshake-ok"))
        controller.stop()
    }

    // ── the rider's ownership switch ───────────────────────────────────────────

    @Test fun aCameraTheRiderSwitchedOffIsNeverConnectedTo() = runTest {
        // The pick survives the switch, so the mac alone no longer says the app
        // may use it. Without the loop's own check this connects and goes on
        // writing light modes to a camera the rider said they do not have.
        val link = Link()
        val p = prefs().apply { dashcamOwnership = DashcamOwnership.NO }
        val controller = controller(link, prefs = p)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue(
            "the loop must say why it stopped; journal=$journal",
            pumpUntil { journalHas("camera link exit: no longer the selected camera") },
        )
        assertEquals("and must never have opened a connection", 0, link.openCount)
        controller.stop()
    }

    @Test fun aLiveLinkDropsWhenItIsNoLongerTheSelectedCamera() = runTest {
        // Mid-session, so the captured mac is what matters: a non-null check
        // would keep this link alive against the camera it was started for.
        val link = Link()
        val p = prefs()
        val controller = controller(link, prefs = p)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue(pumpUntil { journalHas("camera handshake complete") })

        p.dashcamMac = "bb:bb:bb:11:22:33"
        val cb = requireNotNull(link.cb)
        val gatt = requireNotNull(link.gatt)
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        assertTrue(
            "the reconnect loop must not come back for the old camera",
            pumpUntil { journalHas("camera link exit: no longer the selected camera") },
        )
        assertEquals("and must not have reconnected to it", 1, link.openCount)
        controller.stop()
    }

    @Test fun handshakeCompletionRecordsTheCameraProbe() = runTest {
        // The camera's own slot, separate from the radar's: a bundle from a
        // rider with one working device and one broken one has to name which.
        val link = Link()
        val p = prefs()
        p.cameraLinkProbe = null
        val controller = controller(link, prefs = p)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue(pumpUntil { journalHas("camera handshake complete") })

        assertTrue(pumpUntil { p.cameraLinkProbe != null })
        val probe = requireNotNull(p.cameraLinkProbe)
        assertTrue("a completed handshake records its table too, got: $probe", probe.endsWith(" out=handshake-ok"))
        assertTrue("the discovered table must be there, got: $probe", probe.contains("2800["))
        controller.stop()
    }

    // ── what the walk-away alarm is told ───────────────────────────────────────

    private fun handshakesCompleted() = journal.toList().count { it == "camera handshake complete" }

    /** Drive the current connection through a completed handshake, the [n]th. */
    private suspend fun TestScope.handshake(link: Link, n: Int) {
        assertTrue(pumpUntil { link.openCount >= n && link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue("handshake $n must complete; journal=$journal", pumpUntil { handshakesCompleted() >= n })
    }

    private fun disconnect(link: Link, status: Int) {
        requireNotNull(link.cb).onConnectionStateChange(requireNotNull(link.gatt), status, BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test fun aCameraEndingAnEstablishedLinkIsReportedSwitchedOff() = runTest {
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("Cam", mac)
        handshake(link, 1)
        disconnect(link, 257)
        assertTrue(pumpUntil { link.openCount >= 2 })
        handshake(link, 2)
        disconnect(link, 19)
        assertTrue("reports=$reports", pumpUntil { reports.size >= 4 })
        assertEquals(listOf("heard", "off $mac", "heard", "off $mac"), reports.toList())
        controller.stop()
    }

    @Test fun aLinkStillInSetupThatEndsIsNotASwitchOff() = runTest {
        // The camera can end a link it is still setting up and be back in
        // seconds, still on.
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        disconnect(link, 257)
        assertTrue(pumpUntil { link.openCount >= 2 })
        assertEquals(emptyList<String>(), reports.toList())
        controller.stop()
    }

    @Test fun aTimeoutOnAnEstablishedLinkIsNotASwitchOff() = runTest {
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("Cam", mac)
        handshake(link, 1)
        disconnect(link, 147)
        assertTrue(pumpUntil { link.openCount >= 2 })
        assertEquals(listOf("heard"), reports.toList())
        controller.stop()
    }

    @Test fun onlyTheCamerasOwnEndingCountsAsASwitchOff() {
        for (status in listOf(0, 8, 22, 34, 133, 147)) {
            assertFalse("status $status", CameraLightLinkController.isSwitchOff(status, adapterOn = true, handshakeDone = true, endedByApp = false))
        }
        assertTrue(CameraLightLinkController.isSwitchOff(19, adapterOn = true, handshakeDone = true, endedByApp = false))
        assertTrue(CameraLightLinkController.isSwitchOff(257, adapterOn = true, handshakeDone = true, endedByApp = false))
        assertFalse(CameraLightLinkController.isSwitchOff(257, adapterOn = false, handshakeDone = true, endedByApp = false))
        assertFalse(CameraLightLinkController.isSwitchOff(257, adapterOn = true, handshakeDone = false, endedByApp = false))
        assertFalse(CameraLightLinkController.isSwitchOff(257, adapterOn = true, handshakeDone = true, endedByApp = true))
    }

    @Test fun aReopenThatFailsItsHandshakeReportsNothing() = runTest {
        // A camera powering down can open one more link, which then fails.
        val link = Link()
        val controller = controller(
            link,
            setUp = { g -> if (link.openCount >= 2) setUpServicesMissingTx(g) else setUpFrontCameraServices(g) },
        )
        startDriver(link)

        controller.start("Cam", mac)
        handshake(link, 1)
        disconnect(link, 257)
        assertTrue(pumpUntil { link.openCount >= 2 })
        bootstrap(link)
        assertTrue(pumpUntil { journalHas("camera handshake failed at tx-char-missing (quick reconnect)") })
        assertEquals(listOf("heard", "off $mac"), reports.toList())
        controller.stop()
    }

    @Test fun aLinkEndedAsBluetoothTurnsOffIsNotASwitchOff() = runTest {
        // Assumed from AOSP's order: the adapter leaves STATE_ON before the stack ends its links.
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("Cam", mac)
        handshake(link, 1)
        shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF)
        disconnect(link, 257)
        assertTrue(pumpUntil { link.openCount >= 2 })
        assertEquals(listOf("heard"), reports.toList())
        controller.stop()
    }

    @Test fun aSwitchOffJustAfterTheHandshakeIsReported() = runTest {
        // The camera ends the link at the controller's first suspension after
        // the handshake, the mode-state subscribe, before anything after it
        // has run.
        val link = Link()
        val controller = controller(link)
        startDriver(link)
        onHeard = { backgroundScope.launch { disconnect(link, 257) } }

        controller.start("Cam", mac)
        handshake(link, 1)
        assertTrue("reports=$reports", pumpUntil { reports.size >= 2 })
        assertEquals(listOf("heard", "off $mac"), reports.toList())
        controller.stop()
    }

    @Test fun aLinkEndedByItsScopeIsNoSwitchOff() = runTest {
        // The connection's own teardown marks the link the app's, whatever
        // ended it, so a callback after it is not the camera.
        val link = Link()
        val linkScope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
        val controller = controller(link, scope = linkScope)
        startDriver(link)

        controller.start("Cam", mac)
        handshake(link, 1)
        linkScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertTrue("reports=$reports", pumpUntil { reports.lastOrNull() == "gone" })
        disconnect(link, 257)
        runCurrent()
        assertEquals(listOf("heard", "gone"), reports.toList())
    }

    @Test fun everyEndOfAConnectionIsReportedAfterTheHandshakeNamedTheCamera() = runTest {
        // What keeps the camera heard while its link is up, and only then.
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        disconnect(link, 147)
        assertTrue(pumpUntil { link.openCount >= 2 })
        handshake(link, 1)
        disconnect(link, 147)
        assertTrue(pumpUntil { link.openCount >= 3 })
        handshake(link, 2)
        disconnect(link, 257)
        assertTrue("edges=$edges", pumpUntil { link.openCount >= 4 })
        controller.stop()
        assertTrue(pumpUntil { edges.lastOrNull() == "gone" })
        assertEquals(
            listOf("down", "heard $mac", "down", "heard $mac", "off", "down", "down", "gone"),
            edges.toList(),
        )
    }

    @Test fun theLoopEndingReportsTheLinkGoneAndALinkTheAppEndedIsNoSwitchOff() = runTest {
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("Cam", mac)
        handshake(link, 1)
        controller.stop()
        // The stack's own callback, landing before the cancel has reached
        // the connection's teardown.
        disconnect(link, 257)
        assertTrue("reports=$reports", pumpUntil { reports.lastOrNull() == "gone" })
        assertEquals(listOf("heard", "gone"), reports.toList())
    }

    // ── the light set runs beside the notify loop ──────────────────────────────

    /** Forwards every callback the controller handles, except that the first
     *  [swallow] answers to a mode-set write are dropped, so each of those
     *  writes runs to the queue's op timeout. [ops] lists the mode-set writes
     *  in the order they went out. */
    private class SwallowingLightWrites(
        private val inner: BluetoothGattCallback,
        private var swallow: Int,
        private val ops: MutableList<String>,
    ) : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = inner.onConnectionStateChange(g, status, newState)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = inner.onServicesDiscovered(g, status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = inner.onDescriptorWrite(g, d, status)

        @Deprecated("API < 33 compat")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = inner.onCharacteristicRead(g, ch, status)
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) = inner.onCharacteristicRead(g, ch, value, status)
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid == Uuids.SETTINGS_ACK) {
                @Suppress("DEPRECATION")
                ops += "light " + ch.value.joinToString("") { "%02x".format(it) }
                if (swallow > 0) {
                    swallow--
                    return
                }
            }
            inner.onCharacteristicWrite(g, ch, status)
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = inner.onMtuChanged(g, mtu, status)

        @Deprecated("API < 33 compat")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) = inner.onCharacteristicChanged(g, ch)
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = inner.onCharacteristicChanged(g, ch, value)
    }

    /** Every connection's mode-set writes, in order. */
    private val gattOps: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun lightWrites() = gattOps.toList().filter { it.startsWith("light ") }

    private var lightConnections = 0

    /** The full front-camera table on every connection, each of which leaves
     *  its first [swallowedWrites] (given the connection's number, from 1)
     *  mode-set writes unanswered. */
    private fun slowLightCamera(swallowedWrites: (connection: Int) -> Int): (BluetoothGatt) -> Unit = { g ->
        lightConnections++
        shadowOf(g).setGattCallback(SwallowingLightWrites(shadowOf(g).gattCallback, swallowedWrites(lightConnections), gattOps))
        setUpFrontCameraServices(g)
    }

    /** A camera link through its handshake, whose mode-set writes are
     *  [setUp]'s. */
    private suspend fun TestScope.lightLink(
        setUp: (BluetoothGatt) -> Unit,
        prefs: Prefs = frontModePrefs(),
        ha: RecordingHaClient = RecordingHaClient(),
        clock: () -> Long = { 0L },
        wallClock: () -> Long = { System.currentTimeMillis() },
    ): Pair<Link, CameraLightLinkController> {
        val link = Link()
        val controller = controller(link, setUp = setUp, prefs = prefs, ha = ha, frontModeHa = ha, clock = clock, wallClock = wallClock)
        startDriver(link)
        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue("journal=$journal", pumpUntil { journalHas("camera handshake complete") })
        return link to controller
    }

    private fun lightFailNotified(): Boolean {
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        return shadowOf(nm).allNotifications.any {
            it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == "Dashcam light"
        }
    }

    private fun nackTones() = ShadowToneGenerator.getPlayedTones().count { it.type() == ToneGenerator.TONE_PROP_NACK }

    @Test fun anUnansweredLightSetDoesNotHoldTheCamerasNotifications() = runTest {
        // Three unanswered tries take about 17 s; neither a battery reading nor
        // a mode-state notify waits for them.
        val ha = RecordingHaClient()
        val (link, controller) = lightLink(slowLightCamera { 3 }, ha = ha)
        assertTrue("ops=$gattOps", pumpUntil { lightWrites().size == 1 })
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010500")
        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "50")
        assertTrue(
            "the battery reading must reach the bus while the set is unanswered",
            pumpUntil(timeoutMs = 100) { BatteryStateBus.entries.value["cam"]?.pct == 80 },
        )
        assertTrue("sends=${ha.sends}", pumpUntil(timeoutMs = 100) { ha.frontModeStates("cam") == listOf("OFF") })
        assertEquals("ops=$gattOps", 1, lightWrites().size)
        controller.stop()
    }

    /** Runs [block] in London on midsummer's day, with manual coordinates and
     *  a wall clock reading 9 s before sunset, so the sunset switch falls due
     *  9 s into the link. Day mode is High and night mode Low. */
    private suspend fun TestScope.nineSecondsBeforeSunset(block: suspend (prefs: Prefs, wallClock: () -> Long) -> Unit) {
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"))
        try {
            val p = frontModePrefs().apply {
                cameraLightDayMode = CameraLightMode.HIGH
                cameraLightNightMode = CameraLightMode.LOW
                setManualLocation(51.5074, -0.1278)
            }
            val sunset = requireNotNull(SunsetCalculator.sunsetEpochMs(java.time.LocalDate.of(2026, 6, 21), 51.5074, -0.1278))
            block(p) { sunset - 9_000L + testScheduler.currentTime }
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    @Test fun aFlipDueDuringTheInitialSetComesAfterIt() = runTest {
        // Three unanswered tries take about 17 s, past the switch's 9 s.
        nineSecondsBeforeSunset { p, wall ->
            val (_, controller) = lightLink(slowLightCamera { 3 }, prefs = p, wallClock = wall)
            assertTrue("ops=$gattOps", pumpUntil(timeoutMs = 30_000) { lightWrites().size == 4 })
            pumpUntil(timeoutMs = 3_000) { false }
            assertEquals(listOf("light 070001", "light 070001", "light 070001", "light 070003"), lightWrites())
            controller.stop()
        }
    }

    @Test fun aLinkLostDuringTheLightSetSoundsNoLightFailure() = runTest {
        // The last try is failed by the disconnect, not by the light.
        val (link, controller) = lightLink(slowLightCamera { Int.MAX_VALUE })
        assertTrue("ops=$gattOps", pumpUntil { lightWrites().size == 3 })
        disconnect(link, 147)
        assertTrue(pumpUntil { link.openCount >= 2 })
        pumpUntil(timeoutMs = 3_000) { false }
        assertFalse(lightFailNotified())
        assertEquals(0, nackTones())
        controller.stop()
    }

    @Test fun aSetTheAppEndsReportsNothing() = runTest {
        val (_, controller) = lightLink(slowLightCamera { Int.MAX_VALUE })
        assertTrue("ops=$gattOps", pumpUntil { lightWrites().size == 2 })
        controller.stop()
        pumpUntil(timeoutMs = 20_000) { false }
        assertEquals("ops=$gattOps", 2, lightWrites().size)
        assertFalse(lightFailNotified())
        assertEquals(0, nackTones())
    }

    @Test fun aRefusedSetOnALiveLinkIsReported() = runTest {
        val (_, controller) = lightLink(::setUpCameraRefusingLightWrites)
        assertTrue(pumpUntil { lightFailNotified() })
        assertTrue(pumpUntil { nackTones() == 1 })
        controller.stop()
    }

    @Test fun anEchoOfTheSetBeforeItsAnswerIsNotAPress() = runTest {
        // Connection 1 lands LOW. Connection 2 sets HIGH, and the camera's
        // mode-state echo of it arrives while the write is still unanswered.
        // Read as a press it would hold auto-mode off, and connection 3 would
        // set nothing.
        val ha = RecordingHaClient()
        val p = frontModePrefs()
        val (link, controller) = lightLink(slowLightCamera { if (it == 2) 1 else 0 }, prefs = p, ha = ha)
        assertTrue("sends=${ha.sends}", pumpUntil { ha.frontModeStates("cam") == listOf("LOW") })
        p.cameraLightDayMode = CameraLightMode.HIGH
        p.cameraLightNightMode = CameraLightMode.HIGH
        disconnect(link, 147)
        handshake(link, 2)
        assertTrue("ops=$gattOps", pumpUntil { lightWrites().size == 2 })
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "010000")
        assertTrue("ops=$gattOps", pumpUntil(timeoutMs = 20_000) { lightWrites().size == 3 })
        disconnect(link, 147)
        handshake(link, 3)
        assertTrue("connection 3 must set the light again; ops=$gattOps", pumpUntil { lightWrites().size == 4 })
        controller.stop()
    }
}
