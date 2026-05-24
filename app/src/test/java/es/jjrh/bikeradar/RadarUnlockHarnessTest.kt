// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/**
 * Robolectric harness for [RadarUnlock.runHandshake] - the long AMV write/await
 * orchestration that unlocks the V2 radar stream and the front-light control.
 *
 * Driving model:
 *  - A forwarding [BluetoothGattCallback] completes write ops automatically:
 *    Robolectric's ShadowBluetoothGatt synchronously fires `onCharacteristicWrite`
 *    for the API-33 `writeCharacteristic(char, value, type)` path.
 *  - Reads, CCCD writes, and the MTU request are NOT auto-fired by the shadow,
 *    so a background driver coroutine completes them by hand each virtual tick.
 *    The completers are UUID/type-matched, so blanket-calling them is safe - only
 *    the genuinely-pending op resolves.
 *  - Handshake reply frames are pre-loaded into the unlimited `notifies` channel
 *    in await order. Each frame matches only its own `awaitNotify` predicate, so
 *    the sequential awaits each consume their intended frame.
 *
 * The ABORT paths need no notify feed at all (TX missing) or a deliberately
 * starved feed (await times out under virtual time).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RadarUnlockHarnessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Builds a GATT whose callback forwards every completion into [queue]. */
    private fun forwardingGatt(queue: BleOpQueue): BluetoothGatt {
        val cb = object : BluetoothGattCallback() {
            override fun onCharacteristicWrite(g: BluetoothGatt?, c: BluetoothGattCharacteristic, s: Int) = queue.onCharacteristicWrite(c, s)
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, s: Int) = queue.onCharacteristicRead(c, v, s)
            override fun onDescriptorWrite(g: BluetoothGatt?, d: BluetoothGattDescriptor, s: Int) = queue.onDescriptorWrite(d, s)
            override fun onMtuChanged(g: BluetoothGatt?, m: Int, s: Int) = queue.onMtuChanged(m, s)
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF").connectGatt(context, false, cb)
    }

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

    private val propWrite = BluetoothGattCharacteristic.PROPERTY_WRITE
    private val propWriteNoResp = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    private val propNotify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private val propIndicate = BluetoothGattCharacteristic.PROPERTY_INDICATE
    private val propRead = BluetoothGattCharacteristic.PROPERTY_READ

    /** Installs the full rear-radar service map. */
    private fun setUpRadarServices(gatt: BluetoothGatt) {
        addService(
            gatt,
            Uuids.SVC_CONFIG,
            char(Uuids.HANDSHAKE_TX, propWriteNoResp or propWrite),
            char(Uuids.HANDSHAKE_RX, propNotify, cccd = true),
        )
        addService(gatt, Uuids.SVC_CONTROL, char(Uuids.SETTINGS_ACK, propIndicate or propWrite, cccd = true))
        addService(gatt, Uuids.SVC_BATTERY, char(Uuids.CHAR_BATTERY, propRead or propNotify, cccd = true))
        addService(
            gatt,
            Uuids.SVC_RADAR,
            char(Uuids.RADAR_V2, propNotify, cccd = true),
        )
        addService(
            gatt,
            Uuids.SVC_DIS,
            char(Uuids.DIS_MODEL_NUMBER, propRead),
            char(Uuids.DIS_FIRMWARE_REV, propRead),
            char(Uuids.DIS_SERIAL_NUMBER, propRead),
        )
        gatt.discoverServices()
    }

    /** Installs the front-camera/light service map (TX=2820, RX=2810). */
    private fun setUpFrontCameraServices(gatt: BluetoothGatt) {
        addService(
            gatt,
            Uuids.SVC_CONFIG,
            char(Uuids.CHAR_2820, propWriteNoResp or propWrite),
            char(Uuids.CHAR_2810, propNotify, cccd = true),
        )
        addService(gatt, Uuids.SVC_CONTROL, char(Uuids.SETTINGS_ACK, propIndicate or propWrite, cccd = true))
        addService(gatt, Uuids.SVC_BATTERY, char(Uuids.CHAR_BATTERY, propRead or propNotify, cccd = true))
        gatt.discoverServices()
    }

    /**
     * Background driver: completes the read/CCCD/MTU ops the shadow does not
     * auto-fire. Loops on virtual time until cancelled. Completers no-op when the
     * pending op type or UUID does not match, so calling them all is safe.
     */
    private fun TestScope.startDriver(queue: BleOpQueue, gatt: BluetoothGatt, radar: Boolean) {
        backgroundScope.launch {
            while (isActive) {
                queue.onMtuChanged(247, BluetoothGatt.GATT_SUCCESS)
                gatt.getService(Uuids.SVC_CONFIG)?.getCharacteristic(Uuids.HANDSHAKE_RX)
                    ?.let { queue.onDescriptorWrite(it.getDescriptor(Uuids.CCCD), BluetoothGatt.GATT_SUCCESS) }
                gatt.getService(Uuids.SVC_CONFIG)?.getCharacteristic(Uuids.CHAR_2810)
                    ?.let { queue.onDescriptorWrite(it.getDescriptor(Uuids.CCCD), BluetoothGatt.GATT_SUCCESS) }
                gatt.getService(Uuids.SVC_CONTROL)?.getCharacteristic(Uuids.SETTINGS_ACK)
                    ?.let { queue.onDescriptorWrite(it.getDescriptor(Uuids.CCCD), BluetoothGatt.GATT_SUCCESS) }
                gatt.getService(Uuids.SVC_BATTERY)?.getCharacteristic(Uuids.CHAR_BATTERY)?.let {
                    queue.onCharacteristicRead(it, byteArrayOf(0x64), BluetoothGatt.GATT_SUCCESS)
                    queue.onDescriptorWrite(it.getDescriptor(Uuids.CCCD), BluetoothGatt.GATT_SUCCESS)
                }
                if (radar) {
                    gatt.getService(Uuids.SVC_RADAR)?.getCharacteristic(Uuids.RADAR_V2)
                        ?.let { queue.onDescriptorWrite(it.getDescriptor(Uuids.CCCD), BluetoothGatt.GATT_SUCCESS) }
                    gatt.getService(Uuids.SVC_DIS)?.let { dis ->
                        listOf(Uuids.DIS_MODEL_NUMBER, Uuids.DIS_FIRMWARE_REV, Uuids.DIS_SERIAL_NUMBER).forEach { u ->
                            dis.getCharacteristic(u)?.let { queue.onCharacteristicRead(it, byteArrayOf(0x00), BluetoothGatt.GATT_SUCCESS) }
                        }
                    }
                }
                delay(10)
            }
        }
    }

    private fun frame(hex: String) = hex.hexToBytes()

    // ── ABORT: TX characteristic missing ───────────────────────────────────────

    @Test fun abortsWhenTxCharMissing() = runTest {
        val queue = BleOpQueue()
        val gatt = forwardingGatt(queue)
        // SVC_CONFIG exists but has no HANDSHAKE_TX: immediate abort, no ops.
        addService(gatt, Uuids.SVC_CONFIG, char(Uuids.HANDSHAKE_RX, propNotify, cccd = true))
        gatt.discoverServices()
        val notifies = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        backgroundScope.launch { queue.run() }

        val log = mutableListOf<String>()
        val ok = RadarUnlock.runHandshake(gatt, queue, notifies, DeviceVariant.RADAR) { log += it }

        assertFalse("missing TX characteristic must abort", ok)
        assertTrue("abort must be logged", log.any { it.contains("TX characteristic not found") })
        queue.cancel()
    }

    @Test fun abortsWhenConfigServiceMissing() = runTest {
        val queue = BleOpQueue()
        val gatt = forwardingGatt(queue)
        gatt.discoverServices() // no services at all
        val notifies = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        backgroundScope.launch { queue.run() }

        val ok = RadarUnlock.runHandshake(gatt, queue, notifies, DeviceVariant.RADAR) {}
        assertFalse("missing config service must abort", ok)
        queue.cancel()
    }

    // ── ABORT: AMV-open reply never arrives ─────────────────────────────────────

    @Test fun abortsWhenAmvOpenReplyTimesOut() = runTest {
        val queue = BleOpQueue()
        val gatt = forwardingGatt(queue)
        setUpRadarServices(gatt)
        startDriver(queue, gatt, radar = true)
        val notifies = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        // Feed nothing: the AMV-open await must time out and abort.
        backgroundScope.launch { queue.run() }

        val log = mutableListOf<String>()
        val ok = RadarUnlock.runHandshake(gatt, queue, notifies, DeviceVariant.RADAR) { log += it }

        assertFalse(ok)
        assertTrue("must abort on the AMV open reply", log.any { it.contains("AMV open reply never arrived") })
        queue.cancel()
    }

    // ── ABORT: cmd-04 reply never arrives ───────────────────────────────────────

    @Test fun abortsWhenCmd04ReplyTimesOut() = runTest {
        val queue = BleOpQueue()
        val gatt = forwardingGatt(queue)
        setUpRadarServices(gatt)
        startDriver(queue, gatt, radar = true)
        val notifies = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        // Feed only the AMV-open reply; starve cmd-04.
        notifies.trySend(Uuids.HANDSHAKE_RX to frame("000600"))
        backgroundScope.launch { queue.run() }

        val log = mutableListOf<String>()
        val ok = RadarUnlock.runHandshake(gatt, queue, notifies, DeviceVariant.RADAR) { log += it }

        assertFalse(ok)
        assertTrue("must abort on the cmd-04 reply", log.any { it.contains("AMV 04 reply never arrived") })
        queue.cancel()
    }

    // ── ABORT: device-ID frame never arrives (RADAR) ────────────────────────────

    @Test fun abortsWhenDeviceIdFrameTimesOut() = runTest {
        val queue = BleOpQueue()
        val gatt = forwardingGatt(queue)
        setUpRadarServices(gatt)
        startDriver(queue, gatt, radar = true)
        val notifies = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        notifies.trySend(Uuids.HANDSHAKE_RX to frame("000600"))
        notifies.trySend(Uuids.HANDSHAKE_RX to frame("0001000000000000000004000040"))
        notifies.trySend(Uuids.HANDSHAKE_RX to frame("0001000000000000000016000000"))
        // Starve the device-ID frame.
        backgroundScope.launch { queue.run() }

        val log = mutableListOf<String>()
        val ok = RadarUnlock.runHandshake(gatt, queue, notifies, DeviceVariant.RADAR) { log += it }

        assertFalse(ok)
        assertTrue("must abort on the device-ID frame", log.any { it.contains("device-ID frame never arrived") })
        queue.cancel()
    }

    // ── ABORT: 0x18 toggle reply never arrives (FRONT_CAMERA) ────────────────────

    @Test fun abortsWhenSubmodeToggleReplyTimesOut() = runTest {
        val queue = BleOpQueue()
        val gatt = forwardingGatt(queue)
        setUpFrontCameraServices(gatt)
        startDriver(queue, gatt, radar = false)
        val notifies = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        // Feed open + cmd-04 on the front-camera RX (2810); starve the 0x18 toggle.
        notifies.trySend(Uuids.CHAR_2810 to frame("000600"))
        notifies.trySend(Uuids.CHAR_2810 to frame("0001000000000000000004000040"))
        backgroundScope.launch { queue.run() }

        val log = mutableListOf<String>()
        val ok = RadarUnlock.runHandshake(gatt, queue, notifies, DeviceVariant.FRONT_CAMERA) { log += it }

        assertFalse(ok)
        assertTrue("must abort on the first 0x18 toggle frame", log.any { it.contains("0x18 toggle frame 1 reply never arrived") })
        queue.cancel()
    }

    // ── HAPPY PATH: RADAR variant ───────────────────────────────────────────────

    @Test fun radarHandshakeCompletes() = runTest {
        val queue = BleOpQueue()
        val gatt = forwardingGatt(queue)
        setUpRadarServices(gatt)
        startDriver(queue, gatt, radar = true)
        val notifies = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        // Pre-load every reply in await order on the radar RX.
        notifies.trySend(Uuids.HANDSHAKE_RX to frame("000600")) // AMV open
        notifies.trySend(Uuids.HANDSHAKE_RX to frame("0001000000000000000004000040")) // cmd-04, pfxEnum=0x40
        notifies.trySend(Uuids.HANDSHAKE_RX to frame("0001000000000000000016000000")) // cmd-16, pfxCmd=0x00
        notifies.trySend(Uuids.HANDSHAKE_RX to frame("80000102030405060708090a0b0c0d0e0f1011121314")) // devId, base=0x80
        backgroundScope.launch { queue.run() }

        val log = mutableListOf<String>()
        val ok = withTimeout(60_000) {
            RadarUnlock.runHandshake(gatt, queue, notifies, DeviceVariant.RADAR) { log += it }
        }

        assertTrue("radar handshake should report success; log=$log", ok)
        assertTrue("handshake complete must be logged", log.any { it.contains("handshake complete") })
        assertTrue(
            "the DIS-CCCD-DIS tail must run for the RADAR variant",
            log.any { it.contains("DIS-CCCD-DIS sequence complete") },
        )
        queue.cancel()
    }

    // ── HAPPY PATH: FRONT_CAMERA variant ────────────────────────────────────────

    @Test fun frontCameraHandshakeCompletes() = runTest {
        val queue = BleOpQueue()
        val gatt = forwardingGatt(queue)
        setUpFrontCameraServices(gatt)
        startDriver(queue, gatt, radar = false)
        val notifies = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        // open + cmd-04 then three 0x18-toggle replies. matchSubmodeReply needs the
        // AMV signature at bytes 8-10 and the 0x18 opcode at byte 11.
        notifies.trySend(Uuids.CHAR_2810 to frame("000600"))
        notifies.trySend(Uuids.CHAR_2810 to frame("0001000000000000000004000040"))
        notifies.trySend(Uuids.CHAR_2810 to frame("0000000000000000414d561800"))
        notifies.trySend(Uuids.CHAR_2810 to frame("0000000000000000414d561882"))
        notifies.trySend(Uuids.CHAR_2810 to frame("0000000000000000414d561800"))
        backgroundScope.launch { queue.run() }

        val log = mutableListOf<String>()
        val ok = withTimeout(60_000) {
            RadarUnlock.runHandshake(gatt, queue, notifies, DeviceVariant.FRONT_CAMERA) { log += it }
        }

        assertTrue("front camera handshake should report success; log=$log", ok)
        assertTrue("front camera handshake complete must be logged", log.any { it.contains("front camera handshake complete") })
        queue.cancel()
    }
}
