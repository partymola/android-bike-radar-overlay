// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.UUID

/**
 * Robolectric harness for [EBikeStatusReader.runLoop] / connectAndRun - the
 * read-only Bosch status loop: reconnect -> service discovery -> CCCD subscribe
 * -> notify-decode. No handshake, so it is the simplest of the three BLE-loop
 * harnesses; the driving model is shared with
 * [CameraLightLinkControllerHarnessTest].
 *
 * The reader is strictly read-only (the only GATT write is the CCCD enable);
 * the source-scan contract is pinned separately by [EBikeStatusReaderReadOnlyTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EBikeStatusReaderLoopTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val snapshots = Collections.synchronizedList(mutableListOf<LiveDataSnapshot>())

    private class Link {
        @Volatile var cb: BluetoothGattCallback? = null

        @Volatile var gatt: BluetoothGatt? = null
        var openCount = 0
    }

    private fun TestScope.reader(
        link: Link,
        readerMac: String = mac,
        returnNull: Boolean = false,
        setUp: (BluetoothGatt) -> Unit = ::setUpStatusService,
    ): EBikeStatusReader = EBikeStatusReader(
        context = app,
        scope = backgroundScope,
        mac = readerMac,
        onSnapshot = { snapshots += it },
        log = { log += it },
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

    private fun statusChar(): BluetoothGattCharacteristic {
        val ch = BluetoothGattCharacteristic(
            Uuids.CHAR_EBIKE_STATUS,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        ch.addDescriptor(
            BluetoothGattDescriptor(
                Uuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        return ch
    }

    private fun addService(gatt: BluetoothGatt, svc: UUID, vararg chars: BluetoothGattCharacteristic) {
        val s = BluetoothGattService(svc, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        chars.forEach { s.addCharacteristic(it) }
        shadowOf(gatt).addDiscoverableService(s)
    }

    private fun setUpStatusService(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_EBIKE_STATUS, statusChar())
        gatt.discoverServices()
    }

    /** Status service present but WITHOUT the status characteristic. */
    private fun setUpServiceMissingChar(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_EBIKE_STATUS)
        gatt.discoverServices()
    }

    /** No `discoverServices()` call, so the SUCCESS `onServicesDiscovered` is not
     *  auto-fired and the test can inject a FAILURE status. */
    private fun setUpNoDiscover(gatt: BluetoothGatt) {
        addService(gatt, Uuids.SVC_EBIKE_STATUS, statusChar())
    }

    // ── driving helpers ────────────────────────────────────────────────────────

    private fun bootstrap(link: Link) {
        val cb = requireNotNull(link.cb) { "openGatt was not called - reader never connected" }
        cb.onConnectionStateChange(requireNotNull(link.gatt), BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    }

    /** Completes the one CCCD write the shadow does not auto-fire. */
    private fun TestScope.startDriver(link: Link) {
        backgroundScope.launch {
            while (isActive) {
                val cb = link.cb
                val gatt = link.gatt
                if (cb != null && gatt != null) {
                    gatt.getService(Uuids.SVC_EBIKE_STATUS)?.getCharacteristic(Uuids.CHAR_EBIKE_STATUS)
                        ?.getDescriptor(Uuids.CCCD)
                        ?.let { cb.onDescriptorWrite(gatt, it, BluetoothGatt.GATT_SUCCESS) }
                }
                delay(10)
            }
        }
    }

    private fun notify(link: Link, hex: String) {
        val cb = requireNotNull(link.cb)
        val gatt = requireNotNull(link.gatt)
        cb.onCharacteristicChanged(
            gatt,
            gatt.getService(Uuids.SVC_EBIKE_STATUS).getCharacteristic(Uuids.CHAR_EBIKE_STATUS),
            hex.hexToBytes(),
        )
    }

    private suspend fun TestScope.pumpUntil(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!cond()) {
            runCurrent()
            delay(10)
        }
        true
    } ?: false

    private fun logHas(needle: String) = log.toList().any { it.contains(needle) }

    // ── happy path: subscribe -> decode a status frame ──────────────────────────

    @Test fun subscribesAndDecodesStatusFrame() = runTest {
        val link = Link()
        val reader = reader(link)
        startDriver(link)

        reader.start()
        assertTrue("openGatt must be called and the callback captured", pumpUntil { link.cb != null })
        bootstrap(link)

        assertTrue("the reader must subscribe; log=$log", pumpUntil { logHas("subscribed; streaming") })

        // Battery SoC record 0x8088 = 72 %.
        notify(link, "300480880848")
        assertTrue("a status frame must decode to a snapshot", pumpUntil { snapshots.isNotEmpty() })
        assertEquals(72, snapshots.last().batterySoc)

        reader.shutdown()
    }

    // ── services-discovery failure short-circuits the attempt ───────────────────

    @Test fun serviceDiscoveryFailureEndsAttempt() = runTest {
        val link = Link()
        val reader = reader(link, setUp = ::setUpNoDiscover)

        reader.start()
        assertTrue(pumpUntil { link.cb != null })
        // No connection-state drive (which would re-run discoverServices and
        // auto-succeed); inject a FAILURE discovery directly.
        requireNotNull(link.cb).onServicesDiscovered(requireNotNull(link.gatt), BluetoothGatt.GATT_FAILURE)

        assertTrue(pumpUntil { logHas("service discovery failed") })
        reader.shutdown()
    }

    // ── status characteristic absent short-circuits the attempt ─────────────────

    @Test fun missingStatusCharEndsAttempt() = runTest {
        val link = Link()
        val reader = reader(link, setUp = ::setUpServiceMissingChar)

        reader.start()
        assertTrue(pumpUntil { link.cb != null })
        bootstrap(link)

        assertTrue(pumpUntil { logHas("status characteristic not found") })
        reader.shutdown()
    }

    // ── a null GATT is retried by the reconnect loop ────────────────────────────

    @Test fun nullGattIsRetried() = runTest {
        val link = Link()
        val reader = reader(link, returnNull = true)

        reader.start()
        assertTrue("a null GATT must drive a reconnect attempt", pumpUntil { link.openCount >= 2 })
        reader.shutdown()
    }

    // ── start() is idempotent while a loop is already running ────────────────────

    @Test fun startIsIdempotentWhileRunning() = runTest {
        // setUpNoDiscover never completes servicesReady, so the first connectAndRun
        // parks at `servicesReady.await()` and cannot progress or reconnect on its
        // own - no driver can complete it. openCount therefore moves only when a
        // start() call actually launches a loop, which is exactly what the
        // loopJob-guard controls. (Do NOT add a driver here: the point is a
        // connection that stays parked so a second openGatt can only come from a
        // second loop.)
        val link = Link()
        val reader = reader(link, setUp = ::setUpNoDiscover)

        reader.start()
        runCurrent()
        assertEquals("the first start() opens exactly one connection", 1, link.openCount)

        reader.start() // guarded by loopJob != null
        runCurrent()
        assertEquals("a second start() while running must not open a second connection", 1, link.openCount)

        reader.shutdown()
    }

    // ── start() bails out on an unresolvable address without launching ──────────

    @Test fun startBailsOnBadAddress() = runTest {
        val link = Link()
        val reader = reader(link, readerMac = "not-a-valid-mac")

        reader.start()
        runCurrent()
        assertEquals("a bad address must not open any connection", 0, link.openCount)
        reader.shutdown()
    }

    // ── findBondedEBikeMac name matching ────────────────────────────────────────

    private fun bondedDevice(addr: String, name: String?): android.bluetooth.BluetoothDevice {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val dev = adapter.getRemoteDevice(addr)
        if (name != null) shadowOf(dev).setName(name)
        return dev
    }

    private fun setBonded(vararg devices: android.bluetooth.BluetoothDevice) {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setBondedDevices(devices.toSet())
    }

    @Test fun findBondedEBikeMacMatchesSmartSystemAmongOthers() {
        val bike = bondedDevice("11:22:33:44:55:66", "smart system eBike")
        setBonded(bondedDevice("AA:00:00:00:00:01", "Pixel Buds"), bike)
        assertEquals("11:22:33:44:55:66", EBikeStatusReader.findBondedEBikeMac(app))
    }

    @Test fun findBondedEBikeMacMatchesEbikeSubstring() {
        setBonded(bondedDevice("11:22:33:44:55:77", "My eBike X"))
        assertEquals("11:22:33:44:55:77", EBikeStatusReader.findBondedEBikeMac(app))
    }

    @Test fun findBondedEBikeMacReturnsNullWhenNoMatchOrName() {
        // A non-matching device plus an unnamed one (exercises the null-name "" fallback).
        setBonded(bondedDevice("AA:00:00:00:00:02", "Headphones"), bondedDevice("AA:00:00:00:00:03", null))
        assertNull(EBikeStatusReader.findBondedEBikeMac(app))
    }

    @Test fun findBondedEBikeMacReturnsNullWhenNoneBonded() {
        setBonded()
        assertNull(EBikeStatusReader.findBondedEBikeMac(app))
    }
}
