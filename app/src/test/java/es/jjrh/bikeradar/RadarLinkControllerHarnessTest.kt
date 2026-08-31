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
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
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
        // Inline, like the other pipeline tests: this harness never reaches the
        // publish path, but leaving a real IO dispatcher here is the shape that
        // made the close-pass test flaky.
        ioDispatcher = Dispatchers.Unconfined,
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
        // Matches production, so a test that does not care about the stamp
        // still exercises the real default rather than a synthetic one.
        wallClock: () -> Long = { System.currentTimeMillis() },
        returnNull: Boolean = false,
        setUp: (BluetoothGatt) -> Unit = ::setUpRadarServices,
        captureLog: CaptureLogManager = CaptureLogManager(externalFilesDir = { null }, captureLoggingEnabled = { false }),
        // Defaults to SUCCESS, which is what a supported Android does. The real
        // reflection call cannot be used here at all: Robolectric has no hidden
        // refresh(), so it always returns false, and every legacy test would
        // pin the refresh-FAILED branch - the one that refuses the fallback -
        // while appearing to test the fallback itself.
        refreshGatt: (BluetoothGatt) -> Boolean = { true },
    ): RadarLinkController = RadarLinkController(
        context = app,
        scope = backgroundScope,
        prefs = prefs,
        captureLog = captureLog,
        overlayPipeline = overlayPipeline(prefs),
        haPublisher = haPublisher(backgroundScope),
        notifications = ServiceNotifications(app) { Prefs(app) },
        linkState = gateway,
        macToSlug = ConcurrentHashMap(),
        slug = { it.lowercase() },
        journal = { journal += it },
        clock = clock,
        wallClock = wallClock,
        refreshGatt = refreshGatt,
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

    /** A legacy radar: the radar service carries the V1 characteristic and NO
     *  V2 one, and there is no config service, so the handshake aborts at
     *  `tx-char-missing` and the legacy fallback is eligible. */
    private fun setUpLegacyOnlyRadar(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_RADAR, char(Uuids.RADAR_V1, propNotify, cccd = true))
        gatt.discoverServices()
    }

    /** The same legacy radar, plus the standard battery service. That service
     *  is the only battery reading such a radar can give: it aborts before the
     *  setup sequence reaches its own battery step. */
    private fun setUpLegacyOnlyRadarWithBattery(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_RADAR, char(Uuids.RADAR_V1, propNotify, cccd = true))
        addService(gatt, Uuids.SVC_BATTERY, char(Uuids.CHAR_BATTERY, propNotify or propRead, cccd = true))
        gatt.discoverServices()
    }

    /** A V2-capable radar whose handshake still aborts: the radar service has
     *  BOTH characteristics, so the fallback must refuse it however the
     *  handshake ends. This is the shape that would be pinned into the legacy
     *  stream if the gate ever regressed. */
    private fun setUpV2RadarWithFailingHandshake(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_CONFIG, char(Uuids.HANDSHAKE_RX, propNotify, cccd = true))
        addService(
            gatt,
            Uuids.SVC_RADAR,
            char(Uuids.RADAR_V2, propNotify, cccd = true),
            char(Uuids.RADAR_V1, propNotify, cccd = true),
        )
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
        val p = prefs()
        p.radarLinkProbe = null
        val controller = controller(link, prefs = p, gateway = gateway)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue("openGatt must be called and the callback captured", pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)

        assertTrue("handshake must complete; journal=$journal", pumpUntil { journalHas("radar handshake complete") })
        assertTrue("the link state must be marked connected", gateway.connects >= 1)

        // A working radar records its table too, so a later report has a
        // baseline to compare an aborting one against.
        assertTrue(pumpUntil { p.radarLinkProbe != null })
        val probe = requireNotNull(p.radarLinkProbe)
        assertTrue("expected the radar service in the table, got: $probe", probe.contains("3200[3204]"))
        assertTrue("expected the completed-handshake outcome, got: $probe", probe.endsWith(" out=handshake-ok"))
        // This construction takes the production stamp source. A maintainer who
        // swapped it for the monotonic clock beside it, or for a constant, would
        // leave every rider a well-formed probe nobody can date.
        val stamp = requireNotNull(probe.removePrefix("since=").substringBefore(' ').toLongOrNull()) {
            "expected a numeric since= stamp, got: $probe"
        }
        val now = System.currentTimeMillis()
        assertTrue("stamp $stamp is not a wall-clock reading (now=$now)", stamp in (now - 600_000)..now)

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
        // Waits for the VALUE, not merely for an entry to exist. The handshake
        // publishes its own battery read before this point, so "an entry
        // appeared" is satisfied the moment the link comes up and would let
        // this assert the handshake's reading instead of the notify's.
        assertTrue(
            "battery notify must reach BatteryStateBus",
            pumpUntil { BatteryStateBus.entries.value["testradar"]?.pct == 80 },
        )
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
        val p = prefs()
        // Seeded with a previous attempt's answer, because that is the defect:
        // an exit that records nothing leaves the slot naming a stopping point
        // this attempt never reached, and a bundle then misdates the failure.
        p.radarLinkProbe = LinkProbe.render(1_000L, LinkProbe.format(emptyList(), "handshake-ok"))
        val controller = controller(link, prefs = p, setUp = ::setUpNoDiscover)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        // No connection-state drive (which would re-run discoverServices and
        // auto-succeed); inject a FAILURE discovery directly.
        requireNotNull(link.cb).onServicesDiscovered(requireNotNull(link.gatt), BluetoothGatt.GATT_FAILURE)

        assertTrue(
            "a failed discovery must be journalled and end the attempt",
            pumpUntil { journalHas("radar services discovery failed") },
        )
        assertTrue(pumpUntil { p.radarLinkProbe?.endsWith(" out=discovery-failed") == true })
        val probe = requireNotNull(p.radarLinkProbe)
        assertFalse("the previous answer must not survive, got: $probe", probe.contains("handshake-ok"))
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
            "a missing handshake TX char must abort into the quick-reconnect branch, naming the step",
            pumpUntil { journalHas("radar handshake aborted at tx-char-missing (quick reconnect)") },
        )
        controller.forceReconnect()
    }

    /**
     * A radar that never reaches the decode loop opens no capture log, so the
     * probe in prefs is the only place a rider's diagnostic bundle can carry
     * what the device exposed and where the handshake stopped.
     */
    @Test fun handshakeAbortRecordsTheDiscoveredTableAndTheStoppingPoint() = runTest {
        val link = Link()
        val p = prefs()
        p.radarLinkProbe = null
        // Wall clock under the test's control, so the anti-rewrite assertion
        // below fails on a real rewrite instead of depending on two attempts
        // landing in different milliseconds.
        var wall = 1_786_034_957_178L
        val controller = controller(
            link,
            prefs = p,
            wallClock = { wall },
            setUp = ::setUpServicesMissingTx,
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue(pumpUntil { p.radarLinkProbe != null })

        // The stamp is the wall clock, not a placeholder: the maintainer reads
        // it as "failing this way since".
        assertEquals(
            "since=1786034957178 svc=2800[2811] out=tx-char-missing",
            p.radarLinkProbe,
        )

        // The abort loop retries with the same answer. Rewriting the pref each
        // time would churn SharedPreferences and fan out its change listener
        // for the whole ride, and would move the stamp so a bug report could
        // never show how long it has been failing. Moving the clock first means
        // a rewrite is visible rather than merely possible.
        wall += 60_000L
        assertTrue("expected a second attempt", pumpUntil { link.openCount >= 2 })
        assertEquals(
            "the unchanged result must not be rewritten",
            "since=1786034957178 svc=2800[2811] out=tx-char-missing",
            p.radarLinkProbe,
        )
        controller.forceReconnect()
    }

    // ── setup transcript: the capture log's reach ───────────────────────────────

    /** With the transcript off, a handshake-failing radar produces no capture
     *  file at all - the file opens only after a successful handshake. Pinned
     *  so the transcript path cannot quietly become the default. */
    @Test fun transcriptOffKeepsCaptureClosedThroughAnAbort() = runTest {
        val link = Link()
        val root = Files.createTempDirectory("radar-transcript-off").toFile()
        val p = prefs()
        p.captureLoggingEnabled = true
        val controller = controller(
            link,
            prefs = p,
            setUp = ::setUpServicesMissingTx,
            captureLog = CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true }),
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue(pumpUntil { journalHas("radar handshake aborted at tx-char-missing (quick reconnect)") })

        val dir = File(root, CaptureLogManager.CAPTURE_DIR)
        assertTrue(
            "no capture file for an aborting radar with the transcript off",
            !dir.exists() || dir.listFiles().isNullOrEmpty(),
        )
        controller.forceReconnect()
    }

    /** With the transcript on, the file opens before the connect and one file
     *  accumulates the retries: the abort reason and the discovered-service
     *  line - both dropped on the null writer otherwise - are in it. */
    @Test fun transcriptOnCapturesTheAbortingHandshakeAcrossRetries() = runTest {
        val link = Link()
        val root = Files.createTempDirectory("radar-transcript-on").toFile()
        val p = prefs()
        p.captureLoggingEnabled = true
        p.setupTranscriptEnabled = true
        val capture = CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true })
        val controller = controller(
            link,
            prefs = p,
            setUp = ::setUpServicesMissingTx,
            captureLog = capture,
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue(pumpUntil { journalHas("radar handshake aborted at tx-char-missing (quick reconnect)") })
        assertTrue("expected a second attempt", pumpUntil { link.openCount >= 2 })

        capture.flushNow()
        val files = File(root, CaptureLogManager.CAPTURE_DIR).listFiles()!!
        assertEquals("retries accumulate into one file, not one per attempt", 1, files.size)
        val lines = files.single().readLines()
        assertTrue(
            "the abort reason must be in the transcript: $lines",
            lines.any { it.contains("# script: ABORT: handshake TX characteristic not found") },
        )
        assertTrue(
            "the discovered-service line must be in the transcript",
            lines.any { it.startsWith("# services discovered status=") },
        )

        // The file the retries accumulated into still closes when the loop
        // exits, or the transcript never gzips and never appears in the Debug
        // screen's shareable list.
        controller.forceReconnect()
        assertTrue(
            "the transcript must close and gzip when the reconnect loop exits",
            pumpUntil {
                File(root, CaptureLogManager.CAPTURE_DIR)
                    .listFiles()?.any { it.name.endsWith(".log.gz") } ?: false
            },
        )
    }

    /**
     * Turning the transcript toggle off closes the accumulated file, WITHOUT
     * the loop being cancelled. This is the route the app itself tells riders
     * to use ("turn it off when you're done: the log appears in the list once
     * it closes"), and it works only because the pref is read per attempt: a
     * tidy-up hoisting that read out of the attempt would leave every rider
     * who follows the on-screen instruction with a file that never appears.
     */
    @Test fun turningTheTranscriptOffClosesTheAccumulatedFile() = runTest {
        val link = Link()
        val root = Files.createTempDirectory("radar-transcript-toggled-off").toFile()
        val p = prefs()
        p.captureLoggingEnabled = true
        p.setupTranscriptEnabled = true
        val controller = controller(
            link,
            prefs = p,
            setUp = ::setUpServicesMissingTx,
            captureLog = CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true }),
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue("expected a second attempt", pumpUntil { link.openCount >= 2 })

        p.setupTranscriptEnabled = false

        assertTrue(
            "the transcript must close and gzip once the toggle goes off, with the loop still running",
            pumpUntil {
                File(root, CaptureLogManager.CAPTURE_DIR)
                    .listFiles()?.any { it.name.endsWith(".log.gz") } ?: false
            },
        )
        controller.forceReconnect()
    }

    /**
     * Turning the capture-log MASTER switch off closes an open transcript at
     * the next attempt. The transcript spans the reconnect loop, so without
     * this the switch stops governing the file it opened, while its own
     * subtitle promises it takes effect on the next radar connection.
     */
    @Test fun turningCaptureLoggingOffClosesAnOpenTranscript() = runTest {
        val link = Link()
        val root = Files.createTempDirectory("radar-transcript-master-off").toFile()
        val p = prefs()
        p.captureLoggingEnabled = true
        p.setupTranscriptEnabled = true
        // Reads the live pref rather than a constant, which is what makes the
        // switch reachable from inside the manager at all.
        val controller = controller(
            link,
            prefs = p,
            setUp = ::setUpServicesMissingTx,
            captureLog = CaptureLogManager(
                externalFilesDir = { root },
                captureLoggingEnabled = { p.captureLoggingEnabled },
            ),
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue("expected a second attempt", pumpUntil { link.openCount >= 2 })

        p.captureLoggingEnabled = false

        assertTrue(
            "the master switch must close the open transcript at the next attempt",
            pumpUntil {
                File(root, CaptureLogManager.CAPTURE_DIR)
                    .listFiles()?.any { it.name.endsWith(".log.gz") } ?: false
            },
        )
        // And nothing reopens behind it while the loop keeps retrying.
        val before = link.openCount
        assertTrue(pumpUntil { link.openCount >= before + 2 })
        assertTrue(
            "no new plain log may be opened once logging is off",
            File(root, CaptureLogManager.CAPTURE_DIR)
                .listFiles()?.none { it.name.endsWith(".log") } ?: true,
        )
        controller.forceReconnect()
    }

    /**
     * A link that stops at a different step on alternating attempts keeps each
     * answer's OWN first-seen stamp. A single last-answer slot differs from the
     * previous answer every time, so it would rewrite every attempt and restamp
     * on every flip - and a flapping link is exactly the case the stamp is for.
     */
    @Test fun anAlternatingAbortKeepsEachAnswersOwnFirstSeenStamp() = runTest {
        val link = Link()
        val p = prefs()
        p.radarLinkProbe = null
        var wall = 1_000L
        // Odd attempts miss the TX characteristic; even attempts present the
        // full radar table, so the two answers alternate.
        val controller = controller(
            link,
            prefs = p,
            wallClock = { wall },
            setUp = { g -> if (link.openCount % 2 == 1) setUpServicesMissingTx(g) else setUpRadarServices(g) },
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue(pumpUntil { p.radarLinkProbe != null })
        val firstAnswer = p.radarLinkProbe
        assertEquals("since=1000 svc=2800[2811] out=tx-char-missing", firstAnswer)

        // Second attempt: a different answer, so a new stamp is correct.
        wall = 2_000L
        assertTrue(pumpUntil { link.openCount >= 2 })
        assertTrue(pumpUntil { p.radarLinkProbe != firstAnswer })
        val secondAnswer = p.radarLinkProbe
        assertTrue(
            "the second answer takes its own stamp: $secondAnswer",
            secondAnswer!!.startsWith("since=2000 "),
        )

        // Third attempt returns to the FIRST answer. Its stamp must be the
        // original one, not the clock as it now reads.
        wall = 9_000L
        assertTrue(pumpUntil { link.openCount >= 3 })
        assertTrue(pumpUntil { p.radarLinkProbe == firstAnswer })
        assertEquals(
            "returning to an answer must restore its first-seen stamp, not restamp it",
            "since=1000 svc=2800[2811] out=tx-char-missing",
            p.radarLinkProbe,
        )
        controller.forceReconnect()
    }

    /**
     * A stored answer seeds the debounce, so a restart does not rewrite an
     * unchanged result with a fresh stamp. Without it the field means "first
     * seen since the last reboot", and a radar failing the same way for weeks
     * reports seconds.
     */
    @Test fun aStoredProbeSurvivesARestartWithItsOriginalStamp() = runTest {
        val link = Link()
        val p = prefs()
        // As a previous process left it.
        p.radarLinkProbe = "since=1000 svc=2800[2811] out=tx-char-missing"
        val controller = controller(
            link,
            prefs = p,
            wallClock = { 9_999_999L },
            setUp = ::setUpServicesMissingTx,
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue("expected a second attempt", pumpUntil { link.openCount >= 2 })

        assertEquals(
            "a restart must not restamp an answer that has not changed",
            "since=1000 svc=2800[2811] out=tx-char-missing",
            p.radarLinkProbe,
        )
        controller.forceReconnect()
    }

    /** Transcript off, working radar: the per-attempt lifecycle is untouched -
     *  the file opens after the handshake and closes (gzipped) on teardown. */
    @Test fun captureClosesPerAttemptWithTranscriptOff() = runTest {
        val link = Link()
        val root = Files.createTempDirectory("radar-transcript-off-ok").toFile()
        val p = prefs()
        p.captureLoggingEnabled = true
        val controller = controller(
            link,
            prefs = p,
            captureLog = CaptureLogManager(externalFilesDir = { root }, captureLoggingEnabled = { true }),
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        feedHandshakeReplies(link)
        assertTrue(pumpUntil { journalHas("radar handshake complete") })

        // Radar drops; the reconnect loop stays alive. The attempt's file must
        // gzip NOW, not when the loop eventually exits.
        requireNotNull(link.cb).onConnectionStateChange(
            requireNotNull(link.gatt),
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED,
        )
        assertTrue(
            "the attempt's file must gzip on teardown while the loop is still running",
            pumpUntil {
                File(root, CaptureLogManager.CAPTURE_DIR)
                    .listFiles()?.any { it.name.endsWith(".log.gz") } ?: false
            },
        )
        controller.forceReconnect()
    }

    /**
     * A radar whose handshake aborts AFTER the battery step still reports its
     * battery.
     *
     * This is the only battery such a radar can produce: the notify loop that
     * normally feeds the bus is past the abort, and `scheduleRead` stands the
     * one-shot reader down for as long as the link coroutine is alive, which
     * for a permanently-aborting radar is forever. Before this, a rider in
     * that state saw no battery at all and the card could not read Connected.
     */
    @Test fun anAbortingRadarStillReportsTheBatteryTheHandshakeRead() = runTest {
        val link = Link()
        // Full service map, so the sequence gets past the battery read, but no
        // handshake replies are fed, so it aborts at the AMV open that follows.
        val controller = controller(link, setUp = ::setUpRadarServices)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue(pumpUntil { journal.any { it.startsWith("radar handshake aborted at") } })

        // 0x64 is what the harness driver answers the battery read with.
        assertTrue(
            "an aborting radar must still surface a battery reading",
            pumpUntil { BatteryStateBus.entries.value["testradar"]?.pct == 100 },
        )
        controller.forceReconnect()
    }

    // ── legacy-stream fallback: the gate, pinned in both directions ────────────

    /**
     * A radar with no V2 characteristic falls back to the legacy stream after
     * the handshake aborts, and its targets reach the state bus.
     *
     * This is the whole point of the fallback: a device that cannot run the
     * handshake at all still produces a usable radar.
     */
    @Test fun aRadarWithNoV2CharFallsBackToTheLegacyStream() = runTest {
        val link = Link()
        val controller = controller(link, setUp = ::setUpLegacyOnlyRadar)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        // The cache clear comes FIRST and costs a whole reconnect cycle. Pin
        // the order and the cost, not just the destination: without this the
        // branch can be deleted outright and this test still passes, because
        // the subscribe simply arrives one attempt earlier. That is exactly
        // how a maintainer reads an unexplained extra reconnect and removes it.
        assertTrue(
            "the first legacy candidate must clear the cache, not subscribe",
            pumpUntil { journalHas("radar legacy candidate; cache refresh=true") },
        )
        assertFalse(
            "no subscribe may happen on the attempt that found the candidate",
            journalHas("radar legacy stream subscribe"),
        )
        assertTrue(
            "the fallback must run after the abort",
            pumpUntil { journalHas("radar legacy stream subscribe ok=true") },
        )
        assertTrue(
            "the subscribe must land on a LATER connection than the candidate",
            link.openCount >= 2,
        )

        // One threat packet: track 1 at 24 m, with the vehicle-present bit set.
        requireNotNull(link.cb).onCharacteristicChanged(
            requireNotNull(link.gatt),
            requireNotNull(link.gatt).getService(Uuids.SVC_RADAR).getCharacteristic(Uuids.RADAR_V1),
            byteArrayOf(0x02, 0x81.toByte(), 24, 0x00),
        )
        assertTrue(
            "the decoded target must reach the state bus",
            pumpUntil { RadarStateBus.state.value.vehicles.any { it.distanceM == 24 } },
        )
        assertEquals(DataSource.V1, RadarStateBus.state.value.source)
        controller.forceReconnect()
    }

    /**
     * The legacy path reads the standard battery service, and follows its
     * notifications afterwards.
     *
     * This is the ONLY battery a radar on this path can report. It aborts
     * before the setup sequence reaches its own battery step, and the
     * one-shot reader stands down while a link is live, so without this read
     * the rider's battery chip stays empty for the whole ride.
     */
    @Test fun theLegacyPathReportsBatteryAndFollowsItsNotifications() = runTest {
        val link = Link()
        val controller = controller(link, setUp = ::setUpLegacyOnlyRadarWithBattery)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        // The driver answers the read with 0x64.
        assertTrue(
            "the one-shot read must reach the battery bus",
            pumpUntil { BatteryStateBus.entries.value["testradar"]?.pct == 100 },
        )
        assertTrue(pumpUntil { journalHas("radar legacy stream subscribe ok=true") })

        // And the subscribe is live: a later notification moves the value,
        // which a read-once-and-forget path would leave stuck at 100 all ride.
        requireNotNull(link.cb).onCharacteristicChanged(
            requireNotNull(link.gatt),
            requireNotNull(link.gatt).getService(Uuids.SVC_BATTERY).getCharacteristic(Uuids.CHAR_BATTERY),
            byteArrayOf(0x2A),
        )
        assertTrue(
            "a battery notification on the legacy link must update the bus",
            pumpUntil { BatteryStateBus.entries.value["testradar"]?.pct == 42 },
        )
        controller.forceReconnect()
    }

    /**
     * A heartbeat that changes no track still publishes, so the link reads
     * live.
     *
     * On an empty road heartbeats are the only traffic this stream carries.
     * Publishing only on a changed track set would leave the last-published
     * timestamp frozen at the moment the road cleared, and every consumer
     * scoring liveness off it - the Settings card, the overlay, the drop cue -
     * would call a perfectly healthy radar stale within seconds.
     */
    @Test fun aLegacyHeartbeatKeepsThePublishedStateFresh() = runTest {
        val link = Link()
        val controller = controller(link, setUp = ::setUpLegacyOnlyRadar)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue(pumpUntil { journalHas("radar legacy stream subscribe ok=true") })

        // A bare heartbeat on an empty road: the decoder has no track to
        // prune, so it reports no change and returns nothing to publish.
        // Publishing only on a decoder result would leave the bus untouched,
        // which is the frozen-timestamp bug: nothing here would ever mark the
        // source as live.
        notify(link, Uuids.SVC_RADAR, Uuids.RADAR_V1, "02")
        assertTrue(
            "a no-change heartbeat must still reach the state bus",
            pumpUntil { RadarStateBus.state.value.source == DataSource.V1 },
        )
        assertTrue(
            "and it carries no phantom target",
            RadarStateBus.state.value.vehicles.isEmpty(),
        )
        controller.forceReconnect()
    }

    /**
     * When the cache clear FAILS, the fallback is refused outright.
     *
     * `BluetoothGatt.refresh()` is a hidden method reached by reflection, and
     * a future Android could remove it. On that day no service table can be
     * verified, and the choice is between refusing the fallback and pinning
     * every V2 radar whose cached table happens to be missing 6a4e3204. The
     * rider whose radar is genuinely legacy loses a feature they did not have
     * before; the rider whose radar is healthy would lose V2 until they
     * power-cycled it. This test is the argument for that trade.
     *
     * It is reachable at all only because the refresh is an injected seam.
     * The real call returns false under Robolectric, so without the seam
     * every legacy test would pin this branch while appearing to test the
     * one that takes the fallback.
     */
    @Test fun aFailedCacheClearRefusesTheLegacyFallbackRatherThanRiskingThePin() = runTest {
        val link = Link()
        val controller = controller(
            link,
            setUp = ::setUpLegacyOnlyRadar,
            refreshGatt = { false },
        )
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue(
            "a failed cache clear must be journalled as the fallback being unavailable",
            pumpUntil { journalHas("radar legacy fallback unavailable (cache refresh failed)") },
        )
        // Several further attempts, so this is a refusal and not merely a
        // delay: the loop keeps retrying and must never reach the subscribe.
        assertTrue(pumpUntil { link.openCount >= 3 })
        assertFalse(
            "an unverifiable table must never reach the legacy subscribe",
            journalHas("radar legacy stream subscribe"),
        )
        controller.forceReconnect()
    }

    /** A legacy stream that goes silent is torn down by the same watchdog the
     *  V2 path uses. Without it a dead radar holds the link open and the
     *  reconnect loop never gets to try again. */
    @Test fun watchdogTearsDownSilentLegacyStream() = runTest {
        val link = Link()
        val clockMs = java.util.concurrent.atomic.AtomicLong(1_000L)
        val controller = controller(link, setUp = ::setUpLegacyOnlyRadar, clock = { clockMs.get() })
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        assertTrue(pumpUntil { journalHas("radar legacy stream subscribe ok=true") })

        clockMs.set(1_000L + RadarLinkController.V2_FRAME_STALL_MS + 2_000L)
        assertTrue(
            "a silent legacy stream must be torn down by the watchdog",
            pumpUntil { journalHas("radar legacy stream silent") },
        )
        controller.forceReconnect()
    }

    /**
     * A radar that HAS the V2 characteristic must never reach the legacy
     * subscribe, whatever the handshake does.
     *
     * Subscribing the legacy CCCD pins such a radar out of V2, and the pin
     * survives every later reconnect until the unit is power-cycled. So this
     * is not a preference about which stream is nicer: it is the guard that
     * stops a transient handshake failure costing a healthy radar its modern
     * stream. Do not relax the gate to a retry count.
     */
    @Test fun aV2CapableRadarNeverFallsBackHoweverTheHandshakeEnds() = runTest {
        val link = Link()
        val controller = controller(link, setUp = ::setUpV2RadarWithFailingHandshake)
        startDriver(link)

        controller.start("TestRadar", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)
        // The handshake aborts, exactly as for the legacy radar above.
        assertTrue(pumpUntil { journal.any { it.startsWith("radar handshake aborted at") } })
        // Give the loop several further attempts to be sure the fallback is
        // not merely late.
        assertTrue(pumpUntil { link.openCount >= 3 })

        assertFalse(
            "a radar with a V2 characteristic must never subscribe the legacy stream",
            journal.any { it.contains("legacy stream") },
        )
        controller.forceReconnect()
    }

    // ── connectGatt returning null is journalled and ends the attempt ───────────

    @Test fun nullGattIsHandled() = runTest {
        val link = Link()
        val p = prefs()
        p.radarLinkProbe = LinkProbe.render(1_000L, LinkProbe.format(emptyList(), "handshake-ok"))
        val controller = controller(link, prefs = p, returnNull = true)

        controller.start("TestRadar", mac)
        assertTrue(
            "a null GATT must be journalled",
            pumpUntil { journalHas("radar connectGatt returned null") },
        )
        assertTrue(pumpUntil { p.radarLinkProbe?.endsWith(" out=no-gatt") == true })
        val probe = requireNotNull(p.radarLinkProbe)
        assertFalse("the previous answer must not survive, got: $probe", probe.contains("handshake-ok"))
        controller.forceReconnect()
    }

    /**
     * Losing the bond and re-pairing must leave the link startable again.
     *
     * The deadlock this pins: bond loss cleared the MAC the receiver matches
     * on, so the BOND_BONDED branch that lifts the bond-lost flag could never
     * run, and start() refuses while that flag is set. The rider re-paired,
     * which is what the app's own notification tells them to do, and the link
     * stayed dead until the service was destroyed - no radar, no alerts.
     */
    @Test
    fun rePairingAfterBondLossIsRecognised() = runTest {
        val controller = controller(Link())
        controller.registerBondReceiver()
        // Arms the bond watch. The connection attempt itself is irrelevant.
        controller.start("RearVue8", mac)

        sendBondState(android.bluetooth.BluetoothDevice.BOND_NONE)
        assertTrue(
            "bond loss should stop the reconnect loop",
            journal.any { it.contains("bond removed") },
        )

        sendBondState(android.bluetooth.BluetoothDevice.BOND_BONDED)
        assertTrue(
            "re-pairing must lift the bond-lost gate",
            journal.any { it.contains("re-paired") },
        )
    }

    /**
     * A re-pair the BOND_BONDED broadcast cannot report must still lift the
     * gate, because the adapter is asked directly.
     *
     * Two ordinary situations produce no usable broadcast: the rider owns a
     * second radar whose sighting moves the bond watch on, and a re-pair that
     * lands on a different address. Both leave the watch pointing somewhere
     * other than the radar being re-paired, so the receiver's match fails and
     * the flag stays set for the life of the process. This drives the first:
     * a second radar takes the watch, then the original is re-paired with no
     * broadcast that can match it.
     */
    @Test
    fun aRePairNoBroadcastCanReportStillLiftsTheGate() = runTest {
        val otherRadar = "11:22:33:44:55:66"
        val controller = controller(Link())
        controller.registerBondReceiver()
        controller.start("RearVue8", mac)

        sendBondState(android.bluetooth.BluetoothDevice.BOND_NONE)
        assertTrue(
            "bond loss should stop the reconnect loop",
            journal.any { it.contains("bond removed") },
        )

        // A sighting of the rider's other radar takes the bond watch. It is
        // unbonded, so the gate holds and nothing is now watching `mac`.
        controller.start("RearVue8", otherRadar)
        assertFalse(
            "an unbonded radar must not lift the gate",
            controller.isActive(),
        )
        assertTrue(
            "the refusal must say it was the bond gate, not merely fail to start",
            journal.any { it.contains("start refused: bond lost") },
        )

        // The rider re-pairs the original radar in system Bluetooth settings.
        setBonded(mac)
        controller.start("RearVue8", mac)
        assertTrue(
            "a radar the adapter reports as bonded must be startable again",
            controller.isActive(),
        )
        assertTrue(
            "the journal must distinguish the adapter lift from a broadcast one",
            journal.any { it.contains("radar re-paired (adapter)") },
        )
        controller.forceReconnect()
    }

    /**
     * Recovering the link must also retract the notification that told the
     * rider to re-pair.
     *
     * Before the gate could actually lift this rarely happened, so the
     * notification outliving it went unnoticed. Now that a re-pair recovers,
     * a stale high-priority "re-pair your radar" would sit in the shade for
     * the rest of the process while the link runs and alerts fire.
     */
    @Test
    fun recoveringTheLinkRetractsTheRePairNotification() = runTest {
        val controller = controller(Link())
        controller.registerBondReceiver()
        controller.start("RearVue8", mac)

        sendBondState(android.bluetooth.BluetoothDevice.BOND_NONE)
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(
            "bond loss should post the re-pair notification",
            shadowOf(nm).allNotifications.isNotEmpty(),
        )

        sendBondState(android.bluetooth.BluetoothDevice.BOND_BONDED)
        assertTrue(
            "re-pairing must clear the re-pair notification",
            shadowOf(nm).allNotifications.isEmpty(),
        )
    }

    private fun setBonded(address: String) {
        val device = (
            app.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
            ).adapter.getRemoteDevice(address)
        shadowOf(device).setBondState(android.bluetooth.BluetoothDevice.BOND_BONDED)
    }

    private fun sendBondState(state: Int) {
        val device = (
            app.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
            ).adapter.getRemoteDevice(mac)
        val intent = android.content.Intent(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, device)
            .putExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, state)
        app.sendBroadcast(intent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
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
