// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Pins [BluetoothStateMonitor]'s contract: exactly the terminal adapter
 * states trigger the recovery hooks (OFF -> teardown, ON -> restart), the
 * transitional TURNING_* states are ignored (acting on them would tear down
 * twice), and register/unregister are idempotent. The hooks' wiring into the
 * service (link cancel, scan re-register, kickstart) is thin glue over
 * already-tested components.
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothStateMonitorTest {

    private lateinit var context: Context
    private var offCalls = 0
    private var onCalls = 0
    private lateinit var monitor: BluetoothStateMonitor

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        monitor = BluetoothStateMonitor(
            context = context,
            onAdapterOff = { offCalls++ },
            onAdapterOn = { onCalls++ },
        )
    }

    private fun broadcastState(state: Int) {
        val intent = Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, state)
        context.sendBroadcast(intent)
        shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()
    }

    @Test
    fun terminalStates_triggerTheMatchingHook() {
        monitor.register()
        broadcastState(BluetoothAdapter.STATE_OFF)
        broadcastState(BluetoothAdapter.STATE_ON)
        assertEquals(1, offCalls)
        assertEquals(1, onCalls)
        monitor.unregister()
    }

    @Test
    fun transitionalStates_areIgnored() {
        monitor.register()
        broadcastState(BluetoothAdapter.STATE_TURNING_OFF)
        broadcastState(BluetoothAdapter.STATE_TURNING_ON)
        assertEquals(0, offCalls)
        assertEquals(0, onCalls)
        monitor.unregister()
    }

    @Test
    fun missingStateExtra_isIgnored() {
        monitor.register()
        context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
        shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()
        assertEquals(0, offCalls)
        assertEquals(0, onCalls)
        monitor.unregister()
    }

    @Test
    fun beforeRegister_andAfterUnregister_nothingFires() {
        broadcastState(BluetoothAdapter.STATE_OFF)
        assertEquals(0, offCalls)

        monitor.register()
        monitor.unregister()
        broadcastState(BluetoothAdapter.STATE_OFF)
        assertEquals(0, offCalls)
    }

    @Test
    fun registerAndUnregister_areIdempotent() {
        monitor.register()
        monitor.register() // second call must not double-register
        broadcastState(BluetoothAdapter.STATE_OFF)
        assertEquals("a double register must not deliver twice", 1, offCalls)

        monitor.unregister()
        monitor.unregister() // second call must not throw
    }

    @Test
    fun fullCycle_offThenOn_ordersHooksCorrectly() {
        val order = mutableListOf<String>()
        val m = BluetoothStateMonitor(
            context = context,
            onAdapterOff = { order.add("off") },
            onAdapterOn = { order.add("on") },
        )
        m.register()
        broadcastState(BluetoothAdapter.STATE_TURNING_OFF)
        broadcastState(BluetoothAdapter.STATE_OFF)
        broadcastState(BluetoothAdapter.STATE_TURNING_ON)
        broadcastState(BluetoothAdapter.STATE_ON)
        assertEquals(listOf("off", "on"), order)
        m.unregister()
    }
}
