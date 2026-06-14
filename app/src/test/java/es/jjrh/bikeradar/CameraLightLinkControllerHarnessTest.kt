// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
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
 * Robolectric harness for [CameraLightLinkController.runCameraLightConnection]
 * and its inner connect-and-run loop - the front camera/light reconnect ->
 * AMV (FRONT_CAMERA) handshake -> mode-state-notify path. The simpler twin of
 * the radar harness (no V2 decode, no data-flow watchdog), built first to
 * establish the fake-GATT driving pattern.
 *
 * Driving model (mirrors [RadarUnlockHarnessTest]):
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

    @Before fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        BatteryStateBus.clearForTest()
    }

    @After fun tearDown() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
        BatteryStateBus.clearForTest()
    }

    // ── construction ───────────────────────────────────────────────────────────

    private fun prefs() = Prefs(app).apply {
        autoLightModeEnabled = true
        // Paused: skips the HA battery-publish branch in the notify loop so the
        // assertion is the local BatteryStateBus update only.
        pausedUntilEpochMs = Long.MAX_VALUE
    }

    private fun haPublisher(scope: kotlinx.coroutines.CoroutineScope) = HaPublisher(
        scope = scope,
        creds = HaCredentials(app),
        rideStats = { RideStatsAccumulator() },
        currentRadarMac = { null },
        macToSlug = { ConcurrentHashMap() },
        loadKnownDevices = { emptyList() },
        slug = { it.lowercase() },
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
    ): CameraLightLinkController = CameraLightLinkController(
        context = app,
        scope = backgroundScope,
        prefs = prefs(),
        ha = { HaClient("", "") },
        haPublisher = haPublisher(backgroundScope),
        notifications = ServiceNotifications(app) { Prefs(app) },
        macToSlug = ConcurrentHashMap(),
        slug = { it.lowercase() },
        radarOffSinceMs = { null },
        journal = { journal += it },
        clock = { 0L },
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
        val controller = controller(link, setUp = ::setUpServicesMissingTx)
        startDriver(link)

        controller.start("Cam", mac)
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)

        assertTrue(
            "a missing handshake TX char must abort into the quick-reconnect branch",
            pumpUntil { journalHas("camera handshake failed (quick reconnect)") },
        )
        controller.stop()
    }

    // ── connectGatt returning null is journalled and ends the attempt ───────────

    @Test fun nullGattIsHandled() = runTest {
        val link = Link()
        val controller = controller(link, returnNull = true)

        controller.start("Cam", mac)
        assertTrue(
            "a null GATT must be journalled",
            pumpUntil { journalHas("camera connectGatt returned null") },
        )
        controller.stop()
    }
}
