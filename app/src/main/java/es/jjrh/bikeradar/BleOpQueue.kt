// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single-inflight GATT operation queue.
 *
 * All GATT operations (CCCD writes, reads, writes, MTU) must go through this
 * queue because Android's Bluetooth stack processes at most one GATT operation
 * at a time per connection - firing two concurrently results in one silently
 * dropped. The queue serialises callers: each op blocks until its GATT callback
 * fires (or the per-op timeout expires).
 *
 * Usage:
 *   val q = BleOpQueue()
 *   q.launch(scope)                      // start the consumer coroutine
 *   val ok = q.writeCccd(gatt, char)
 *   val bytes = q.read(gatt, char)
 *   q.cancel()                           // shut down on disconnect
 *
 * The consumer processes one op at a time. Each op submits its Android call
 * immediately, then suspends on a [CompletableDeferred] that the matching
 * GATT callback completes. On timeout the op returns null/false and the next
 * op is processed.
 */
class BleOpQueue(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private sealed class Op {
        abstract val result: CompletableDeferred<*>

        data class CccdWrite(
            val gatt: BluetoothGatt,
            val descriptor: BluetoothGattDescriptor,
            val value: ByteArray,
            override val result: CompletableDeferred<Boolean> = CompletableDeferred(),
        ) : Op()

        data class Read(
            val gatt: BluetoothGatt,
            val char: BluetoothGattCharacteristic,
            override val result: CompletableDeferred<ByteArray?> = CompletableDeferred(),
        ) : Op()

        data class Write(
            val gatt: BluetoothGatt,
            val char: BluetoothGattCharacteristic,
            val bytes: ByteArray,
            val noResponse: Boolean,
            override val result: CompletableDeferred<Boolean> = CompletableDeferred(),
        ) : Op()

        data class Mtu(
            val gatt: BluetoothGatt,
            val mtu: Int,
            override val result: CompletableDeferred<Int> = CompletableDeferred(),
        ) : Op()
    }

    private val channel = Channel<Op>(Channel.UNLIMITED)

    // Pending op currently in flight — completed by GATT callbacks.
    @Volatile private var pending: Op? = null

    // ── consumer ──────────────────────────────────────────────────────────────

    suspend fun run() {
        for (op in channel) {
            pending = op
            @Suppress("UNCHECKED_CAST")
            val timedOut = withTimeoutOrNull(timeoutMs) {
                dispatch(op)
                (op.result as CompletableDeferred<Any?>).await()
            } == null
            if (timedOut) {
                Log.w(TAG, "BleOpQueue timeout on $op")
                completeAny(op, timedOut = true)
            }
            pending = null
        }
    }

    fun cancel() {
        channel.close()
        pending?.let { completeAny(it, timedOut = true) }
        pending = null
    }

    // ── enqueuers (called from coroutines that need the result) ───────────────

    // GATT ops require BLUETOOTH_CONNECT; the queue only runs against a live
    // connection the service opened after the permission was granted.
    @SuppressLint("MissingPermission")
    suspend fun writeCccd(gatt: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
        gatt.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(Uuids.CCCD) ?: return false
        val value = if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val op = Op.CccdWrite(gatt, desc, value)
        channel.send(op)
        return op.result.await()
    }

    @SuppressLint("MissingPermission")
    suspend fun read(gatt: BluetoothGatt, char: BluetoothGattCharacteristic): ByteArray? {
        val op = Op.Read(gatt, char)
        channel.send(op)
        return op.result.await()
    }

    @SuppressLint("MissingPermission")
    suspend fun write(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        bytes: ByteArray,
        noResponse: Boolean = false,
    ): Boolean {
        val op = Op.Write(gatt, char, bytes, noResponse)
        channel.send(op)
        return op.result.await()
    }

    @SuppressLint("MissingPermission")
    suspend fun requestMtu(gatt: BluetoothGatt, mtu: Int): Int {
        val op = Op.Mtu(gatt, mtu)
        channel.send(op)
        return op.result.await()
    }

    // ── GATT callback completers (call from BluetoothGattCallback) ────────────

    fun onDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int) {
        val op = pending as? Op.CccdWrite ?: return
        if (op.descriptor.uuid == descriptor.uuid) {
            op.result.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    fun onCharacteristicRead(char: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val op = pending as? Op.Read ?: return
        if (op.char.uuid == char.uuid) {
            op.result.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
    }

    fun onCharacteristicWrite(char: BluetoothGattCharacteristic, status: Int) {
        val op = pending as? Op.Write ?: return
        if (op.char.uuid == char.uuid) {
            op.result.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    fun onMtuChanged(mtu: Int, status: Int) {
        val op = pending as? Op.Mtu ?: return
        op.result.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else -1)
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * The synchronous Android GATT-submit calls (writeDescriptor,
     * readCharacteristic, writeCharacteristic, requestMtu) sometimes fail
     * transiently when the stack is briefly busy with internal housekeeping
     * - the legacy calls return false, and the API 33+ write overloads
     * return a non-SUCCESS status. [retrySubmit] normalises both to a
     * Boolean and retries a few times with a small gap, recovering the op
     * without bubbling a spurious failure to the caller. Only after
     * [SUBMIT_RETRY_ATTEMPTS] consecutive failures do we complete the op as
     * failed.
     */
    @SuppressLint("MissingPermission")
    private suspend fun dispatch(op: Op) {
        when (op) {
            is Op.CccdWrite -> {
                val ok = retrySubmit {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        op.gatt.writeDescriptor(op.descriptor, op.value) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        op.descriptor.value = op.value
                        @Suppress("DEPRECATION")
                        op.gatt.writeDescriptor(op.descriptor)
                    }
                }
                if (!ok) {
                    // Distinguishes a rejected/non-SUCCESS submit from a
                    // GATT-callback timeout (handled in completeAny) when
                    // reading the capture log after a handshake abort.
                    Log.w(TAG, "CCCD descriptor write not submitted")
                    op.result.complete(false)
                }
            }
            is Op.Read -> {
                val ok = retrySubmit { op.gatt.readCharacteristic(op.char) }
                if (!ok) op.result.complete(null)
            }
            is Op.Write -> {
                val ok = retrySubmit {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val type = if (op.noResponse)
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        else
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        op.gatt.writeCharacteristic(op.char, op.bytes, type) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        op.char.writeType = if (op.noResponse)
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        else
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        op.char.value = op.bytes
                        @Suppress("DEPRECATION")
                        op.gatt.writeCharacteristic(op.char)
                    }
                }
                if (!ok) op.result.complete(false)
            }
            is Op.Mtu -> {
                val ok = retrySubmit { op.gatt.requestMtu(op.mtu) }
                if (!ok) op.result.complete(-1)
            }
        }
    }

    internal suspend fun retrySubmit(submit: () -> Boolean): Boolean {
        repeat(SUBMIT_RETRY_ATTEMPTS) { attempt ->
            if (submit()) return true
            if (attempt < SUBMIT_RETRY_ATTEMPTS - 1) delay(SUBMIT_RETRY_GAP_MS)
        }
        return false
    }

    private fun completeAny(op: Op, timedOut: Boolean) {
        when (op) {
            is Op.CccdWrite -> if (!op.result.isCompleted) op.result.complete(!timedOut)
            is Op.Read      -> if (!op.result.isCompleted) op.result.complete(null)
            is Op.Write     -> if (!op.result.isCompleted) op.result.complete(!timedOut)
            is Op.Mtu       -> if (!op.result.isCompleted) op.result.complete(-1)
        }
    }

    companion object {
        private const val TAG = "BleOpQueue"
        const val DEFAULT_TIMEOUT_MS = 5000L

        // Per-op submit retry: tolerates transient false returns from the
        // Android stack while it is briefly busy. 5 attempts at 50 ms
        // covers the typical 100-200 ms recovery window without unduly
        // blocking the queue.
        private const val SUBMIT_RETRY_ATTEMPTS = 5
        private const val SUBMIT_RETRY_GAP_MS = 50L
    }
}
