// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Watches the Bluetooth adapter's on/off transitions and fans them out to the
 * service's recovery hooks.
 *
 * Why it exists: a mid-ride Bluetooth stack restart (Bluedroid crash, a manual
 * toggle, an OS adapter reset) kills everything this app has open - the
 * PendingIntent event scan dies with the adapter, and any GATT client parked
 * inside a connect (`autoConnect=true` waits indefinitely for its device by
 * design, so the wait itself is healthy) is orphaned on the dead stack
 * instance with no guarantee its callbacks ever fire again. Nothing recovers
 * by itself: the app keeps saying "reconnecting" for the rest of the ride.
 *
 * Recovery is therefore STATE-driven, not timeout-driven: a timeout on the
 * connect wait would break the legitimate low-power "connect whenever the
 * radar appears" mode, whereas the adapter broadcast distinguishes exactly
 * the case where the wait can never complete. On [BluetoothAdapter.STATE_OFF]
 * the caller cancels its link coroutines (their cleanup paths close the
 * orphaned GATTs) and marks the event scan dead; on
 * [BluetoothAdapter.STATE_ON] it re-registers the scan and kickstarts the
 * links against the fresh stack.
 *
 * Only the terminal states trigger the hooks - TURNING_OFF/TURNING_ON are
 * transitional and acting on them would tear down twice.
 */
internal class BluetoothStateMonitor(
    private val context: Context,
    private val onAdapterOff: () -> Unit,
    private val onAdapterOn: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> onAdapterOff()
                BluetoothAdapter.STATE_ON -> onAdapterOn()
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
            // Best-effort: the platform may have already dropped the
            // receiver during teardown; there is nothing left to undo.
        }
        registered = false
    }
}
