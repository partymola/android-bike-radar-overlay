// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.content.Context

/**
 * Opens a GATT connection pinned to the LE transport.
 *
 * The explicit-transport `connectGatt(context, autoConnect, callback, transport)`
 * overload is flagged deprecated on the current compileSdk, but the app keeps it
 * deliberately: every link here is BLE, and pinning [BluetoothDevice.TRANSPORT_LE]
 * stops the stack from probing BR/EDR on dual-mode peers. Centralised so the
 * deprecation suppression lives in exactly one place, shared by the radar,
 * camera-light, and battery-read paths.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal fun connectGattLe(
    context: Context,
    device: BluetoothDevice,
    autoConnect: Boolean,
    callback: BluetoothGattCallback,
): BluetoothGatt? = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
