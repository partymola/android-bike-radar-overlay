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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/**
 * Robolectric harness for the live [BleOpQueue] consumer.
 *
 * The Robolectric [org.robolectric.shadows.ShadowBluetoothGatt] does not
 * complete write/read/MTU GATT callbacks back to the production queue in a way
 * the queue can rely on (robolectric#8374), so these tests drive the queue
 * explicitly: the consumer runs in a background coroutine under [runTest]
 * virtual time, [runCurrent] lets the enqueued op reach its pending state
 * (without advancing past the op timeout), and the matching completer is
 * called by hand.
 *
 * The test registers a no-op [BluetoothGattCallback] so the shadow's
 * non-null-callback guards pass; the shadow's own synchronous auto-fired
 * callbacks land on that no-op and are discarded - the queue only advances
 * when a test calls a completer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BleOpQueueHarnessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val noopCallback = object : BluetoothGattCallback() {}

    /** A connected real GATT with a registered no-op callback. */
    private fun connectedGatt(): BluetoothGatt {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        val gatt = device.connectGatt(context, false, noopCallback)
        return gatt
    }

    private fun charWith(uuid: UUID, properties: Int, withCccd: Boolean = false): BluetoothGattCharacteristic {
        val ch = BluetoothGattCharacteristic(
            uuid,
            properties,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        if (withCccd) {
            ch.addDescriptor(
                BluetoothGattDescriptor(
                    Uuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        return ch
    }

    /** Registers a single-characteristic service on the gatt and returns the char. */
    private fun serviceWithChar(
        gatt: BluetoothGatt,
        svcUuid: UUID,
        char: BluetoothGattCharacteristic,
    ): BluetoothGattCharacteristic {
        val svc = BluetoothGattService(svcUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(char)
        shadowOf(gatt).addDiscoverableService(svc)
        gatt.discoverServices()
        return gatt.getService(svcUuid).getCharacteristic(char.uuid)
    }

    // ── write ────────────────────────────────────────────────────────────────

    @Test fun writeCompletesTrueOnSuccess() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_TX, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.write(gatt, char, byteArrayOf(0x01, 0x02), noResponse = true) }
        runCurrent()
        queue.onCharacteristicWrite(char, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertTrue("write must report success when callback returns GATT_SUCCESS", deferred.await())
        queue.cancel()
    }

    @Test fun writeCompletesFalseOnGattError() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_TX, BluetoothGattCharacteristic.PROPERTY_WRITE),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.write(gatt, char, byteArrayOf(0x01), noResponse = false) }
        runCurrent()
        queue.onCharacteristicWrite(char, BluetoothGatt.GATT_FAILURE)
        runCurrent()

        assertFalse("non-SUCCESS status must complete the write as false", deferred.await())
        queue.cancel()
    }

    @Test fun writeCallbackForWrongCharIsIgnored() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_TX, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.write(gatt, char, byteArrayOf(0x01), noResponse = true) }
        runCurrent()

        // A callback for a different characteristic UUID must not complete the op.
        val otherChar = charWith(UUID.fromString("6a4e9999-667b-11e3-949a-0800200c9a66"), BluetoothGattCharacteristic.PROPERTY_WRITE)
        queue.onCharacteristicWrite(otherChar, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertFalse("op must still be pending after a mismatched callback", deferred.isCompleted)

        // The correct callback then completes it.
        queue.onCharacteristicWrite(char, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertTrue(deferred.await())
        queue.cancel()
    }

    // ── read ─────────────────────────────────────────────────────────────────

    @Test fun readCompletesWithValueOnSuccess() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_BATTERY,
            charWith(Uuids.CHAR_BATTERY, BluetoothGattCharacteristic.PROPERTY_READ),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.read(gatt, char) }
        runCurrent()
        queue.onCharacteristicRead(char, byteArrayOf(0x55), BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertArrayEqualsByteArray(byteArrayOf(0x55), deferred.await())
        queue.cancel()
    }

    @Test fun readCompletesNullOnGattError() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_BATTERY,
            charWith(Uuids.CHAR_BATTERY, BluetoothGattCharacteristic.PROPERTY_READ),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.read(gatt, char) }
        runCurrent()
        queue.onCharacteristicRead(char, byteArrayOf(0x55), BluetoothGatt.GATT_FAILURE)
        runCurrent()

        assertNull("read must complete null on a non-SUCCESS status", deferred.await())
        queue.cancel()
    }

    @Test fun readCallbackForWrongCharIsIgnored() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_BATTERY,
            charWith(Uuids.CHAR_BATTERY, BluetoothGattCharacteristic.PROPERTY_READ),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.read(gatt, char) }
        runCurrent()
        val otherChar = charWith(Uuids.DIS_MODEL_NUMBER, BluetoothGattCharacteristic.PROPERTY_READ)
        queue.onCharacteristicRead(otherChar, byteArrayOf(0x11), BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertFalse(deferred.isCompleted)

        queue.onCharacteristicRead(char, byteArrayOf(0x22), BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertArrayEqualsByteArray(byteArrayOf(0x22), deferred.await())
        queue.cancel()
    }

    // ── CCCD write ─────────────────────────────────────────────────────────────

    @Test fun writeCccdCompletesTrueOnSuccess() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_RX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, withCccd = true),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.writeCccd(gatt, char) }
        runCurrent()
        val cccd = char.getDescriptor(Uuids.CCCD)
        queue.onDescriptorWrite(cccd, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertTrue(deferred.await())
        queue.cancel()
    }

    @Test fun writeCccdReturnsFalseWhenNoCccdDescriptor() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        // Characteristic without a CCCD descriptor: writeCccd short-circuits false
        // before ever enqueuing an op.
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_RX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, withCccd = false),
        )
        backgroundScope.launch { queue.run() }

        val result = queue.writeCccd(gatt, char)
        assertFalse("a missing CCCD descriptor must short-circuit to false", result)
        queue.cancel()
    }

    @Test fun descriptorCallbackForWrongUuidIsIgnored() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_RX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, withCccd = true),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.writeCccd(gatt, char) }
        runCurrent()
        // A descriptor with a different UUID must be ignored by the completer.
        val otherDesc = BluetoothGattDescriptor(
            UUID.fromString("00002903-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        queue.onDescriptorWrite(otherDesc, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertFalse(deferred.isCompleted)

        queue.onDescriptorWrite(char.getDescriptor(Uuids.CCCD), BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertTrue(deferred.await())
        queue.cancel()
    }

    // ── MTU ──────────────────────────────────────────────────────────────────

    @Test fun requestMtuCompletesWithMtuOnSuccess() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.requestMtu(gatt, 247) }
        runCurrent()
        queue.onMtuChanged(247, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertEquals(247, deferred.await())
        queue.cancel()
    }

    @Test fun requestMtuCompletesMinusOneOnError() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.requestMtu(gatt, 247) }
        runCurrent()
        queue.onMtuChanged(247, BluetoothGatt.GATT_FAILURE)
        runCurrent()

        assertEquals("a non-SUCCESS MTU status must complete as -1", -1, deferred.await())
        queue.cancel()
    }

    // ── timeout ────────────────────────────────────────────────────────────────

    @Test fun writeTimesOutToFalseWhenCallbackNeverArrives() = runTest {
        val queue = BleOpQueue(timeoutMs = 1_000)
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_TX, BluetoothGattCharacteristic.PROPERTY_WRITE),
        )
        backgroundScope.launch { queue.run() }

        // Never call the completer: withTimeoutOrNull must fire and complete false.
        val result = queue.write(gatt, char, byteArrayOf(0x01), noResponse = false)
        assertFalse("an unanswered write must time out to false", result)
        queue.cancel()
    }

    @Test fun readTimesOutToNullWhenCallbackNeverArrives() = runTest {
        val queue = BleOpQueue(timeoutMs = 1_000)
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_BATTERY,
            charWith(Uuids.CHAR_BATTERY, BluetoothGattCharacteristic.PROPERTY_READ),
        )
        backgroundScope.launch { queue.run() }

        val result = queue.read(gatt, char)
        assertNull("an unanswered read must time out to null", result)
        queue.cancel()
    }

    @Test fun mtuTimesOutToMinusOneWhenCallbackNeverArrives() = runTest {
        val queue = BleOpQueue(timeoutMs = 1_000)
        val gatt = connectedGatt()
        backgroundScope.launch { queue.run() }

        val result = queue.requestMtu(gatt, 247)
        assertEquals("an unanswered MTU request must time out to -1", -1, result)
        queue.cancel()
    }

    // ── cancel ───────────────────────────────────────────────────────────────

    @Test fun cancelCompletesPendingWriteAsFalse() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_TX, BluetoothGattCharacteristic.PROPERTY_WRITE),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.write(gatt, char, byteArrayOf(0x01), noResponse = false) }
        runCurrent()
        assertFalse("op must be in flight before cancel", deferred.isCompleted)

        queue.cancel()
        runCurrent()
        assertFalse("cancel must complete the pending write as false", deferred.await())
    }

    @Test fun cancelCompletesPendingReadAsNull() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_BATTERY,
            charWith(Uuids.CHAR_BATTERY, BluetoothGattCharacteristic.PROPERTY_READ),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.read(gatt, char) }
        runCurrent()
        queue.cancel()
        runCurrent()
        assertNull("cancel must complete the pending read as null", deferred.await())
    }

    @Test fun cancelCompletesPendingMtuAsMinusOne() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.requestMtu(gatt, 247) }
        runCurrent()
        queue.cancel()
        runCurrent()
        assertEquals("cancel must complete the pending MTU as -1", -1, deferred.await())
    }

    @Test fun cancelCompletesPendingCccdAsFalse() = runTest {
        val queue = BleOpQueue()
        val gatt = connectedGatt()
        val char = serviceWithChar(
            gatt,
            Uuids.SVC_CONFIG,
            charWith(Uuids.HANDSHAKE_RX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, withCccd = true),
        )
        backgroundScope.launch { queue.run() }

        val deferred = async { queue.writeCccd(gatt, char) }
        runCurrent()
        queue.cancel()
        runCurrent()
        assertFalse("cancel must complete the pending CCCD write as false", deferred.await())
    }

    private fun assertArrayEqualsByteArray(expected: ByteArray, actual: ByteArray?) {
        assertTrue(
            "expected ${expected.toHex()} got ${actual?.toHex()}",
            actual != null && actual.contentEquals(expected),
        )
    }
}
