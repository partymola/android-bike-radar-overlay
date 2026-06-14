// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.data.AndroidKeyStoreCryptor
import es.jjrh.bikeradar.data.HaCredentials
import es.jjrh.bikeradar.data.Prefs
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Robolectric harness for [RadarLinkController.runRadarConnection] and its inner
 * connect-and-run loop - the rear-radar reconnect -> AMV handshake ->
 * service-discovery -> CCCD-subscribe -> V2-decode loop, the safety-critical
 * alert hot path. The heavier twin of [CameraLightLinkControllerHarnessTest]
 * (adds the V2 decode and the data-flow watchdog).
 *
 * Driving model (see [CameraLightLinkControllerHarnessTest] / [RadarUnlockHarnessTest]):
 *  - The controller's [BluetoothGattCallback] is captured through the injected
 *    [openGatt] seam; the test hand-drives connect/discover/notify while a
 *    background driver completes the read/CCCD/MTU ops the shadow does not
 *    auto-fire (writes ARE auto-fired by ShadowBluetoothGatt).
 *  - The four RADAR handshake replies are pushed onto the notify channel in
 *    `awaitNotify` order; V2 + battery frames are fed only after the handshake
 *    completes (an `awaitNotify` loop would otherwise drop a non-RX frame).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RadarLinkControllerHarnessTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val journal = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var beeper: AlertBeeper

    @Before fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        RadarStateBus.clear()
        BatteryStateBus.clearForTest()
        beeper = AlertBeeper(
            audioManager = app.getSystemService(AudioManager::class.java),
            rotationProvider = { android.view.Surface.ROTATION_0 },
            onCue = {},
        )
    }

    @After fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        beeper.release()
        RadarStateBus.clear()
        BatteryStateBus.clearForTest()
    }

    // ── construction ───────────────────────────────────────────────────────────

    private fun prefs(radarLightAutoMode: Boolean = false) = Prefs(app).apply {
        radarLightAutoModeEnabled = radarLightAutoMode
        // Paused: skips the HA battery-publish branch in the notify loop so the
        // assertion is the local BatteryStateBus update only.
        pausedUntilEpochMs = Long.MAX_VALUE
    }

    private fun overlayPipeline(prefs: Prefs): OverlayPipeline = OverlayPipeline(
        prefs = prefs,
        ha = { HaClient("", "") },
        beeper = beeper,
        overlayHost = FakeOverlayHost(app),
        phoneBattery = object : PhoneBatterySource {
            override fun readSnapshot(): PhoneBatteryReading? = null
        },
        rideStats = { RideStatsAccumulator() },
        overlayPrefsSnapshot = { prefs.snapshot() },
        ebikeSnapshot = { null },
        climbingNow = { false },
        currentRadarMac = { mac },
        macToSlug = { emptyMap() },
        clog = {},
    )

    private fun haPublisher(scope: kotlinx.coroutines.CoroutineScope) = HaPublisher(
        scope = scope,
        creds = HaCredentials(app),
        rideStats = { RideStatsAccumulator() },
        currentRadarMac = { mac },
        macToSlug = { ConcurrentHashMap() },
        loadKnownDevices = { emptyList() },
        slug = { it.lowercase() },
    )

    /** A [RadarLinkStateGateway] double recording connect/disconnect calls. */
    private class FakeGateway : RadarLinkStateGateway {
        var connects = 0
        var disconnects = 0
        override fun markConnected() {
            connects++
        }
        override fun markDisconnected() {
            disconnects++
        }
        override fun snapshot(): RadarLinkState = RadarLinkState()
    }

    /** Captured handle on the connection the controller opened. */
    private class Link {
        @Volatile var cb: BluetoothGattCallback? = null

        @Volatile var gatt: BluetoothGatt? = null
        var openCount = 0
    }

    private fun TestScope.controller(
        link: Link,
        prefs: Prefs = prefs(),
        gateway: FakeGateway = FakeGateway(),
        clock: () -> Long = { 0L },
        returnNull: Boolean = false,
        setUp: (BluetoothGatt) -> Unit = ::setUpRadarServices,
    ): RadarLinkController = RadarLinkController(
        context = app,
        scope = backgroundScope,
        prefs = prefs,
        captureLog = CaptureLogManager(externalFilesDir = { null }, captureLoggingEnabled = { false }),
        overlayPipeline = overlayPipeline(prefs),
        haPublisher = haPublisher(backgroundScope),
        notifications = ServiceNotifications(app) { Prefs(app) },
        linkState = gateway,
        macToSlug = ConcurrentHashMap(),
        slug = { it.lowercase() },
        journal = { journal += it },
        clock = clock,
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

    /** Full rear-radar service map (config/control/battery/radar/DIS), plus the
     *  2f14 state-notify the radar-light auto-mode subscribes. */
    private fun setUpRadarServices(gatt: BluetoothGatt) {
        addService(
            gatt,
            Uuids.SVC_CONFIG,
            char(Uuids.HANDSHAKE_TX, propWriteNoResp or propWrite),
            char(Uuids.HANDSHAKE_RX, propNotify, cccd = true),
        )
        addService(
            gatt,
            Uuids.SVC_CONTROL,
            char(Uuids.SETTINGS_ACK, propIndicate or propWrite, cccd = true),
            char(Uuids.SETTINGS_14, propNotify, cccd = true),
        )
        addService(gatt, Uuids.SVC_BATTERY, char(Uuids.CHAR_BATTERY, propRead or propNotify, cccd = true))
        addService(gatt, Uuids.SVC_RADAR, char(Uuids.RADAR_V2, propNotify, cccd = true))
        addService(
            gatt,
            Uuids.SVC_DIS,
            char(Uuids.DIS_MODEL_NUMBER, propRead),
            char(Uuids.DIS_FIRMWARE_REV, propRead),
            char(Uuids.DIS_SERIAL_NUMBER, propRead),
        )
        gatt.discoverServices()
    }

    /** Config service WITHOUT the handshake-TX char: the AMV handshake aborts at
     *  its TX-present check, exercising the quick-reconnect branch. */
    private fun setUpServicesMissingTx(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_CONFIG, char(Uuids.HANDSHAKE_RX, propNotify, cccd = true))
        gatt.discoverServices()
    }

    /** No `discoverServices()` call, so Robolectric never auto-fires the SUCCESS
     *  `onServicesDiscovered`; the test then injects a FAILURE status by hand. */
    private fun setUpNoDiscover(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_CONFIG, char(Uuids.HANDSHAKE_RX, propNotify, cccd = true))
    }

    // ── driving helpers ────────────────────────────────────────────────────────

    /** Bootstrap the connection: drive STATE_CONNECTED, which re-runs
     *  `discoverServices()`. The service-map setup already auto-fired the SUCCESS
     *  `onServicesDiscovered`, so this only covers the connection-state branch. */
    private fun bootstrap(link: Link) {
        val cb = requireNotNull(link.cb) { "openGatt was not called - controller never connected" }
        cb.onConnectionStateChange(requireNotNull(link.gatt), BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    }

    /** Background loop completing the read/CCCD/MTU ops ShadowBluetoothGatt does
     *  not auto-fire, routed through the controller's own callback. */
    private fun TestScope.startDriver(link: Link) {
        backgroundScope.launch {
            while (isActive) {
                val cb = link.cb
                val gatt = link.gatt
                if (cb != null && gatt != null) {
                    cb.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
                    completeCccd(cb, gatt, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_RX)
                    completeCccd(cb, gatt, Uuids.SVC_CONTROL, Uuids.SETTINGS_ACK)
                    completeCccd(cb, gatt, Uuids.SVC_CONTROL, Uuids.SETTINGS_14)
                    completeCccd(cb, gatt, Uuids.SVC_RADAR, Uuids.RADAR_V2)
                    gatt.getService(Uuids.SVC_BATTERY)?.getCharacteristic(Uuids.CHAR_BATTERY)?.let {
                        cb.onCharacteristicRead(gatt, it, byteArrayOf(0x64), BluetoothGatt.GATT_SUCCESS)
                        cb.onDescriptorWrite(gatt, it.getDescriptor(Uuids.CCCD), BluetoothGatt.GATT_SUCCESS)
                    }
                    gatt.getService(Uuids.SVC_DIS)?.let { dis ->
                        listOf(Uuids.DIS_MODEL_NUMBER, Uuids.DIS_FIRMWARE_REV, Uuids.DIS_SERIAL_NUMBER).forEach { u ->
                            dis.getCharacteristic(u)?.let { cb.onCharacteristicRead(gatt, it, byteArrayOf(0x00), BluetoothGatt.GATT_SUCCESS) }
                        }
                    }
                }
                delay(10)
            }
        }
    }

    private fun completeCccd(cb: BluetoothGattCallback, gatt: BluetoothGatt, svc: UUID, ch: UUID) {
        gatt.getService(svc)?.getCharacteristic(ch)?.getDescriptor(Uuids.CCCD)
            ?.let { cb.onDescriptorWrite(gatt, it, BluetoothGatt.GATT_SUCCESS) }
    }

    /** Push a notify frame onto the controller's channel via its callback. */
    private fun notify(link: Link, svc: UUID, ch: UUID, hex: String) {
        val cb = requireNotNull(link.cb)
        val gatt = requireNotNull(link.gatt)
        cb.onCharacteristicChanged(gatt, gatt.getService(svc).getCharacteristic(ch), hex.hexToBytes())
    }

    /** The four RADAR handshake replies on RX (2811), in await order. */
    private fun feedHandshakeReplies(link: Link) {
        notify(link, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_RX, "000600") // AMV open
        notify(link, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_RX, "0001000000000000000004000040") // cmd-04
        notify(link, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_RX, "0001000000000000000016000000") // cmd-16
        notify(link, Uuids.SVC_CONFIG, Uuids.HANDSHAKE_RX, "80000102030405060708090a0b0c0d0e0f1011121314") // devId
    }

    // One 9-byte target struct behind a non-status header: tid 1, CLASS_NORMAL,
    // rangeX 0.0 m, rangeY 5.0 m, speedY -8.0 m/s, lateral-velocity sentinel.
    private val v2TargetFrame = "000001170090011008f080"

    private suspend fun TestScope.pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!cond()) {
            runCurrent()
            delay(10)
        }
        true
    } ?: false

    private fun journalHas(needle: String) = journal.toList().any { it.contains(needle) }

    // ── happy path: connect -> handshake -> V2 decode + battery ─────────────────

    @Test fun connectsHandshakesAndDecodesV2() = runTest {
        val link = Link()
        val gateway = FakeGateway()
        val controller = controller(link, gateway = gateway)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue("openGatt must be called and the callback captured", pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)

        assertTrue("handshake must complete; journal=$journal", pumpUntil { journalHas("radar handshake complete") })
        assertTrue("the link state must be marked connected", gateway.connects >= 1)

        notify(link, Uuids.SVC_RADAR, Uuids.RADAR_V2, v2TargetFrame)
        assertTrue(
            "a V2 target frame must decode onto RadarStateBus",
            pumpUntil { RadarStateBus.state.value.vehicles.isNotEmpty() },
        )
        val v = RadarStateBus.state.value.vehicles.single()
        assertEquals(1, v.id)
        assertEquals(5, v.distanceM)
        assertEquals(-8f, v.speedMs)

        notify(link, Uuids.SVC_BATTERY, Uuids.CHAR_BATTERY, "50") // 0x50 = 80%
        assertTrue("battery notify must reach BatteryStateBus", pumpUntil { BatteryStateBus.entries.value["testradar"] != null })
        assertEquals(80, BatteryStateBus.entries.value["testradar"]?.pct)

        controller.forceReconnect()
    }

    // ── radar-light auto-mode set + 2f14 override branch ────────────────────────

    @Test fun radarLightAutoModeRunsAndProcessesStateNotify() = runTest {
        val link = Link()
        val controller = controller(link, prefs = prefs(radarLightAutoMode = true))
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue(pumpUntil { journalHas("radar handshake complete") })

        // 2f14 mode-state frames: first sets the override baseline, the slot change
        // trips the override branch. A V2 frame landing after them proves the loop
        // processed both and kept decoding.
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "0100ff01") // baseline: slot 0
        notify(link, Uuids.SVC_CONTROL, Uuids.SETTINGS_14, "0101ff01") // slot 1: override
        notify(link, Uuids.SVC_RADAR, Uuids.RADAR_V2, v2TargetFrame)

        assertTrue(
            "the loop must keep decoding V2 after the 2f14 frames",
            pumpUntil { RadarStateBus.state.value.vehicles.isNotEmpty() },
        )
        controller.forceReconnect()
    }

    // ── data-flow watchdog tears down a silent V2 stream ────────────────────────

    @Test fun watchdogTearsDownSilentV2Stream() = runTest {
        val link = Link()
        val clockMs = java.util.concurrent.atomic.AtomicLong(1_000L)
        val controller = controller(link, clock = { clockMs.get() })
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        // The decode entry stamps lastV2FrameMs = clock() (1 s); feed no V2 frame.
        assertTrue(pumpUntil { journalHas("radar handshake complete") })

        // Jump the clock past the stall window so the next watchdog tick fires.
        clockMs.set(1_000L + RadarLinkController.V2_FRAME_STALL_MS + 2_000L)
        assertTrue(
            "a silent V2 stream must be torn down by the watchdog",
            pumpUntil { journalHas("V2 stream silent") },
        )
        controller.forceReconnect()
    }

    // ── reconnect loop continues after a healthy session disconnects ────────────

    @Test fun reconnectsAfterHealthyDisconnect() = runTest {
        val link = Link()
        val controller = controller(link)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue(pumpUntil { journalHas("radar handshake complete") })
        // Reach the decode loop so the healthy-session backoff reset runs.
        notify(link, Uuids.SVC_RADAR, Uuids.RADAR_V2, v2TargetFrame)
        assertTrue(pumpUntil { RadarStateBus.state.value.vehicles.isNotEmpty() })

        val cb = requireNotNull(link.cb)
        cb.onConnectionStateChange(requireNotNull(link.gatt), BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        assertTrue("the reconnect loop must open a second connection", pumpUntil { link.openCount >= 2 })
        controller.forceReconnect()
    }

    // ── services-discovery failure short-circuits the attempt ───────────────────

    @Test fun serviceDiscoveryFailureEndsAttempt() = runTest {
        val link = Link()
        val controller = controller(link, setUp = ::setUpNoDiscover)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        // No connection-state drive (which would re-run discoverServices and
        // auto-succeed); inject a FAILURE discovery directly.
        requireNotNull(link.cb).onServicesDiscovered(requireNotNull(link.gatt), BluetoothGatt.GATT_FAILURE)

        assertTrue(
            "a failed discovery must be journalled and end the attempt",
            pumpUntil { journalHas("radar services discovery failed") },
        )
        controller.forceReconnect()
    }

    // ── handshake abort takes the quick-reconnect branch ────────────────────────

    @Test fun handshakeAbortTakesQuickReconnect() = runTest {
        val link = Link()
        val controller = controller(link, setUp = ::setUpServicesMissingTx)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)

        assertTrue(
            "a missing handshake TX char must abort into the quick-reconnect branch",
            pumpUntil { journalHas("radar handshake aborted (quick reconnect)") },
        )
        controller.forceReconnect()
    }

    // ── connectGatt returning null is journalled and ends the attempt ───────────

    @Test fun nullGattIsHandled() = runTest {
        val link = Link()
        val controller = controller(link, returnNull = true)

        controller.start("TestRadar", mac)
        assertTrue(
            "a null GATT must be journalled",
            pumpUntil { journalHas("radar connectGatt returned null") },
        )
        controller.forceReconnect()
    }

    /** Test double that owns view-creation + tracks attach/detach calls. */
    private class FakeOverlayHost(private val ctx: android.content.Context) : OverlayHost {
        override fun createView(): RadarOverlayView = RadarOverlayView(ctx)
        override fun canDrawOverlays(): Boolean = true
        override fun attach(view: RadarOverlayView): Throwable? = null
        override fun detach(view: RadarOverlayView) {}
        override fun onConfigurationChanged() {}
    }
}
