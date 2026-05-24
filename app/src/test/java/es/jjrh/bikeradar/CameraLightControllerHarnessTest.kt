// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/**
 * Robolectric harness for [CameraLightController.setMode] - the instance path
 * that resolves SETTINGS_ACK on the live GATT and writes through [BleOpQueue].
 *
 * The companion `encodeWrite`/`parseModeStateNotify` are pinned by the pure-JVM
 * [CameraLightModeWireFormatTest] / [CameraLightModeNotifyParserTest]; this file
 * only covers the GATT-touching instance method.
 *
 * Drives the queue exactly as [BleOpQueueHarnessTest] does: a background
 * consumer under [runTest] virtual time, [runCurrent] to reach pending state,
 * then the write completer by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CameraLightControllerHarnessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val noopCallback = object : BluetoothGattCallback() {}

    private fun connectedGatt(): BluetoothGatt {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
            .connectGatt(context, false, noopCallback)
    }

    /** A GATT exposing SVC_CONTROL with a writable SETTINGS_ACK characteristic. */
    private fun gattWithSettingsAck(): Pair<BluetoothGatt, BluetoothGattCharacteristic> {
        val gatt = connectedGatt()
        val ack = BluetoothGattCharacteristic(
            Uuids.SETTINGS_ACK,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val svc = BluetoothGattService(Uuids.SVC_CONTROL, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(ack)
        shadowOf(gatt).addDiscoverableService(svc)
        gatt.discoverServices()
        return gatt to gatt.getService(Uuids.SVC_CONTROL).getCharacteristic(Uuids.SETTINGS_ACK)
    }

    @Test fun setModeWritesAndReturnsTrueOnSuccess() = runTest {
        val (gatt, ack) = gattWithSettingsAck()
        val queue = BleOpQueue()
        backgroundScope.launch { queue.run() }
        val controller = CameraLightController(gatt, queue)

        val deferred = async { controller.setMode(CameraLightMode.DAY_FLASH) }
        runCurrent()
        // The write op is now in flight on SETTINGS_ACK; complete it.
        queue.onCharacteristicWrite(ack, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        assertTrue("setMode must return true when the write callback succeeds", deferred.await())
        queue.cancel()
    }

    @Test fun setModeReturnsFalseOnWriteError() = runTest {
        val (gatt, ack) = gattWithSettingsAck()
        val queue = BleOpQueue()
        backgroundScope.launch { queue.run() }
        val controller = CameraLightController(gatt, queue)

        val deferred = async { controller.setMode(CameraLightMode.OFF) }
        runCurrent()
        queue.onCharacteristicWrite(ack, BluetoothGatt.GATT_FAILURE)
        runCurrent()

        assertFalse("a non-SUCCESS write status must make setMode return false", deferred.await())
        queue.cancel()
    }

    @Test fun setModeReturnsFalseWhenServiceMissing() = runTest {
        // GATT with no SVC_CONTROL service at all: the characteristic lookup
        // fails and setMode must short-circuit to false without enqueuing.
        val gatt = connectedGatt()
        val queue = BleOpQueue()
        backgroundScope.launch { queue.run() }
        val controller = CameraLightController(gatt, queue)

        val result = controller.setMode(CameraLightMode.HIGH)
        assertFalse("a missing SVC_CONTROL service must make setMode return false", result)
        queue.cancel()
    }

    @Test fun setModeReturnsFalseWhenCharMissing() = runTest {
        // SVC_CONTROL present but without SETTINGS_ACK: same false short-circuit.
        val gatt = connectedGatt()
        val svc = BluetoothGattService(Uuids.SVC_CONTROL, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID.fromString("6a4e2f99-667b-11e3-949a-0800200c9a66"),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        shadowOf(gatt).addDiscoverableService(svc)
        gatt.discoverServices()

        val queue = BleOpQueue()
        backgroundScope.launch { queue.run() }
        val controller = CameraLightController(gatt, queue)

        val result = controller.setMode(CameraLightMode.NIGHT_FLASH)
        assertFalse("a missing SETTINGS_ACK characteristic must make setMode return false", result)
        queue.cancel()
    }
}
